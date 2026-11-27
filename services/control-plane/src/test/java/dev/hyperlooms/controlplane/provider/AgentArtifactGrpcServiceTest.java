package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.hyperlooms.controlplane.job.LeaseExecutionDeniedException;
import dev.hyperlooms.controlplane.job.ScopedInputReadService;
import dev.hyperlooms.protocol.agent.v1.ReadInputResponse;
import dev.hyperlooms.protocol.agent.v1.ReadInputRequest;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

class AgentArtifactGrpcServiceTest {

    private final UUID nodeId = UUID.randomUUID();
    private final UUID leaseId = UUID.randomUUID();
    private final UUID commandId = UUID.randomUUID();
    private final ProviderPeerIdentity peer = mock(ProviderPeerIdentity.class);
    private final ScopedInputReadService reader = mock(ScopedInputReadService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ScopedInputReadService> readers = mock(ObjectProvider.class);

    @Test
    void rejectsUnauthenticatedAndDisabledRequests() throws Exception {
        Responses response = new Responses();
        new AgentArtifactGrpcService(readers, true).readInput(request(), response);
        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(response.error).getCode());
        response = new Responses();
        Responses disabled = response;
        Context.current().withValue(AgentPeerInterceptor.PEER, peer).call(() -> {
            new AgentArtifactGrpcService(readers, false).readInput(request(), disabled);
            return null;
        });
        assertEquals(Status.Code.FAILED_PRECONDITION,
                Status.fromThrowable(response.error).getCode());
        verify(readers, never()).getIfAvailable();
    }

    @Test
    void streamsBoundedVerifiedFileAndRechecksBeforeEachChunk() throws Exception {
        when(readers.getIfAvailable()).thenReturn(reader);
        byte[] data = new byte[70_000];
        java.util.Arrays.fill(data, (byte) 'x');
        when(reader.readFile(eq(peer), eq(leaseId), eq(commandId), eq(42L),
                eq("request.json"))).thenReturn(data);
        Responses response = new Responses();
        Context.current().withValue(AgentPeerInterceptor.PEER, peer).call(() -> {
            new AgentArtifactGrpcService(readers, true).readInput(request(), response);
            return null;
        });
        assertTrue(response.completed);
        assertEquals(3, response.chunks.size());
        assertEquals(0, response.chunks.get(0).getOffset());
        assertEquals(32_768, response.chunks.get(1).getOffset());
        assertEquals(65_536, response.chunks.get(2).getOffset());
        assertEquals(70_000, response.chunks.get(2).getTotalBytes());
        assertEquals(64, response.chunks.get(0).getSha256().length());
        verify(reader, org.mockito.Mockito.times(3)).requireCurrent(peer, leaseId, commandId, 42);
    }

    @Test
    void deniesOversizedInputBeforeSendingAndStopsOnRevocation() throws Exception {
        when(readers.getIfAvailable()).thenReturn(reader);
        when(reader.readFile(eq(peer), eq(leaseId), eq(commandId), eq(42L),
                eq("request.json"))).thenReturn(new byte[1024 * 1024 + 1]);
        Responses oversized = new Responses();
        Context.current().withValue(AgentPeerInterceptor.PEER, peer).call(() -> {
            new AgentArtifactGrpcService(readers, true).readInput(request(), oversized);
            return null;
        });
        assertTrue(oversized.chunks.isEmpty());
        assertEquals(Status.Code.PERMISSION_DENIED,
                Status.fromThrowable(oversized.error).getCode());

        when(reader.readFile(eq(peer), eq(leaseId), eq(commandId), eq(42L),
                eq("request.json"))).thenReturn(new byte[40_000]);
        org.mockito.Mockito.doThrow(new LeaseExecutionDeniedException())
                .when(reader).requireCurrent(peer, leaseId, commandId, 42);
        Responses revoked = new Responses();
        Context.current().withValue(AgentPeerInterceptor.PEER, peer).call(() -> {
            new AgentArtifactGrpcService(readers, true).readInput(request(), revoked);
            return null;
        });
        assertTrue(revoked.chunks.isEmpty());
        assertEquals(Status.Code.PERMISSION_DENIED,
                Status.fromThrowable(revoked.error).getCode());
        assertNotNull(revoked.error);
    }

    private ReadInputRequest request() {
        return ReadInputRequest.newBuilder().setLeaseId(leaseId.toString())
                .setCommandId(commandId.toString()).setFencingToken(42).build();
    }

    private static final class Responses implements StreamObserver<ReadInputResponse> {
        private final List<ReadInputResponse> chunks = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override public void onNext(ReadInputResponse value) { chunks.add(value); }
        @Override public void onError(Throwable failure) { error = failure; }
        @Override public void onCompleted() { completed = true; }
    }
}
