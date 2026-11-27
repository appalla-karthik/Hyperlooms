package dev.hyperlooms.controlplane.provider;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.ServerInterceptors;

/** Optional, fail-closed agent listener. REST and agent TLS use separate ports. */
@Component
@ConditionalOnProperty(name = "hyperlooms.agent-grpc.enabled", havingValue = "true")
public final class AgentGrpcServer implements SmartLifecycle {

    private final int port;
    private final String certificate;
    private final String privateKey;
    private final String clientCa;
    private final AgentSessionGrpcService sessions;
    private final AgentArtifactGrpcService artifacts;
    private Server server;

    public AgentGrpcServer(
            @Value("${hyperlooms.agent-grpc.port:9090}") int port,
            @Value("${hyperlooms.agent-grpc.certificate:}") String certificate,
            @Value("${hyperlooms.agent-grpc.private-key:}") String privateKey,
            @Value("${hyperlooms.agent-grpc.client-ca:}") String clientCa,
            AgentSessionGrpcService sessions, AgentArtifactGrpcService artifacts) {
        this.port = port;
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.clientCa = clientCa;
        this.sessions = sessions;
        this.artifacts = artifacts;
    }

    @Override
    public void start() {
        if (port < 1 || port > 65535 || certificate.isBlank() || privateKey.isBlank()
                || clientCa.isBlank()) {
            throw new IllegalStateException("agent gRPC requires a valid port, server certificate, "
                    + "private key, and client CA");
        }
        try {
            ServerCredentials credentials = TlsServerCredentials.newBuilder()
                    .keyManager(new File(certificate), new File(privateKey))
                    .trustManager(new File(clientCa))
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
            server = Grpc.newServerBuilderForPort(port, credentials)
                    .maxInboundMessageSize(64 * 1024)
                    .addService(ServerInterceptors.intercept(sessions, new AgentPeerInterceptor()))
                    .addService(ServerInterceptors.intercept(artifacts, new AgentPeerInterceptor()))
                    .build()
                    .start();
        } catch (IOException exception) {
            throw new IllegalStateException("could not start agent gRPC listener", exception);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            } finally {
                server = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
