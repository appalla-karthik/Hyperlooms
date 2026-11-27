package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hyperlooms.controlplane.job.LeaseAcknowledgementService;
import dev.hyperlooms.controlplane.job.LeaseAssignment;
import dev.hyperlooms.controlplane.job.LeaseExecutionCommand;
import dev.hyperlooms.controlplane.job.LeaseExecutionCommandService;
import dev.hyperlooms.controlplane.job.LeaseExecutionDelivery;
import dev.hyperlooms.controlplane.job.LeaseExecutionPlan;
import dev.hyperlooms.controlplane.job.LeaseOfferService;
import dev.hyperlooms.protocol.agent.v1.AgentHeartbeat;
import dev.hyperlooms.protocol.agent.v1.AgentHello;
import dev.hyperlooms.protocol.agent.v1.AgentLeaseReceipt;
import dev.hyperlooms.protocol.agent.v1.AgentCommandStaged;
import dev.hyperlooms.protocol.agent.v1.ConnectRequest;
import dev.hyperlooms.protocol.agent.v1.ConnectResponse;
import dev.hyperlooms.protocol.agent.v1.GpuCapability;
import dev.hyperlooms.protocol.agent.v1.HelloRejectionReason;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

class AgentSessionGrpcServiceTest {

    private final UUID nodeId = UUID.randomUUID();
    private ProviderPeerIdentity peer;
    private ProviderCapabilityService capabilities;
    private LeaseOfferService offers;
    private LeaseAcknowledgementService acknowledgements;
    private LeaseExecutionCommandService commands;
    private AgentSessionGrpcService service;
    private Responses responses;

    @BeforeEach
    void setUp() {
        peer = mock(ProviderPeerIdentity.class);
        when(peer.nodeId()).thenReturn(nodeId);
        capabilities = mock(ProviderCapabilityService.class);
        offers = mock(LeaseOfferService.class);
        acknowledgements = mock(LeaseAcknowledgementService.class);
        commands = mock(LeaseExecutionCommandService.class);
        when(offers.nextIssuedForPeer(peer)).thenReturn(Optional.empty());
        when(commands.nextForPeer(peer)).thenReturn(Optional.empty());
        service = new AgentSessionGrpcService(capabilities, offers, acknowledgements,
                commands, true);
        responses = new Responses();
    }

    @Test
    void requiresAuthenticatedPeer() {
        service.connect(responses);
        assertEquals(Status.Code.UNAUTHENTICATED,
                Status.fromThrowable(responses.error).getCode());
        verify(capabilities, never()).acceptHelloFromAuthenticatedPeer(any(), any());
    }

    @Test
    void rejectsClaimedIdentityMismatchWithoutActivation() {
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(UUID.randomUUID(), 1));
        assertFalse(responses.messages.get(0).getHelloAcknowledgement().getAccepted());
        assertEquals(HelloRejectionReason.HELLO_REJECTION_REASON_IDENTITY_MISMATCH,
                responses.messages.get(0).getHelloAcknowledgement().getRejectionReason());
        assertTrue(responses.completed);
        verify(capabilities, never()).acceptHelloFromAuthenticatedPeer(any(), any());
    }

    @Test
    void rejectsUnsupportedProtocolWithoutActivation() {
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 2));
        assertEquals(HelloRejectionReason.HELLO_REJECTION_REASON_UNSUPPORTED_PROTOCOL,
                responses.messages.get(0).getHelloAcknowledgement().getRejectionReason());
        verify(capabilities, never()).acceptHelloFromAuthenticatedPeer(any(), any());
    }

    @Test
    void helloAndOrderedHeartbeatsAreRecordedButAvailabilityIsNotTrusted() {
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 1));
        assertTrue(responses.messages.get(0).getHelloAcknowledgement().getAccepted());
        verify(capabilities).acceptHelloFromAuthenticatedPeer(any(), any());

        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-1")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1)
                        .setAvailability(dev.hyperlooms.protocol.agent.v1.AvailabilityState
                                .AVAILABILITY_STATE_AVAILABLE))
                .build());
        assertTrue(responses.messages.get(1).hasHeartbeatAcknowledgement());
        verify(capabilities).recordHeartbeatFromAuthenticatedPeer(peer);

        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-duplicate")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1))
                .build());
        assertNotNull(responses.error);
        assertEquals(Status.Code.INVALID_ARGUMENT,
                Status.fromThrowable(responses.error).getCode());
    }

    @Test
    void heartbeatBeforeHelloFailsClosed() {
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-early")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1))
                .build());
        assertEquals(Status.Code.FAILED_PRECONDITION,
                Status.fromThrowable(responses.error).getCode());
        verify(capabilities, never()).recordHeartbeatFromAuthenticatedPeer(any());
    }

    @Test
    void heartbeatOffersIssuedLeaseAndAuthenticatedReceiptIsRecorded() {
        UUID leaseId = UUID.randomUUID();
        LeaseAssignment assignment = new LeaseAssignment(UUID.randomUUID(), UUID.randomUUID(),
                leaseId, nodeId, "GPU-test", 42, Instant.now().plusSeconds(90));
        when(offers.nextIssuedForPeer(peer)).thenReturn(Optional.of(assignment));
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 1));
        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-1")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1)).build());

        assertEquals(leaseId.toString(), responses.messages.get(1).getLeaseOffer().getLeaseId());
        assertEquals(42, responses.messages.get(1).getLeaseOffer().getFencingToken());
        assertTrue(responses.messages.get(2).hasHeartbeatAcknowledgement());

        requests.onNext(ConnectRequest.newBuilder().setMessageId("receipt-1")
                .setLeaseReceipt(AgentLeaseReceipt.newBuilder().setNodeId(nodeId.toString())
                        .setLeaseId(leaseId.toString()).setFencingToken(42)).build());
        verify(acknowledgements).acknowledge(peer, leaseId, 42);
        assertEquals("receipt-1", responses.messages.get(3)
                .getLeaseReceiptAcknowledgement().getRequestMessageId());
    }

    @Test
    void wrongNodeReceiptFailsWithoutRecording() {
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 1));
        requests.onNext(ConnectRequest.newBuilder().setMessageId("receipt-bad")
                .setLeaseReceipt(AgentLeaseReceipt.newBuilder()
                        .setNodeId(UUID.randomUUID().toString())
                        .setLeaseId(UUID.randomUUID().toString()).setFencingToken(1)).build());
        assertEquals(Status.Code.PERMISSION_DENIED,
                Status.fromThrowable(responses.error).getCode());
        verify(acknowledgements, never()).acknowledge(any(), any(), anyLong());
    }

    @Test
    void optedInHeartbeatDeliversPinnedCommandAndRecordsStagingReceipt() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(90);
        String digest = "sha256:" + "a".repeat(64);
        LeaseExecutionPlan plan = new LeaseExecutionPlan(jobId, attemptId, leaseId, nodeId,
                "GPU-test", 42, expires, "ghcr.io/example/recipe", digest, 60,
                "jobs/" + jobId + "/verified/snapshot", "b".repeat(64), 1, 32);
        LeaseExecutionCommand command = new LeaseExecutionCommand(commandId, leaseId, nodeId,
                "GPU-test", 42, plan.imageRepository(), digest, 60,
                plan.verifiedInputPrefix(), plan.inputManifestSha256(), 1, 32, Instant.now());
        when(commands.nextForPeer(peer)).thenReturn(Optional.of(new LeaseExecutionDelivery(plan, command)));
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 1));
        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-1")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1)).build());
        assertEquals(commandId.toString(), responses.messages.get(1)
                .getExecutionCommand().getCommandId());
        assertEquals("GPU-test", responses.messages.get(1).getExecutionCommand().getGpuUuid());
        assertTrue(responses.messages.get(2).hasHeartbeatAcknowledgement());

        requests.onNext(ConnectRequest.newBuilder().setMessageId("stage-1")
                .setCommandStaged(AgentCommandStaged.newBuilder().setNodeId(nodeId.toString())
                        .setLeaseId(leaseId.toString()).setCommandId(commandId.toString())
                        .setFencingToken(42)).build());
        verify(commands).acknowledgeStaged(peer, leaseId, commandId, 42);
        assertEquals("stage-1", responses.messages.get(3)
                .getCommandStagedAcknowledgement().getRequestMessageId());
    }

    @Test
    void commandStagingIsDefaultOffWhenServerGateIsDisabled() {
        service = new AgentSessionGrpcService(capabilities, offers, acknowledgements,
                commands, false);
        StreamObserver<ConnectRequest> requests = connect();
        requests.onNext(hello(nodeId, 1));
        requests.onNext(ConnectRequest.newBuilder().setMessageId("heartbeat-1")
                .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                        .setSequence(1)).build());
        verify(commands, never()).nextForPeer(any());
        assertTrue(responses.messages.get(1).hasHeartbeatAcknowledgement());
    }

    private StreamObserver<ConnectRequest> connect() {
        Context context = Context.current().withValue(AgentPeerInterceptor.PEER, peer);
        Context previous = context.attach();
        try {
            return service.connect(responses);
        } finally {
            context.detach(previous);
        }
    }

    private ConnectRequest hello(UUID claimedNode, int version) {
        return ConnectRequest.newBuilder().setMessageId("hello-1")
                .setHello(AgentHello.newBuilder().setNodeId(claimedNode.toString())
                        .setProtocolVersion(version).setAgentVersion("test-agent")
                        .setSupportsCommandStaging(true)
                        .addGpus(GpuCapability.newBuilder().setGpuUuid("GPU-test")
                                .setModel("Test GPU").setVramBytes(1024)
                                .setDriverVersion("test").setCudaVersion("test")))
                .build();
    }

    private static final class Responses implements StreamObserver<ConnectResponse> {
        private final List<ConnectResponse> messages = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(ConnectResponse response) {
            messages.add(response);
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
