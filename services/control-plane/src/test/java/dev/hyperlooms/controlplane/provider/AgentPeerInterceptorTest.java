package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

class AgentPeerInterceptorTest {

    @Test
    void rejectsCallWithoutTransportTlsSession() {
        @SuppressWarnings("unchecked")
        ServerCall<Object, Object> call = mock(ServerCall.class);
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        when(call.getAttributes()).thenReturn(Attributes.EMPTY);

        new AgentPeerInterceptor().interceptCall(call, new Metadata(), next);

        verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED),
                any(Metadata.class));
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void failsClosedWhenTlsEnabledWithoutCertificatePaths() {
        AgentGrpcServer server = new AgentGrpcServer(9090, "", "", "",
                mock(AgentSessionGrpcService.class), mock(AgentArtifactGrpcService.class),
                mock(AgentExecutionGrpcService.class));
        assertThrows(IllegalStateException.class, server::start);
    }
}
