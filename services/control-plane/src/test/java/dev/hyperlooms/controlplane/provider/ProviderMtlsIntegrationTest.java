package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.hyperlooms.protocol.agent.v1.AgentHeartbeat;
import dev.hyperlooms.protocol.agent.v1.AgentHello;
import dev.hyperlooms.protocol.agent.v1.AgentSessionServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.ConnectRequest;
import dev.hyperlooms.protocol.agent.v1.ConnectResponse;
import dev.hyperlooms.protocol.agent.v1.GpuCapability;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;

/** Real TLS + PostgreSQL boundary; GPU discovery is deliberately not simulated here. */
@Tag("integration")
@SpringBootTest
class ProviderMtlsIntegrationTest {

    @TempDir
    Path temporary;

    @Autowired
    private ProviderEnrollmentService enrollment;

    @Autowired
    private ProviderIdentityService identities;

    @Autowired
    private AgentSessionGrpcService sessions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> inviteIds = new ArrayList<>();

    @BeforeEach
    void requireOpenSslOnWindows() {
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            String openssl = System.getenv("HYPERLOOMS_TEST_OPENSSL");
            Assumptions.assumeTrue(openssl != null && Files.isExecutable(Path.of(openssl)),
                    "Set HYPERLOOMS_TEST_OPENSSL to an OpenSSL executable on Windows");
        }
    }

    @AfterEach
    void removeTestRecords() {
        for (UUID inviteId : inviteIds) {
            List<UUID> nodeIds = jdbc.queryForList(
                    "SELECT node_id FROM provider_enrollment_invites WHERE id = ? AND node_id IS NOT NULL",
                    UUID.class, inviteId);
            for (UUID nodeId : nodeIds) {
                jdbc.update("DELETE FROM provider_gpus WHERE node_id = ?", nodeId);
                jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", nodeId);
                jdbc.update("DELETE FROM provider_certificates WHERE node_id = ?", nodeId);
            }
            jdbc.update("DELETE FROM provider_enrollment_invites WHERE id = ?", inviteId);
            for (UUID nodeId : nodeIds) {
                jdbc.update("DELETE FROM provider_nodes WHERE id = ?", nodeId);
            }
        }
    }

    @Test
    void boundCertificateHelloAndHeartbeatReachTheDatabase() throws Exception {
        UUID nodeId = pendingNode();
        TestCa ca = new TestCa(temporary);
        SignedCertificate serverCertificate = ca.issue("server", "DNS:localhost", "serverAuth", 11);
        SignedCertificate clientCertificate = ca.issue("client", providerSan(nodeId), "clientAuth", 12);
        X509Certificate leaf = new OperatorCertificateVerifier(ca.certificate().toString())
                .verifyClientCertificate(clientCertificate.certificate());
        identities.bindExpectedCertificate(nodeId, leaf);

        try (TestServer server = new TestServer(serverCertificate, ca.certificate(), sessions);
                TestSession client = server.connect(clientCertificate, ca.certificate())) {
            client.send(hello(nodeId));
            ConnectResponse helloAck = assertInstanceOf(ConnectResponse.class, client.receive());
            assertTrue(helloAck.getHelloAcknowledgement().getAccepted());
            assertEquals("VERIFIED/OFFLINE", jdbc.queryForObject("""
                    SELECT enrollment_state || '/' || availability_state
                    FROM provider_nodes WHERE id = ?
                    """, String.class, nodeId));
            assertEquals("REPORTED", jdbc.queryForObject("""
                    SELECT verification_state FROM provider_gpus
                    WHERE node_id = ? AND gpu_uuid = 'GPU-test'
                    """, String.class, nodeId));

            jdbc.update("UPDATE provider_nodes SET last_seen_at = NULL WHERE id = ?", nodeId);
            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-1")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(1))
                    .build());
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .hasHeartbeatAcknowledgement());
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM provider_nodes
                    WHERE id = ? AND last_seen_at IS NOT NULL AND availability_state = 'OFFLINE'
                    """, Long.class, nodeId));

            SignedCertificate replacement = ca.issue("replacement", providerSan(nodeId),
                    "clientAuth", 13);
            identities.stageReplacementCertificate(nodeId,
                    new OperatorCertificateVerifier(ca.certificate().toString())
                            .verifyClientCertificate(replacement.certificate()));
            try (TestSession rotated = server.connect(replacement, ca.certificate())) {
                rotated.send(hello(nodeId));
                assertTrue(assertInstanceOf(ConnectResponse.class, rotated.receive())
                        .getHelloAcknowledgement().getAccepted());
            }
            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-old-cert")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(2))
                    .build());
            assertEquals(Status.Code.PERMISSION_DENIED,
                    Status.fromThrowable(assertInstanceOf(Throwable.class, client.receive()))
                            .getCode());
        }
    }

    @Test
    void trustedButUnboundCertificateIsDenied() throws Exception {
        UUID nodeId = pendingNode();
        TestCa ca = new TestCa(temporary);
        SignedCertificate serverCertificate = ca.issue("server", "DNS:localhost", "serverAuth", 21);
        SignedCertificate unbound = ca.issue("unbound", providerSan(nodeId), "clientAuth", 22);

        try (TestServer server = new TestServer(serverCertificate, ca.certificate(), sessions);
                TestSession client = server.connect(unbound, ca.certificate())) {
            client.send(hello(nodeId));
            Throwable failure = assertInstanceOf(Throwable.class, client.receive());
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(failure).getCode());
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT enrollment_state FROM provider_nodes WHERE id = ?", String.class, nodeId));
        }
    }

    @Test
    void tlsHandshakeWithoutClientCertificateIsRejected() throws Exception {
        UUID nodeId = pendingNode();
        TestCa ca = new TestCa(temporary);
        SignedCertificate serverCertificate = ca.issue("server", "DNS:localhost", "serverAuth", 31);

        try (TestServer server = new TestServer(serverCertificate, ca.certificate(), sessions);
                TestSession client = server.connectWithoutCertificate(ca.certificate())) {
            client.send(hello(nodeId));
            assertInstanceOf(Throwable.class, client.receive());
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT enrollment_state FROM provider_nodes WHERE id = ?", String.class, nodeId));
        }
    }

    private UUID pendingNode() {
        ProviderEnrollmentInvite invite = enrollment.issueInvite(
                "mtls-integration:" + UUID.randomUUID(), Duration.ofMinutes(30));
        inviteIds.add(invite.inviteId());
        return enrollment.redeemInvite(invite.token()).nodeId();
    }

    private static String providerSan(UUID nodeId) {
        return "URI:urn:hyperlooms:provider:" + nodeId;
    }

    private static ConnectRequest hello(UUID nodeId) {
        return ConnectRequest.newBuilder().setMessageId("hello-1")
                .setHello(AgentHello.newBuilder().setNodeId(nodeId.toString())
                        .setProtocolVersion(1).setAgentVersion("mtls-integration")
                        .addGpus(GpuCapability.newBuilder().setGpuUuid("GPU-test")
                                .setModel("Test GPU").setVramBytes(8589934592L)
                                .setDriverVersion("test-driver").setCudaVersion("test-cuda")))
                .build();
    }

    private record SignedCertificate(Path certificate, Path key) { }

    private static final class TestCa {
        private final Path directory;
        private final Path certificate;
        private final Path key;

        private TestCa(Path directory) throws Exception {
            this.directory = directory;
            certificate = directory.resolve("ca.pem");
            key = directory.resolve("ca.key");
            openssl(directory, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", key.toString(), "-out", certificate.toString(), "-days", "2",
                    "-subj", "/CN=Hyperlooms integration CA",
                    "-addext", "basicConstraints=critical,CA:TRUE",
                    "-addext", "keyUsage=critical,keyCertSign,cRLSign");
        }

        private Path certificate() {
            return certificate;
        }

        private SignedCertificate issue(String name, String san, String usage, int serial)
                throws Exception {
            Path clientKey = directory.resolve(name + ".key");
            Path csr = directory.resolve(name + ".csr");
            Path signed = directory.resolve(name + ".pem");
            Path extensions = directory.resolve(name + ".ext");
            Files.writeString(extensions, "basicConstraints=critical,CA:FALSE\n"
                    + "keyUsage=critical,digitalSignature,keyEncipherment\n"
                    + "extendedKeyUsage=" + usage + "\nsubjectAltName=" + san + "\n");
            openssl(directory, "req", "-new", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", clientKey.toString(), "-out", csr.toString(),
                    "-subj", "/CN=" + name);
            openssl(directory, "x509", "-req", "-in", csr.toString(),
                    "-CA", certificate.toString(), "-CAkey", key.toString(),
                    "-out", signed.toString(), "-days", "2", "-set_serial",
                    Integer.toString(serial), "-extfile", extensions.toString());
            return new SignedCertificate(signed, clientKey);
        }

        private static void openssl(Path directory, String... args) throws Exception {
            List<String> command = new ArrayList<>();
            String openssl = System.getenv("HYPERLOOMS_TEST_OPENSSL");
            command.add(openssl == null || openssl.isBlank() ? "openssl" : openssl);
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OpenSSL fixture generation timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("OpenSSL fixture generation failed: "
                        + new String(process.getInputStream().readAllBytes()));
            }
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final Server server;

        private TestServer(SignedCertificate identity, Path ca, AgentSessionGrpcService service)
                throws IOException {
            ServerCredentials credentials = TlsServerCredentials.newBuilder()
                    .keyManager(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(ca.toFile())
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
            server = Grpc.newServerBuilderForPort(0, credentials)
                    .addService(ServerInterceptors.intercept(service, new AgentPeerInterceptor()))
                    .build().start();
        }

        private TestSession connect(SignedCertificate identity, Path ca) throws IOException {
            ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(),
                    TlsChannelCredentials.newBuilder().trustManager(ca.toFile())
                            .keyManager(identity.certificate().toFile(), identity.key().toFile())
                            .build()).build();
            return new TestSession(channel);
        }

        private TestSession connectWithoutCertificate(Path ca) throws IOException {
            ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(),
                    TlsChannelCredentials.newBuilder().trustManager(ca.toFile()).build()).build();
            return new TestSession(channel);
        }

        @Override
        public void close() {
            server.shutdownNow();
        }
    }

    private static final class TestSession implements AutoCloseable {
        private final ManagedChannel channel;
        private final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
        private final StreamObserver<ConnectRequest> requests;

        private TestSession(ManagedChannel channel) {
            this.channel = channel;
            requests = AgentSessionServiceGrpc.newStub(channel).connect(new StreamObserver<>() {
                @Override public void onNext(ConnectResponse response) { responses.add(response); }
                @Override public void onError(Throwable failure) { responses.add(failure); }
                @Override public void onCompleted() { responses.add(Boolean.TRUE); }
            });
        }

        private void send(ConnectRequest request) {
            requests.onNext(request);
        }

        private Object receive() throws InterruptedException {
            Object response = responses.poll(10, TimeUnit.SECONDS);
            if (response == null) {
                throw new AssertionError("timed out waiting for agent session response");
            }
            return response;
        }

        @Override
        public void close() {
            channel.shutdownNow();
        }
    }
}
