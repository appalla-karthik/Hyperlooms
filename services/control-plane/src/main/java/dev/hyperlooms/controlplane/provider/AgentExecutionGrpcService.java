package dev.hyperlooms.controlplane.provider;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import dev.hyperlooms.controlplane.job.LeaseExecutionDeniedException;
import dev.hyperlooms.controlplane.job.LeaseRuntimeService;
import dev.hyperlooms.controlplane.job.VerifiedResultService;
import dev.hyperlooms.protocol.agent.v1.AgentExecutionServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.AdvanceRequest;
import dev.hyperlooms.protocol.agent.v1.AdvanceResponse;
import dev.hyperlooms.protocol.agent.v1.UploadResultRequest;
import dev.hyperlooms.protocol.agent.v1.UploadResultResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** Opt-in mTLS execution RPC. An agent cannot choose a recipe, object key, or another node. */
@Component
public final class AgentExecutionGrpcService extends AgentExecutionServiceGrpc.AgentExecutionServiceImplBase {
    private static final int CHUNK_BYTES = 32 * 1024;
    private final LeaseRuntimeService runtime;
    private final VerifiedResultService results;
    private final boolean enabled;

    public AgentExecutionGrpcService(LeaseRuntimeService runtime, VerifiedResultService results,
            @Value("${hyperlooms.agent.execution-enabled:false}") boolean enabled,
            @Value("${hyperlooms.agent.command-staging-enabled:false}") boolean staging,
            @Value("${hyperlooms.agent.input-read-enabled:false}") boolean inputRead,
            @Value("${hyperlooms.requester-api.enabled:false}") boolean requesterApi,
            @Value("${hyperlooms.lease-expiry.enabled:false}") boolean expiryWorker) {
        if (enabled && (!staging || !inputRead || !requesterApi || !expiryWorker)) {
            throw new IllegalStateException("execution requires command staging, input read, "
                    + "requester storage API, and lease/stop reconciliation");
        }
        this.runtime = runtime;
        this.results = results;
        this.enabled = enabled;
    }

    @Override
    public void advance(AdvanceRequest request, StreamObserver<AdvanceResponse> response) {
        ProviderPeerIdentity peer = AgentPeerInterceptor.PEER.get();
        if (peer == null) {
            response.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        if (!enabled) {
            response.onError(Status.FAILED_PRECONDITION.withDescription("execution disabled").asRuntimeException());
            return;
        }
        try {
            LeaseRuntimeService.Action action = switch (request.getAction()) {
                case EXECUTION_ADVANCE_ACTION_STARTING -> LeaseRuntimeService.Action.STARTING;
                case EXECUTION_ADVANCE_ACTION_RUNNING -> LeaseRuntimeService.Action.RUNNING;
                case EXECUTION_ADVANCE_ACTION_RENEW -> LeaseRuntimeService.Action.RENEW;
                case EXECUTION_ADVANCE_ACTION_FAILED -> LeaseRuntimeService.Action.FAILED;
                default -> throw new LeaseExecutionDeniedException();
            };
            Instant expiry = runtime.advance(peer, UUID.fromString(request.getLeaseId()),
                    UUID.fromString(request.getCommandId()), request.getFencingToken(), action);
            response.onNext(AdvanceResponse.newBuilder().setExpiresAt(Timestamp.newBuilder()
                    .setSeconds(expiry.getEpochSecond()).setNanos(expiry.getNano())).build());
            response.onCompleted();
        } catch (LeaseExecutionDeniedException | IllegalArgumentException exception) {
            response.onError(Status.PERMISSION_DENIED.withDescription("execution transition denied").asRuntimeException());
        } catch (RuntimeException exception) {
            response.onError(Status.INTERNAL.withDescription("execution transition failed").asRuntimeException());
        }
    }

    @Override
    public StreamObserver<UploadResultRequest> uploadResult(StreamObserver<UploadResultResponse> response) {
        ProviderPeerIdentity peer = AgentPeerInterceptor.PEER.get();
        if (peer == null || !enabled) {
            response.onError((peer == null ? Status.UNAUTHENTICATED : Status.FAILED_PRECONDITION)
                    .asRuntimeException());
            return new NoopUpload();
        }
        return new StreamObserver<>() {
            private final ByteArrayOutputStream data = new ByteArrayOutputStream();
            private UUID leaseId;
            private UUID commandId;
            private long token;
            private long total;
            private String digest;
            private boolean closed;

            @Override
            public void onNext(UploadResultRequest chunk) {
                if (closed) return;
                try {
                    UUID incomingLease = UUID.fromString(chunk.getLeaseId());
                    UUID incomingCommand = UUID.fromString(chunk.getCommandId());
                    if (leaseId == null) {
                        leaseId = incomingLease;
                        commandId = incomingCommand;
                        token = chunk.getFencingToken();
                        total = chunk.getTotalBytes();
                        digest = chunk.getSha256();
                    }
                    if (!leaseId.equals(incomingLease) || !commandId.equals(incomingCommand)
                            || token <= 0 || token != chunk.getFencingToken()
                            || total != chunk.getTotalBytes() || total < 1
                            || total > VerifiedResultService.MAX_BYTES || !digest.equals(chunk.getSha256())
                            || !digest.matches("[0-9a-f]{64}") || chunk.getOffset() != data.size()
                            || chunk.getData().isEmpty() || chunk.getData().size() > CHUNK_BYTES
                            || chunk.getData().size() > total - data.size()) {
                        throw new LeaseExecutionDeniedException();
                    }
                    // Revalidate during transfer, not just at completion.
                    runtime.currentLocked(peer, leaseId, commandId, token);
                    chunk.getData().writeTo(data);
                } catch (Exception exception) {
                    closed = true;
                    response.onError(Status.PERMISSION_DENIED.withDescription("result upload denied").asRuntimeException());
                }
            }

            @Override public void onError(Throwable ignored) { closed = true; }

            @Override
            public void onCompleted() {
                if (closed) return;
                try {
                    if (leaseId == null || data.size() != total) {
                        throw new LeaseExecutionDeniedException();
                    }
                    String hash = results.complete(peer, leaseId, commandId, token, data.toByteArray(), digest);
                    response.onNext(UploadResultResponse.newBuilder().setSha256(hash)
                            .setTotalBytes(total).build());
                    response.onCompleted();
                } catch (LeaseExecutionDeniedException | IllegalArgumentException exception) {
                    response.onError(Status.PERMISSION_DENIED.withDescription("result rejected").asRuntimeException());
                } catch (RuntimeException exception) {
                    response.onError(Status.INTERNAL.withDescription("result storage failed").asRuntimeException());
                } finally {
                    closed = true;
                }
            }
        };
    }

    private static final class NoopUpload implements StreamObserver<UploadResultRequest> {
        @Override public void onNext(UploadResultRequest ignored) { }
        @Override public void onError(Throwable ignored) { }
        @Override public void onCompleted() { }
    }
}
