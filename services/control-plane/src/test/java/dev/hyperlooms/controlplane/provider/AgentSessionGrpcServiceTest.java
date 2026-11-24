package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hyperlooms.protocol.agent.v1.AgentHeartbeat;
import dev.hyperlooms.protocol.agent.v1.AgentHello;
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
    private AgentSessionGrpcService service;
    private Responses responses;

    @BeforeEach
    void setUp() {
        peer = mock(ProviderPeerIdentity.class);
        org.mockito.Mockito.when(peer.nodeId()).thenReturn(nodeId);
        capabilities = mock(ProviderCapabilityService.class);
        service = new AgentSessionGrpcService(capabilities);
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
