package dev.hyperlooms.controlplane.provider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.protobuf.ByteString;

import dev.hyperlooms.controlplane.job.LeaseExecutionDeniedException;
import dev.hyperlooms.controlplane.job.ScopedInputReadService;
import dev.hyperlooms.protocol.agent.v1.AgentArtifactServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.ReadInputResponse;
import dev.hyperlooms.protocol.agent.v1.ReadInputRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** A default-off mTLS read of one fixed, bounded recipe input; never S3 credentials. */
@Component
public final class AgentArtifactGrpcService extends AgentArtifactServiceGrpc.AgentArtifactServiceImplBase {

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int CHUNK_BYTES = 32 * 1024;

    private final ObjectProvider<ScopedInputReadService> readers;
    private final boolean enabled;

    public AgentArtifactGrpcService(ObjectProvider<ScopedInputReadService> readers,
            @Value("${hyperlooms.agent.input-read-enabled:false}") boolean enabled) {
        this.readers = readers;
        this.enabled = enabled;
    }

    @Override
    public void readInput(ReadInputRequest request, StreamObserver<ReadInputResponse> responses) {
        ProviderPeerIdentity peer = AgentPeerInterceptor.PEER.get();
        if (peer == null) {
            responses.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        if (!enabled) {
            responses.onError(Status.FAILED_PRECONDITION.withDescription(
                    "input transfer is disabled").asRuntimeException());
            return;
        }
        ScopedInputReadService reader = readers.getIfAvailable();
        if (reader == null) {
            responses.onError(Status.FAILED_PRECONDITION.withDescription(
                    "private input storage is unavailable").asRuntimeException());
            return;
        }
        try {
            if (request.getFencingToken() <= 0) {
                throw new LeaseExecutionDeniedException();
            }
            UUID leaseId = UUID.fromString(request.getLeaseId());
            UUID commandId = UUID.fromString(request.getCommandId());
            byte[] input = reader.readFile(peer, leaseId, commandId,
                    request.getFencingToken(), "request.json");
            if (input.length == 0 || input.length > MAX_REQUEST_BYTES) {
                throw new LeaseExecutionDeniedException();
            }
            String digest = sha256(input);
            for (int offset = 0; offset < input.length; offset += CHUNK_BYTES) {
                reader.requireCurrent(peer, leaseId, commandId, request.getFencingToken());
                int size = Math.min(CHUNK_BYTES, input.length - offset);
                responses.onNext(ReadInputResponse.newBuilder()
                        .setOffset(offset)
                        .setData(ByteString.copyFrom(input, offset, size))
                        .setTotalBytes(input.length)
                        .setSha256(digest).build());
            }
            responses.onCompleted();
        } catch (LeaseExecutionDeniedException | IllegalArgumentException exception) {
            responses.onError(Status.PERMISSION_DENIED.withDescription(
                    "input transfer denied").asRuntimeException());
        } catch (RuntimeException exception) {
            responses.onError(Status.INTERNAL.withDescription(
                    "input transfer failed").asRuntimeException());
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
