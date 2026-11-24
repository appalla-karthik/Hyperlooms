package dev.hyperlooms.controlplane.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;
import dev.hyperlooms.controlplane.job.LeaseAcknowledgementDeniedException;
import dev.hyperlooms.controlplane.job.LeaseAcknowledgementService;
import dev.hyperlooms.controlplane.job.LeaseAssignment;
import dev.hyperlooms.controlplane.job.LeaseOfferService;
import dev.hyperlooms.protocol.agent.v1.AgentHeartbeat;
import dev.hyperlooms.protocol.agent.v1.AgentHello;
import dev.hyperlooms.protocol.agent.v1.AgentLeaseReceipt;
import dev.hyperlooms.protocol.agent.v1.AgentSessionServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.ConnectRequest;
import dev.hyperlooms.protocol.agent.v1.ConnectResponse;
import dev.hyperlooms.protocol.agent.v1.GpuCapability;
import dev.hyperlooms.protocol.agent.v1.HeartbeatAcknowledgement;
import dev.hyperlooms.protocol.agent.v1.HelloAcknowledgement;
import dev.hyperlooms.protocol.agent.v1.HelloRejectionReason;
import dev.hyperlooms.protocol.agent.v1.LeaseOffer;
import dev.hyperlooms.protocol.agent.v1.LeaseReceiptAcknowledgement;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** An authenticated stream accepts one hello followed by strictly ordered heartbeats. */
@Component
public final class AgentSessionGrpcService extends AgentSessionServiceGrpc.AgentSessionServiceImplBase {

    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private final ProviderCapabilityService capabilities;
    private final LeaseOfferService offers;
    private final LeaseAcknowledgementService acknowledgements;

    public AgentSessionGrpcService(ProviderCapabilityService capabilities,
            LeaseOfferService offers, LeaseAcknowledgementService acknowledgements) {
        this.capabilities = capabilities;
        this.offers = offers;
        this.acknowledgements = acknowledgements;
    }

    @Override
    public StreamObserver<ConnectRequest> connect(StreamObserver<ConnectResponse> responses) {
        ProviderPeerIdentity peer = AgentPeerInterceptor.PEER.get();
        if (peer == null) {
            responses.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return new StreamObserver<>() {
                @Override public void onNext(ConnectRequest ignored) { }
                @Override public void onError(Throwable ignored) { }
                @Override public void onCompleted() { }
            };
        }
        return new Session(peer, responses);
    }

    private final class Session implements StreamObserver<ConnectRequest> {
        private final ProviderPeerIdentity peer;
        private final StreamObserver<ConnectResponse> responses;
        private boolean accepted;
        private boolean closed;
        private long lastSequence;

        private Session(ProviderPeerIdentity peer, StreamObserver<ConnectResponse> responses) {
            this.peer = peer;
            this.responses = responses;
        }

        @Override
        public void onNext(ConnectRequest request) {
            if (closed) {
                return;
            }
            if (request.getMessageId().isBlank() || request.getMessageId().length() > 100) {
                fail(Status.INVALID_ARGUMENT.withDescription("invalid message ID"));
                return;
            }
            try {
                if (!accepted && request.hasHello()) {
                    hello(request);
                } else if (accepted && request.hasHeartbeat()) {
                    heartbeat(request);
                } else if (accepted && request.hasLeaseReceipt()) {
                    receipt(request);
                } else {
                    fail(Status.FAILED_PRECONDITION.withDescription(
                            "exactly one hello is required before agent messages"));
                }
            } catch (ProviderIdentityDeniedException exception) {
                fail(Status.PERMISSION_DENIED.withDescription("provider identity denied"));
            } catch (LeaseAcknowledgementDeniedException exception) {
                fail(Status.PERMISSION_DENIED.withDescription("lease receipt denied"));
            } catch (IllegalArgumentException exception) {
                fail(Status.INVALID_ARGUMENT.withDescription("invalid agent message"));
            } catch (RuntimeException exception) {
                fail(Status.INTERNAL.withDescription("agent session failed"));
            }
        }

        private void hello(ConnectRequest request) {
            AgentHello hello = request.getHello();
            UUID claimedNode = UUID.fromString(hello.getNodeId());
            if (!peer.nodeId().equals(claimedNode)) {
                rejectHello(request.getMessageId(),
                        HelloRejectionReason.HELLO_REJECTION_REASON_IDENTITY_MISMATCH);
                return;
            }
            if (hello.getProtocolVersion() != 1) {
                rejectHello(request.getMessageId(),
                        HelloRejectionReason.HELLO_REJECTION_REASON_UNSUPPORTED_PROTOCOL);
                return;
            }
            List<GpuCapabilityReport> gpus = hello.getGpusList().stream()
                    .map(this::toReport)
                    .toList();
            ProviderCapabilityReport report = new ProviderCapabilityReport(
                    claimedNode, hello.getProtocolVersion(), hello.getAgentVersion(), gpus);
            capabilities.acceptHelloFromAuthenticatedPeer(peer, report);
            accepted = true;
            responses.onNext(ConnectResponse.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setHelloAcknowledgement(HelloAcknowledgement.newBuilder()
                            .setRequestMessageId(request.getMessageId())
                            .setAccepted(true)
                            .setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS)
                            .setServerTime(now()))
                    .build());
        }

        private GpuCapabilityReport toReport(GpuCapability gpu) {
            return new GpuCapabilityReport(gpu.getGpuUuid(), gpu.getModel(),
                    gpu.getVramBytes(), gpu.getDriverVersion(), gpu.getCudaVersion());
        }

        private void heartbeat(ConnectRequest request) {
            AgentHeartbeat heartbeat = request.getHeartbeat();
            if (!peer.nodeId().toString().equals(heartbeat.getNodeId())
                    || heartbeat.getSequence() == 0
                    || Long.compareUnsigned(heartbeat.getSequence(), lastSequence) <= 0) {
                fail(Status.INVALID_ARGUMENT.withDescription("invalid heartbeat identity or sequence"));
                return;
            }
            capabilities.recordHeartbeatFromAuthenticatedPeer(peer);
            lastSequence = heartbeat.getSequence();
            offers.nextIssuedForPeer(peer).ifPresent(assignment -> responses.onNext(
                    ConnectResponse.newBuilder()
                            .setMessageId(UUID.randomUUID().toString())
                            .setLeaseOffer(toOffer(assignment))
                            .build()));
            responses.onNext(ConnectResponse.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setHeartbeatAcknowledgement(HeartbeatAcknowledgement.newBuilder()
                            .setRequestMessageId(request.getMessageId())
                            .setServerTime(now()))
                    .build());
        }

        private void receipt(ConnectRequest request) {
            AgentLeaseReceipt receipt = request.getLeaseReceipt();
            if (!peer.nodeId().toString().equals(receipt.getNodeId())
                    || receipt.getFencingToken() <= 0) {
                fail(Status.PERMISSION_DENIED.withDescription("lease receipt denied"));
                return;
            }
            UUID leaseId = UUID.fromString(receipt.getLeaseId());
            acknowledgements.acknowledge(peer, leaseId, receipt.getFencingToken());
            responses.onNext(ConnectResponse.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setLeaseReceiptAcknowledgement(LeaseReceiptAcknowledgement.newBuilder()
                            .setRequestMessageId(request.getMessageId())
                            .setLeaseId(leaseId.toString())
                            .setServerTime(now()))
                    .build());
        }

        private void rejectHello(String requestId, HelloRejectionReason reason) {
            responses.onNext(ConnectResponse.newBuilder()
                    .setMessageId(UUID.randomUUID().toString())
                    .setHelloAcknowledgement(HelloAcknowledgement.newBuilder()
                            .setRequestMessageId(requestId)
                            .setAccepted(false)
                            .setRejectionReason(reason)
                            .setServerTime(now()))
                    .build());
            closed = true;
            responses.onCompleted();
        }

        private void fail(Status status) {
            closed = true;
            responses.onError(status.asRuntimeException());
        }

        @Override
        public void onError(Throwable ignored) {
            closed = true;
        }

        @Override
        public void onCompleted() {
            if (!closed) {
                closed = true;
                responses.onCompleted();
            }
        }
    }

    private static Timestamp now() {
        return timestamp(Instant.now());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano()).build();
    }

    private static LeaseOffer toOffer(LeaseAssignment assignment) {
        return LeaseOffer.newBuilder()
                .setJobId(assignment.jobId().toString())
                .setAttemptId(assignment.attemptId().toString())
                .setLeaseId(assignment.leaseId().toString())
                .setNodeId(assignment.nodeId().toString())
                .setGpuUuid(assignment.gpuUuid())
                .setFencingToken(assignment.fencingToken())
                .setExpiresAt(timestamp(assignment.expiresAt()))
                .build();
    }
}
