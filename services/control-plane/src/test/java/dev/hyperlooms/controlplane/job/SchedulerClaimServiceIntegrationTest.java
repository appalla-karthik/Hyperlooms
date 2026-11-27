package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

@Tag("integration")
@SpringBootTest
class SchedulerClaimServiceIntegrationTest {

    private static final String DIGEST = "a".repeat(64);
    private final List<UUID> jobs = new ArrayList<>();
    private final List<UUID> recipes = new ArrayList<>();
    private final List<UUID> nodes = new ArrayList<>();

    @Autowired
    private SchedulerClaimService claims;

    @Autowired
    private LeaseExpiryService expirations;

    @Autowired
    private LeaseAcknowledgementService acknowledgements;

    @Autowired
    private LeaseOfferService offers;

    @Autowired
    private LeaseExecutionStateService execution;

    @Autowired
    private LeaseExecutionPlanService executionPlans;

    @Autowired
    private LeaseExecutionCommandService executionCommands;

    @Autowired
    private LeaseRuntimeService runtime;

    @Autowired
    private JobCancellationService cancellations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ownerStopFencesRunningLeaseAndReconcilesCanceled() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment lease = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "stop");
        acknowledgements.acknowledge(identity, lease.leaseId(), lease.fencingToken());
        LeaseExecutionCommand command = executionCommands.prepareForPeer(identity,
                lease.leaseId(), lease.fencingToken());
        executionCommands.acknowledgeStaged(identity, lease.leaseId(), command.id(), lease.fencingToken());
        runtime.advance(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), LeaseRuntimeService.Action.STARTING);
        runtime.advance(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), LeaseRuntimeService.Action.RUNNING);
        assertEquals(JobState.RUNNING, cancellations.cancelUnleased(jobId,
                "claim-test:" + jobId).orElseThrow().state());
        assertThrows(LeaseExecutionDeniedException.class, () -> runtime.advance(identity,
                lease.leaseId(), command.id(), lease.fencingToken(), LeaseRuntimeService.Action.RENEW));
        expirations.expireNext().orElseThrow();
        assertEquals("CANCELED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("CANCELED", value("SELECT state FROM job_attempts WHERE id = ?", lease.attemptId()));
        assertEquals("REVOKED", value("SELECT state FROM gpu_leases WHERE id = ?", lease.leaseId()));
        assertEquals("OFFLINE", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
    }

    @Test
    void fencedRuntimeRenewalAndVerifiedResultCompleteOneJob() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment lease = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "runtime");
        acknowledgements.acknowledge(identity, lease.leaseId(), lease.fencingToken());
        LeaseExecutionCommand command = executionCommands.prepareForPeer(identity,
                lease.leaseId(), lease.fencingToken());
        executionCommands.acknowledgeStaged(identity, lease.leaseId(), command.id(), lease.fencingToken());

        assertThrows(LeaseExecutionDeniedException.class, () -> runtime.advance(identity,
                lease.leaseId(), UUID.randomUUID(), lease.fencingToken(), LeaseRuntimeService.Action.STARTING));
        Instant first = runtime.advance(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), LeaseRuntimeService.Action.STARTING);
        assertTrue(first.isAfter(Instant.now()));
        assertEquals("STARTING", value("SELECT state FROM jobs WHERE id = ?", jobId));
        runtime.advance(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), LeaseRuntimeService.Action.RUNNING);
        runtime.advance(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), LeaseRuntimeService.Action.RENEW);

        Map<String, byte[]> objects = new HashMap<>();
        InputObjectStore store = new InputObjectStore() {
            @Override public InputStream get(String key) {
                byte[] content = objects.get(key);
                if (content == null) throw new InputObjectMissingException();
                return new ByteArrayInputStream(content);
            }
            @Override public void put(String key, byte[] content) { throw new UnsupportedOperationException(); }
            @Override public boolean putIfAbsent(String key, byte[] content) {
                return objects.putIfAbsent(key, content) == null;
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<InputObjectStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        VerifiedResultService results = new VerifiedResultService(jdbc, transactions, runtime, provider);
        byte[] output = ("{\"schemaVersion\":1,\"device\":\"cuda\",\"epochs\":20,"
                + "\"weights\":[1.5],\"bias\":0.5,\"meanSquaredError\":0.01}")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] duplicate = new String(output, StandardCharsets.US_ASCII)
                .replace("\"epochs\":20", "\"epochs\":20,\"epochs\":20")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(LeaseExecutionDeniedException.class, () -> results.complete(identity,
                lease.leaseId(), command.id(), lease.fencingToken(), duplicate, sha256(duplicate)));
        assertThrows(LeaseExecutionDeniedException.class, () -> results.complete(identity,
                lease.leaseId(), command.id(), lease.fencingToken(), output, DIGEST));
        assertEquals("RUNNING", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals(sha256(output), results.complete(identity, lease.leaseId(), command.id(),
                lease.fencingToken(), output, sha256(output)));
        assertEquals("SUCCEEDED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("COMPLETED", value("SELECT state FROM gpu_leases WHERE id = ?", lease.leaseId()));
        assertEquals("OFFLINE", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
        assertEquals(new String(output, StandardCharsets.US_ASCII),
                new String(results.readOwned(jobId, "claim-test:" + jobId), StandardCharsets.US_ASCII));
        assertThrows(VerifiedResultService.ResultUnavailableException.class,
                () -> results.readOwned(jobId, "another-user"));
        assertThrows(LeaseExecutionDeniedException.class, () -> runtime.advance(identity,
                lease.leaseId(), command.id(), lease.fencingToken(), LeaseRuntimeService.Action.RENEW));
    }

    @AfterEach
    void removeTestRecords() {
        for (UUID jobId : jobs) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", jobId);
            jdbc.update("DELETE FROM lease_execution_command_staging WHERE lease_id IN "
                    + "(SELECT l.id FROM gpu_leases l JOIN job_attempts a "
                    + "ON a.id = l.attempt_id WHERE a.job_id = ?)", jobId);
            jdbc.update("DELETE FROM lease_execution_commands WHERE lease_id IN "
                    + "(SELECT l.id FROM gpu_leases l JOIN job_attempts a "
                    + "ON a.id = l.attempt_id WHERE a.job_id = ?)", jobId);
            jdbc.update("DELETE FROM gpu_leases WHERE attempt_id IN "
                    + "(SELECT id FROM job_attempts WHERE job_id = ?)", jobId);
            jdbc.update("DELETE FROM job_attempts WHERE job_id = ?", jobId);
            jdbc.update("DELETE FROM jobs WHERE id = ?", jobId);
        }
        for (UUID recipeId : recipes) {
            jdbc.update("DELETE FROM workload_recipes WHERE id = ?", recipeId);
        }
        for (UUID nodeId : nodes) {
            jdbc.update("DELETE FROM provider_gpus WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM provider_certificates WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM provider_nodes WHERE id = ?", nodeId);
        }
    }

    @Test
    void verifiedTwoGigabyteGpuCanClaimSmallerRecipeButNotDoubleBook() {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(2L * 1024 * 1024 * 1024);

        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        assertEquals(jobId, assignment.jobId());
        assertEquals(nodeId, assignment.nodeId());
        assertEquals("GPU-1", assignment.gpuUuid());
        assertTrue(assignment.fencingToken() > 0);
        assertTrue(assignment.expiresAt().isAfter(Instant.now()));
        assertEquals("LEASED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("BUSY", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM job_attempts WHERE job_id = ?", jobId));
        assertEquals(1L, count("SELECT COUNT(*) FROM gpu_leases WHERE id = ?", assignment.leaseId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_LEASED'", jobId));
        assertTrue(claims.claimNext().isEmpty());
    }

    @Test
    void boundedWorkerClaimsOneEligibleJobThenStopsWithoutDoubleBooking() {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID firstJob = queuedJob(recipeId);
        UUID secondJob = queuedJob(recipeId);
        UUID nodeId = provider(2L * 1024 * 1024 * 1024);

        assertEquals(1, new SchedulerClaimWorker(claims).drainOnce());
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE id IN (?, ?) AND state = 'LEASED'",
                firstJob, secondJob));
        assertEquals(1L, count("SELECT COUNT(*) FROM gpu_leases WHERE node_id = ? "
                + "AND state = 'ISSUED'", nodeId));
        assertEquals(0, new SchedulerClaimWorker(claims).drainOnce());
    }

    @Test
    void insufficientMemoryUnverifiedGpuAndStaleHeartbeatCannotBeClaimed() {
        UUID recipeId = recipe(4L * 1024 * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(2L * 1024 * 1024 * 1024);

        assertTrue(claims.claimNext().isEmpty());
        jdbc.update("UPDATE provider_gpus SET vram_bytes = ?, verification_state = 'REPORTED', "
                + "verified_at = NULL WHERE node_id = ?", 8L * 1024 * 1024 * 1024, nodeId);
        assertTrue(claims.claimNext().isEmpty());
        jdbc.update("UPDATE provider_gpus SET verification_state = 'VERIFIED', "
                + "verified_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        jdbc.update("UPDATE provider_nodes SET last_seen_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' "
                + "WHERE id = ?", nodeId);
        assertTrue(claims.claimNext().isEmpty());
        jdbc.update("UPDATE provider_nodes SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", nodeId);
        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP "
                + "WHERE node_id = ?", nodeId);
        assertTrue(claims.claimNext().isEmpty());
        jdbc.update("UPDATE provider_certificates SET revoked_at = NULL WHERE node_id = ?", nodeId);
        assertTrue(claims.claimNext().isPresent());
        assertEquals("LEASED", value("SELECT state FROM jobs WHERE id = ?", jobId));
    }

    @Test
    void concurrentClaimsCannotAssignOneNodeTwice() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID firstJob = queuedJob(recipeId);
        UUID secondJob = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<LeaseAssignment>> first = executor.submit(() -> {
                start.await();
                return claims.claimNext();
            });
            Future<Optional<LeaseAssignment>> second = executor.submit(() -> {
                start.await();
                return claims.claimNext();
            });
            start.countDown();
            assertTrue(first.get().isPresent() != second.get().isPresent());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM gpu_leases WHERE node_id = ? "
                + "AND state = 'ISSUED'", nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE id IN (?, ?) AND state = 'LEASED'",
                firstJob, secondJob));
        assertEquals("BUSY", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
    }

    @Test
    void expiredLeaseMovesJobToRetryWaitAndQuarantinesNode() {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();

        assertTrue(expirations.expireNext().isEmpty());
        makeLeaseExpired(assignment.leaseId());
        LeaseExpiration expired = expirations.expireNext().orElseThrow();

        assertEquals(assignment.leaseId(), expired.leaseId());
        assertEquals(assignment.fencingToken(), expired.fencingToken());
        assertEquals("EXPIRED", value("SELECT state FROM gpu_leases WHERE id = ?", assignment.leaseId()));
        assertEquals("LEASE_EXPIRED", value("SELECT state FROM job_attempts WHERE id = ?",
                assignment.attemptId()));
        assertEquals("LEASE_TIMEOUT", value("SELECT failure_code FROM job_attempts WHERE id = ?",
                assignment.attemptId()));
        assertEquals("RETRY_WAIT", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("OFFLINE", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_LEASE_EXPIRED'", jobId));
        assertTrue(expirations.expireNext().isEmpty());
        assertTrue(claims.claimNext().isEmpty());
    }

    @Test
    void expiryWorkerDrainsExpiredLeasesWithoutRetryingJobs() {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID firstJob = queuedJob(recipeId);
        UUID secondJob = queuedJob(recipeId);
        provider(8L * 1024 * 1024 * 1024);
        provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment first = claims.claimNext().orElseThrow();
        LeaseAssignment second = claims.claimNext().orElseThrow();
        makeLeaseExpired(first.leaseId());
        makeLeaseExpired(second.leaseId());

        LeaseExpiryWorker worker = new LeaseExpiryWorker(expirations);
        assertEquals(2, worker.drainOnce());
        assertEquals(0, worker.drainOnce());
        assertEquals(2L, count("SELECT COUNT(*) FROM gpu_leases WHERE id IN (?, ?) "
                + "AND state = 'EXPIRED'", first.leaseId(), second.leaseId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM jobs WHERE id IN (?, ?) "
                + "AND state = 'RETRY_WAIT'", firstJob, secondJob));
        assertEquals(2L, count("SELECT COUNT(*) FROM provider_nodes WHERE id IN (?, ?) "
                + "AND availability_state = 'OFFLINE'", first.nodeId(), second.nodeId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN (?, ?) "
                + "AND event_type = 'JOB_LEASE_EXPIRED'", firstJob, secondJob));
    }

    @Test
    void concurrentExpiryWorkersRecordOneTimeout() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        makeLeaseExpired(assignment.leaseId());
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<LeaseExpiration>> first = executor.submit(() -> {
                start.await();
                return expirations.expireNext();
            });
            Future<Optional<LeaseExpiration>> second = executor.submit(() -> {
                start.await();
                return expirations.expireNext();
            });
            start.countDown();
            assertTrue(first.get().isPresent() != second.get().isPresent());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_LEASE_EXPIRED'", jobId));
    }

    @Test
    void expiringStrayLeaseDoesNotReopenCanceledJob() {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        jdbc.update("UPDATE job_attempts SET state = 'CANCELED', "
                + "completed_at = CURRENT_TIMESTAMP WHERE id = ?", assignment.attemptId());
        jdbc.update("UPDATE jobs SET state = 'CANCELED' WHERE id = ?", jobId);
        makeLeaseExpired(assignment.leaseId());

        assertTrue(expirations.expireNext().isPresent());
        assertEquals("CANCELED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("CANCELED", value("SELECT state FROM job_attempts WHERE id = ?",
                assignment.attemptId()));
        assertEquals("EXPIRED", value("SELECT state FROM gpu_leases WHERE id = ?", assignment.leaseId()));
        assertEquals("OFFLINE", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
    }

    @Test
    void authenticatedReceiptIsIdempotentAndEmitsOneEvent() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity peer = peer(nodeId, "active");

        LeaseAcknowledgement first = acknowledgements.acknowledge(
                peer, assignment.leaseId(), assignment.fencingToken());
        LeaseAcknowledgement second = acknowledgements.acknowledge(
                peer, assignment.leaseId(), assignment.fencingToken());

        assertEquals(first, second);
        assertEquals(assignment.expiresAt(), first.expiresAt());
        assertEquals("ACKNOWLEDGED", value("SELECT state FROM gpu_leases WHERE id = ?", assignment.leaseId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_LEASE_ACKNOWLEDGED'", jobId));
    }

    @Test
    void receiptRejectsWrongPeerTokenRevokedCredentialAndExpiredLease() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity peer = peer(nodeId, "active");
        UUID otherNode = provider(8L * 1024 * 1024 * 1024);
        ProviderPeerIdentity otherPeer = peer(otherNode, "other");

        assertThrows(LeaseAcknowledgementDeniedException.class,
                () -> acknowledgements.acknowledge(otherPeer, assignment.leaseId(),
                        assignment.fencingToken()));
        assertThrows(LeaseAcknowledgementDeniedException.class,
                () -> acknowledgements.acknowledge(peer, assignment.leaseId(),
                        assignment.fencingToken() + 1));
        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        assertThrows(LeaseAcknowledgementDeniedException.class,
                () -> acknowledgements.acknowledge(peer, assignment.leaseId(),
                        assignment.fencingToken()));
        jdbc.update("UPDATE provider_certificates SET revoked_at = NULL WHERE node_id = ?", nodeId);
        makeLeaseExpired(assignment.leaseId());
        assertThrows(LeaseAcknowledgementDeniedException.class,
                () -> acknowledgements.acknowledge(peer, assignment.leaseId(),
                        assignment.fencingToken()));
        assertEquals("ISSUED", value("SELECT state FROM gpu_leases WHERE id = ?", assignment.leaseId()));
    }

    @Test
    void concurrentReceiptsCannotDuplicateAcknowledgementEvent() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity peer = peer(nodeId, "active");
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LeaseAcknowledgement> first = executor.submit(() -> {
                start.await();
                return acknowledgements.acknowledge(peer, assignment.leaseId(), assignment.fencingToken());
            });
            Future<LeaseAcknowledgement> second = executor.submit(() -> {
                start.await();
                return acknowledgements.acknowledge(peer, assignment.leaseId(), assignment.fencingToken());
            });
            start.countDown();
            assertEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_LEASE_ACKNOWLEDGED'", jobId));
    }

    @Test
    void offerLookupRequiresActiveCredentialAndStopsAfterReceipt() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity peer = peer(nodeId, "active");

        assertEquals(assignment, offers.nextIssuedForPeer(peer).orElseThrow());
        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        assertTrue(offers.nextIssuedForPeer(peer).isEmpty());
        jdbc.update("UPDATE provider_certificates SET revoked_at = NULL WHERE node_id = ?", nodeId);
        acknowledgements.acknowledge(peer, assignment.leaseId(), assignment.fencingToken());
        assertTrue(offers.nextIssuedForPeer(peer).isEmpty());
    }

    @Test
    void onlyAcknowledgedVerifiedV1JobYieldsAnExecutionPlan() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");

        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        LeaseExecutionPlan plan = executionPlans.loadCurrentForPeer(
                identity, assignment.leaseId(), assignment.fencingToken());

        assertEquals(jobId, plan.jobId());
        assertEquals(assignment.attemptId(), plan.attemptId());
        assertEquals(assignment.leaseId(), plan.leaseId());
        assertEquals(assignment.nodeId(), plan.nodeId());
        assertEquals(assignment.gpuUuid(), plan.gpuUuid());
        assertEquals(assignment.fencingToken(), plan.fencingToken());
        assertEquals(assignment.expiresAt(), plan.expiresAt());
        assertEquals("registry.example.invalid/hyperlooms/test", plan.imageRepository());
        assertEquals("sha256:" + DIGEST, plan.imageDigest());
        assertEquals(60, plan.maxRuntimeSeconds());
        assertEquals("jobs/" + jobId + "/verified/snapshot", plan.verifiedInputPrefix());
        assertEquals(DIGEST, plan.inputManifestSha256());
        assertEquals(1, plan.inputFileCount());
        assertEquals(32L, plan.inputTotalBytes());
    }

    @Test
    void acknowledgedLeasePreparesOneDurableCommandAndOutboxEvent() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");

        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.prepareForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        LeaseExecutionCommand command = executionCommands.prepareForPeer(
                identity, assignment.leaseId(), assignment.fencingToken());
        LeaseExecutionCommand retry = executionCommands.prepareForPeer(
                identity, assignment.leaseId(), assignment.fencingToken());

        assertEquals(command.id(), retry.id());
        assertEquals(assignment.leaseId(), command.leaseId());
        assertEquals(nodeId, command.nodeId());
        assertEquals(assignment.gpuUuid(), command.gpuUuid());
        assertEquals(assignment.fencingToken(), command.fencingToken());
        assertEquals("sha256:" + DIGEST, command.imageDigest());
        assertEquals("jobs/" + jobId + "/verified/snapshot", command.verifiedInputPrefix());
        assertEquals(1L, count("SELECT COUNT(*) FROM lease_execution_commands WHERE lease_id = ?",
                assignment.leaseId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_EXECUTION_COMMAND_PREPARED'", jobId));
        LeaseExecutionDelivery delivery = executionCommands.nextForPeer(identity).orElseThrow();
        assertEquals(command.id(), delivery.command().id());
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.acknowledgeStaged(identity, assignment.leaseId(),
                        command.id(), assignment.fencingToken() + 1));
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.acknowledgeStaged(identity, assignment.leaseId(),
                        UUID.randomUUID(), assignment.fencingToken()));
        executionCommands.acknowledgeStaged(identity, assignment.leaseId(),
                command.id(), assignment.fencingToken());
        executionCommands.acknowledgeStaged(identity, assignment.leaseId(),
                command.id(), assignment.fencingToken());
        assertTrue(executionCommands.nextForPeer(identity).isEmpty());
        assertEquals(1L, count("SELECT COUNT(*) FROM lease_execution_command_staging "
                + "WHERE command_id = ?", command.id()));
    }

    @Test
    void scopedInputReadRequiresStagedCurrentCommandAndVerifiesEveryByte() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = verifiedQueuedJob(recipeId);
        byte[] input = "{\"small\":\"bounded test request\"}".getBytes(StandardCharsets.US_ASCII);
        byte[] manifest = ("{\"files\":[{\"path\":\"request.json\",\"sha256\":\""
                + sha256(input) + "\",\"sizeBytes\":" + input.length
                + "}],\"schemaVersion\":1}").getBytes(StandardCharsets.US_ASCII);
        String prefix = "jobs/" + jobId + "/verified/snapshot/";
        jdbc.update("""
                UPDATE jobs SET input_manifest_sha256 = ?, verified_input_manifest_sha256 = ?,
                    input_artifact_prefix = ?, input_total_bytes = ? WHERE id = ?
                """, sha256(manifest), sha256(manifest), prefix, input.length, jobId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        LeaseExecutionCommand command = executionCommands.prepareForPeer(identity,
                assignment.leaseId(), assignment.fencingToken());
        Map<String, byte[]> content = new HashMap<>();
        content.put(prefix + "manifest.json", manifest);
        content.put(prefix + "files/request.json", input);
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
        ScopedInputReadService reader = new ScopedInputReadService(jdbc, executionPlans, objects);
        assertThrows(LeaseExecutionDeniedException.class, () -> reader.readFile(identity,
                assignment.leaseId(), command.id(), assignment.fencingToken(), "request.json"));
        executionCommands.acknowledgeStaged(identity, assignment.leaseId(), command.id(),
                assignment.fencingToken());
        assertEquals(new String(input, StandardCharsets.US_ASCII),
                new String(reader.readFile(identity, assignment.leaseId(), command.id(),
                        assignment.fencingToken(), "request.json"), StandardCharsets.US_ASCII));
        assertThrows(LeaseExecutionDeniedException.class, () -> reader.readFile(identity,
                assignment.leaseId(), command.id(), assignment.fencingToken(), "../request.json"));
        assertThrows(LeaseExecutionDeniedException.class, () -> reader.readFile(identity,
                assignment.leaseId(), UUID.randomUUID(), assignment.fencingToken(), "request.json"));
        content.put(prefix + "files/request.json", "corrupted".getBytes(StandardCharsets.US_ASCII));
        assertThrows(LeaseExecutionDeniedException.class, () -> reader.readFile(identity,
                assignment.leaseId(), command.id(), assignment.fencingToken(), "request.json"));
        content.put(prefix + "files/request.json", input);
        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP "
                + "WHERE node_id = ?", nodeId);
        assertThrows(LeaseExecutionDeniedException.class, () -> reader.readFile(identity,
                assignment.leaseId(), command.id(), assignment.fencingToken(), "request.json"));
    }

    @Test
    void preparedCommandRejectsChangedRecipeAndDatabaseUpdates() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        LeaseExecutionCommand command = executionCommands.prepareForPeer(
                identity, assignment.leaseId(), assignment.fencingToken());

        jdbc.update("UPDATE workload_recipes SET image_digest = ? WHERE id = ?",
                "sha256:" + "b".repeat(64), recipeId);
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.prepareForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        assertEquals("sha256:" + DIGEST,
                value("SELECT image_digest FROM lease_execution_commands WHERE id = ?", command.id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE lease_execution_commands SET image_digest = ? WHERE id = ?",
                        "sha256:" + "b".repeat(64), command.id()));
    }

    @Test
    void commandPreparationRejectsWrongPeerTokenAndLegacyJob() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());

        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.prepareForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        assertEquals(0L, count("SELECT COUNT(*) FROM lease_execution_commands WHERE lease_id = ?",
                assignment.leaseId()));

        UUID verifiedRecipe = recipe(1024L * 1024 * 1024);
        verifiedQueuedJob(verifiedRecipe);
        UUID secondNode = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment second = claims.claimNext().orElseThrow();
        ProviderPeerIdentity secondPeer = peer(secondNode, "second");
        acknowledgements.acknowledge(secondPeer, second.leaseId(), second.fencingToken());
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.prepareForPeer(identity, second.leaseId(),
                        second.fencingToken()));
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionCommands.prepareForPeer(secondPeer, second.leaseId(),
                        second.fencingToken() + 1));
        assertEquals(0L, count("SELECT COUNT(*) FROM lease_execution_commands WHERE lease_id = ?",
                second.leaseId()));
    }

    @Test
    void executionPlanRejectsLegacyJobAndWrongPeerOrToken() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());

        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));

        UUID verifiedRecipe = recipe(1024L * 1024 * 1024);
        verifiedQueuedJob(verifiedRecipe);
        UUID secondNode = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment second = claims.claimNext().orElseThrow();
        ProviderPeerIdentity secondPeer = peer(secondNode, "second");
        acknowledgements.acknowledge(secondPeer, second.leaseId(), second.fencingToken());
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, second.leaseId(),
                        second.fencingToken()));
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(secondPeer, second.leaseId(),
                        second.fencingToken() + 1));
    }

    @Test
    void executionPlanRejectsRevocationRecipeDisableAndExpiry() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        verifiedQueuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());

        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        jdbc.update("UPDATE provider_certificates SET revoked_at = NULL WHERE node_id = ?", nodeId);
        jdbc.update("UPDATE workload_recipes SET state = 'DISABLED' WHERE id = ?", recipeId);
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        jdbc.update("UPDATE workload_recipes SET state = 'APPROVED' WHERE id = ?", recipeId);
        jdbc.update("UPDATE provider_gpus SET verification_state = 'REJECTED', "
                + "verified_at = NULL WHERE node_id = ?", nodeId);
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
        jdbc.update("UPDATE provider_gpus SET verification_state = 'VERIFIED', "
                + "verified_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        makeLeaseExpired(assignment.leaseId());
        assertThrows(LeaseExecutionDeniedException.class,
                () -> executionPlans.loadCurrentForPeer(identity, assignment.leaseId(),
                        assignment.fencingToken()));
    }

    @Test
    void executionTransitionsRequireAcknowledgedLeaseAndQuarantineOnFailure() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");

        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken()));
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken());
        assertEquals("STARTING", value("SELECT state FROM jobs WHERE id = ?", jobId));
        execution.markRunning(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markRunning(identity, assignment.leaseId(), assignment.fencingToken());
        assertEquals("RUNNING", value("SELECT state FROM job_attempts WHERE id = ?", assignment.attemptId()));
        execution.markFailed(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markFailed(identity, assignment.leaseId(), assignment.fencingToken());

        assertEquals("FAILED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("EXECUTION_FAILED", value("SELECT failure_code FROM job_attempts WHERE id = ?",
                assignment.attemptId()));
        assertEquals("COMPLETED", value("SELECT state FROM gpu_leases WHERE id = ?", assignment.leaseId()));
        assertEquals("OFFLINE", value("SELECT availability_state FROM provider_nodes WHERE id = ?", nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'JOB_STARTING'", jobId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'JOB_RUNNING'", jobId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'JOB_EXECUTION_FAILED'", jobId));
        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markRunning(identity, assignment.leaseId(), assignment.fencingToken()));
    }

    @Test
    void executionRejectsWrongPeerTokenRevokedCredentialAndExpiredLease() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        UUID otherNode = provider(8L * 1024 * 1024 * 1024);
        ProviderPeerIdentity otherIdentity = peer(otherNode, "other");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());

        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markStarting(otherIdentity, assignment.leaseId(), assignment.fencingToken()));
        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken() + 1));
        jdbc.update("UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP WHERE node_id = ?", nodeId);
        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken()));
        jdbc.update("UPDATE provider_certificates SET revoked_at = NULL WHERE node_id = ?", nodeId);
        makeLeaseExpired(assignment.leaseId());
        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken()));
        assertEquals("LEASED", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'JOB_STARTING'", jobId));
    }

    @Test
    void expiredRunningLeaseCannotReportFailureAfterReconciliation() throws Exception {
        UUID recipeId = recipe(1024L * 1024 * 1024);
        UUID jobId = queuedJob(recipeId);
        UUID nodeId = provider(8L * 1024 * 1024 * 1024);
        LeaseAssignment assignment = claims.claimNext().orElseThrow();
        ProviderPeerIdentity identity = peer(nodeId, "active");
        acknowledgements.acknowledge(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markStarting(identity, assignment.leaseId(), assignment.fencingToken());
        execution.markRunning(identity, assignment.leaseId(), assignment.fencingToken());
        makeLeaseExpired(assignment.leaseId());
        expirations.expireNext().orElseThrow();

        assertThrows(LeaseExecutionDeniedException.class,
                () -> execution.markFailed(identity, assignment.leaseId(), assignment.fencingToken()));
        assertEquals("RETRY_WAIT", value("SELECT state FROM jobs WHERE id = ?", jobId));
        assertEquals("LEASE_EXPIRED", value("SELECT state FROM job_attempts WHERE id = ?",
                assignment.attemptId()));
    }

    private ProviderPeerIdentity peer(UUID nodeId, String serial) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:" + nodeId)));
        when(certificate.getEncoded()).thenReturn(
                ("lease-test-cert-" + nodeId + "-" + serial).getBytes(StandardCharsets.US_ASCII));
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
        ProviderPeerIdentity identity = ProviderPeerIdentity.fromCertificate(certificate);
        jdbc.update("UPDATE provider_certificates SET certificate_sha256 = ? WHERE node_id = ?",
                identity.certificateSha256(), nodeId);
        return identity;
    }

    private void makeLeaseExpired(UUID leaseId) {
        jdbc.update("""
                UPDATE gpu_leases
                SET issued_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """, leaseId);
    }

    private UUID recipe(long minVram) {
        UUID id = UUID.randomUUID();
        recipes.add(id);
        jdbc.update("""
                INSERT INTO workload_recipes
                    (id, name, image_repository, image_digest, max_runtime_seconds,
                     min_vram_bytes, state, approved_at)
                VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?, 60,
                    ?, 'APPROVED', CURRENT_TIMESTAMP)
                """, id, "claim-test-" + id, "sha256:" + DIGEST, minVram);
        return id;
    }

    private UUID queuedJob(UUID recipeId) {
        UUID id = UUID.randomUUID();
        jobs.add(id);
        jdbc.update("""
                INSERT INTO jobs
                    (id, requester_subject, recipe_id, state, idempotency_key, request_sha256)
                VALUES (?, ?, ?, 'QUEUED', ?, ?)
                """, id, "claim-test:" + id, recipeId, id.toString(), DIGEST);
        return id;
    }

    private UUID verifiedQueuedJob(UUID recipeId) {
        UUID id = UUID.randomUUID();
        jobs.add(id);
        jdbc.update("""
                INSERT INTO jobs
                    (id, requester_subject, recipe_id, state, idempotency_key,
                     request_sha256, request_version, input_manifest_sha256,
                     verified_input_manifest_sha256, input_artifact_prefix,
                     input_verified_at, input_file_count, input_total_bytes)
                VALUES (?, ?, ?, 'QUEUED', ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, 1, 32)
                """, id, "claim-test:" + id, recipeId, id.toString(), DIGEST,
                DIGEST, DIGEST, "jobs/" + id + "/verified/snapshot");
        return id;
    }

    private UUID provider(long vram) {
        UUID id = UUID.randomUUID();
        nodes.add(id);
        jdbc.update("""
                INSERT INTO provider_nodes
                    (id, owner_subject, enrollment_state, availability_state,
                     protocol_version, last_seen_at)
                VALUES (?, ?, 'VERIFIED', 'AVAILABLE', 1, CURRENT_TIMESTAMP)
                """, id, "claim-test:" + id);
        jdbc.update("""
                INSERT INTO provider_certificates
                    (id, node_id, certificate_sha256, not_after, activated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), id, id.toString().replace("-", "").repeat(2));
        jdbc.update("""
                INSERT INTO provider_gpus
                    (node_id, gpu_uuid, model, vram_bytes, driver_version, cuda_version,
                     verification_state, verified_at)
                VALUES (?, 'GPU-1', 'Test NVIDIA GPU', ?, 'test-driver', 'test-cuda',
                    'VERIFIED', CURRENT_TIMESTAMP)
                """, id, vram);
        return id;
    }

    private String value(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbc.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
