package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.hyperlooms.controlplane.job.InputObjectMissingException;
import dev.hyperlooms.controlplane.job.InputObjectStore;
import dev.hyperlooms.controlplane.job.LeaseAssignment;
import dev.hyperlooms.controlplane.job.LeaseExecutionPlanService;
import dev.hyperlooms.controlplane.job.ScopedInputReadService;
import dev.hyperlooms.controlplane.job.SchedulerClaimService;
import dev.hyperlooms.protocol.agent.v1.AgentArtifactServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.AgentCommandStaged;
import dev.hyperlooms.protocol.agent.v1.AgentHeartbeat;
import dev.hyperlooms.protocol.agent.v1.AgentHello;
import dev.hyperlooms.protocol.agent.v1.AgentLeaseReceipt;
import dev.hyperlooms.protocol.agent.v1.AgentSessionServiceGrpc;
import dev.hyperlooms.protocol.agent.v1.ConnectRequest;
import dev.hyperlooms.protocol.agent.v1.ConnectResponse;
import dev.hyperlooms.protocol.agent.v1.GpuCapability;
import dev.hyperlooms.protocol.agent.v1.ReadInputRequest;
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
@SpringBootTest(properties = "hyperlooms.agent.command-staging-enabled=true")
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

    @Autowired
    private SchedulerClaimService claims;

    @Autowired
    private LeaseExecutionPlanService executionPlans;

    private final List<UUID> inviteIds = new ArrayList<>();
    private final List<UUID> jobIds = new ArrayList<>();
    private final List<UUID> recipeIds = new ArrayList<>();

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
        for (UUID jobId : jobIds) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", jobId);
            jdbc.update("DELETE FROM lease_execution_command_staging WHERE lease_id IN "
                    + "(SELECT c.lease_id FROM lease_execution_commands c "
                    + "JOIN gpu_leases l ON l.id = c.lease_id "
                    + "JOIN job_attempts a ON a.id = l.attempt_id WHERE a.job_id = ?)", jobId);
            jdbc.update("DELETE FROM lease_execution_commands WHERE lease_id IN "
                    + "(SELECT l.id FROM gpu_leases l "
                    + "JOIN job_attempts a ON a.id = l.attempt_id WHERE a.job_id = ?)", jobId);
            jdbc.update("DELETE FROM gpu_leases WHERE attempt_id IN "
                    + "(SELECT id FROM job_attempts WHERE job_id = ?)", jobId);
            jdbc.update("DELETE FROM job_attempts WHERE job_id = ?", jobId);
            jdbc.update("DELETE FROM jobs WHERE id = ?", jobId);
        }
        for (UUID recipeId : recipeIds) {
            jdbc.update("DELETE FROM workload_recipes WHERE id = ?", recipeId);
        }
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
    void preparedCommandStagesAcrossRealMtlsAndRecordsReceipt() throws Exception {
        UUID nodeId = pendingNode();
        TestCa ca = new TestCa(temporary);
        SignedCertificate serverCertificate = ca.issue("server", "DNS:localhost", "serverAuth", 51);
        SignedCertificate clientCertificate = ca.issue("client", providerSan(nodeId), "clientAuth", 52);
        identities.bindExpectedCertificate(nodeId,
                new OperatorCertificateVerifier(ca.certificate().toString())
                        .verifyClientCertificate(clientCertificate.certificate()));

        Map<String, byte[]> content = new HashMap<>();
        InputObjectStore objects = new InputObjectStore() {
            @Override public InputStream get(String key) {
                byte[] value = content.get(key);
                if (value == null) {
                    throw new InputObjectMissingException();
                }
                return new ByteArrayInputStream(value);
            }
            @Override public void put(String key, byte[] bytes) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean putIfAbsent(String key, byte[] bytes) {
                throw new UnsupportedOperationException();
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ScopedInputReadService> readers = mock(ObjectProvider.class);
        when(readers.getIfAvailable()).thenReturn(new ScopedInputReadService(jdbc, executionPlans, objects));
        AgentArtifactGrpcService artifacts = new AgentArtifactGrpcService(readers, true);

        try (TestServer server = new TestServer(serverCertificate, ca.certificate(), sessions, artifacts);
                TestSession client = server.connect(clientCertificate, ca.certificate())) {
            client.send(hello(nodeId, true));
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .getHelloAcknowledgement().getAccepted());
            jdbc.update("UPDATE provider_gpus SET verification_state = 'VERIFIED', "
                    + "verified_at = CURRENT_TIMESTAMP WHERE node_id = ? AND gpu_uuid = 'GPU-test'", nodeId);
            jdbc.update("UPDATE provider_nodes SET availability_state = 'AVAILABLE', "
                    + "last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", nodeId);

            UUID recipeId = UUID.randomUUID();
            recipeIds.add(recipeId);
            String digest = "a".repeat(64);
            jdbc.update("""
                    INSERT INTO workload_recipes
                        (id, name, image_repository, image_digest, max_runtime_seconds,
                         min_vram_bytes, state, approved_at)
                    VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?, 60,
                        1073741824, 'APPROVED', CURRENT_TIMESTAMP)
                    """, recipeId, "mtls-stage-test-" + recipeId, "sha256:" + digest);
            UUID jobId = UUID.randomUUID();
            jobIds.add(jobId);
            byte[] input = "{\"small\":\"mtls input\"}".getBytes(StandardCharsets.US_ASCII);
            byte[] manifest = ("{\"files\":[{\"path\":\"request.json\",\"sha256\":\""
                    + sha256(input) + "\",\"sizeBytes\":" + input.length
                    + "}],\"schemaVersion\":1}").getBytes(StandardCharsets.US_ASCII);
            String prefix = "jobs/" + jobId + "/verified/snapshot/";
            content.put(prefix + "manifest.json", manifest);
            content.put(prefix + "files/request.json", input);
            jdbc.update("""
                    INSERT INTO jobs
                        (id, requester_subject, recipe_id, state, idempotency_key,
                         request_sha256, request_version, input_manifest_sha256,
                         verified_input_manifest_sha256, input_artifact_prefix,
                         input_verified_at, input_file_count, input_total_bytes)
                    VALUES (?, ?, ?, 'QUEUED', ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, 1, ?)
                    """, jobId, "mtls-stage-test:" + jobId, recipeId, jobId.toString(), digest,
                    sha256(manifest), sha256(manifest), prefix, input.length);
            LeaseAssignment assignment = claims.claimNext().orElseThrow();
            assertEquals(jobId, assignment.jobId());

            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-stage-offer")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(1)).build());
            assertEquals(assignment.leaseId().toString(),
                    assertInstanceOf(ConnectResponse.class, client.receive())
                            .getLeaseOffer().getLeaseId());
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .hasHeartbeatAcknowledgement());

            client.send(ConnectRequest.newBuilder().setMessageId("receipt-stage-offer")
                    .setLeaseReceipt(AgentLeaseReceipt.newBuilder().setNodeId(nodeId.toString())
                            .setLeaseId(assignment.leaseId().toString())
                            .setFencingToken(assignment.fencingToken())).build());
            assertEquals("receipt-stage-offer", assertInstanceOf(ConnectResponse.class, client.receive())
                    .getLeaseReceiptAcknowledgement().getRequestMessageId());

            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-stage-command")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(2)).build());
            var command = assertInstanceOf(ConnectResponse.class, client.receive())
                    .getExecutionCommand();
            assertEquals(assignment.leaseId().toString(), command.getLeaseId());
            assertEquals(assignment.fencingToken(), command.getFencingToken());
            assertEquals("GPU-test", command.getGpuUuid());
            assertEquals("sha256:" + digest, command.getImageDigest());
            assertEquals(prefix, command.getVerifiedInputPrefix());
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .hasHeartbeatAcknowledgement());

            client.send(ConnectRequest.newBuilder().setMessageId("stage-receipt")
                    .setCommandStaged(AgentCommandStaged.newBuilder().setNodeId(nodeId.toString())
                            .setLeaseId(assignment.leaseId().toString())
                            .setCommandId(command.getCommandId())
                            .setFencingToken(assignment.fencingToken())).build());
            assertEquals("stage-receipt", assertInstanceOf(ConnectResponse.class, client.receive())
                    .getCommandStagedAcknowledgement().getRequestMessageId());
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM lease_execution_command_staging
                    WHERE command_id = ? AND node_id = ? AND fencing_token = ?
                    """, Long.class, UUID.fromString(command.getCommandId()), nodeId,
                    assignment.fencingToken()));

            ReadInputRequest read = ReadInputRequest.newBuilder()
                    .setLeaseId(assignment.leaseId().toString())
                    .setCommandId(command.getCommandId())
                    .setFencingToken(assignment.fencingToken()).build();
            var chunks = AgentArtifactServiceGrpc.newBlockingStub(client.channel).readInput(read);
            byte[] received = chunks.next().getData().toByteArray();
            assertEquals(new String(input, StandardCharsets.US_ASCII),
                    new String(received, StandardCharsets.US_ASCII));
            assertTrue(!chunks.hasNext());
            ReadInputRequest wrongToken = read.toBuilder().setFencingToken(assignment.fencingToken() + 1).build();
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(assertThrows(
                    io.grpc.StatusRuntimeException.class, () -> AgentArtifactServiceGrpc
                            .newBlockingStub(client.channel).readInput(wrongToken).next())).getCode());

            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-after-stage")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(3)).build());
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .hasHeartbeatAcknowledgement());
            jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP "
                    + "WHERE node_id = ?", nodeId);
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(assertThrows(
                    io.grpc.StatusRuntimeException.class, () -> AgentArtifactServiceGrpc
                            .newBlockingStub(client.channel).readInput(read).next())).getCode());
        }
    }

    @Test
    void issuedLeaseOfferAndReceiptCrossRealMtlsAndReachDatabase() throws Exception {
        UUID nodeId = pendingNode();
        TestCa ca = new TestCa(temporary);
        SignedCertificate serverCertificate = ca.issue("server", "DNS:localhost", "serverAuth", 41);
        SignedCertificate clientCertificate = ca.issue("client", providerSan(nodeId), "clientAuth", 42);
        identities.bindExpectedCertificate(nodeId,
                new OperatorCertificateVerifier(ca.certificate().toString())
                        .verifyClientCertificate(clientCertificate.certificate()));

        try (TestServer server = new TestServer(serverCertificate, ca.certificate(), sessions);
                TestSession client = server.connect(clientCertificate, ca.certificate())) {
            client.send(hello(nodeId));
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .getHelloAcknowledgement().getAccepted());
            jdbc.update("UPDATE provider_gpus SET verification_state = 'VERIFIED', "
                    + "verified_at = CURRENT_TIMESTAMP WHERE node_id = ? AND gpu_uuid = 'GPU-test'", nodeId);
            jdbc.update("UPDATE provider_nodes SET availability_state = 'AVAILABLE', "
                    + "last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", nodeId);

            UUID recipeId = UUID.randomUUID();
            recipeIds.add(recipeId);
            jdbc.update("""
                    INSERT INTO workload_recipes
                        (id, name, image_repository, image_digest, max_runtime_seconds,
                         min_vram_bytes, state, approved_at)
                    VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?, 60,
                        1073741824, 'APPROVED', CURRENT_TIMESTAMP)
                    """, recipeId, "mtls-offer-test-" + recipeId, "sha256:" + "a".repeat(64));
            UUID jobId = UUID.randomUUID();
            jobIds.add(jobId);
            jdbc.update("""
                    INSERT INTO jobs
                        (id, requester_subject, recipe_id, state, idempotency_key, request_sha256)
                    VALUES (?, ?, ?, 'QUEUED', ?, ?)
                    """, jobId, "mtls-offer-test:" + jobId, recipeId, jobId.toString(), "a".repeat(64));
            LeaseAssignment assignment = claims.claimNext().orElseThrow();
            assertEquals(jobId, assignment.jobId());

            client.send(ConnectRequest.newBuilder().setMessageId("heartbeat-offer")
                    .setHeartbeat(AgentHeartbeat.newBuilder().setNodeId(nodeId.toString())
                            .setSequence(1)).build());
            ConnectResponse offer = assertInstanceOf(ConnectResponse.class, client.receive());
            assertEquals(assignment.leaseId().toString(), offer.getLeaseOffer().getLeaseId());
            assertEquals(assignment.fencingToken(), offer.getLeaseOffer().getFencingToken());
            assertTrue(assertInstanceOf(ConnectResponse.class, client.receive())
                    .hasHeartbeatAcknowledgement());

            client.send(ConnectRequest.newBuilder().setMessageId("receipt-offer")
                    .setLeaseReceipt(AgentLeaseReceipt.newBuilder().setNodeId(nodeId.toString())
                            .setLeaseId(assignment.leaseId().toString())
                            .setFencingToken(assignment.fencingToken())).build());
            assertEquals("receipt-offer", assertInstanceOf(ConnectResponse.class, client.receive())
                    .getLeaseReceiptAcknowledgement().getRequestMessageId());
            assertEquals("ACKNOWLEDGED", jdbc.queryForObject(
                    "SELECT state FROM gpu_leases WHERE id = ?", String.class, assignment.leaseId()));
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
        return hello(nodeId, false);
    }

    private static ConnectRequest hello(UUID nodeId, boolean supportsStaging) {
        return ConnectRequest.newBuilder().setMessageId("hello-1")
                .setHello(AgentHello.newBuilder().setNodeId(nodeId.toString())
                        .setProtocolVersion(1).setAgentVersion("mtls-integration")
                        .setSupportsCommandStaging(supportsStaging)
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class TestServer implements AutoCloseable {
        private final Server server;

        private TestServer(SignedCertificate identity, Path ca, AgentSessionGrpcService service)
                throws IOException {
            this(identity, ca, service, null);
        }

        private TestServer(SignedCertificate identity, Path ca, AgentSessionGrpcService service,
                AgentArtifactGrpcService artifacts) throws IOException {
            ServerCredentials credentials = TlsServerCredentials.newBuilder()
                    .keyManager(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(ca.toFile())
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
            var builder = Grpc.newServerBuilderForPort(0, credentials)
                    .addService(ServerInterceptors.intercept(service, new AgentPeerInterceptor()));
            if (artifacts != null) {
                builder.addService(ServerInterceptors.intercept(artifacts, new AgentPeerInterceptor()));
            }
            server = builder.build().start();
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
