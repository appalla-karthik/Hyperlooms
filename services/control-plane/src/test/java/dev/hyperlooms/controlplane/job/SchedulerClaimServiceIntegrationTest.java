package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRecords() {
        for (UUID jobId : jobs) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", jobId);
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
}
