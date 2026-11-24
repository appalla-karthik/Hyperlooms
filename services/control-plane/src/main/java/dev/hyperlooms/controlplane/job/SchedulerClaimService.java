package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims at most one eligible queued job. There is deliberately no timer or
 * agent command yet: a lease is not useful until delivery and expiry recovery
 * are implemented and qualified together.
 */
@Service
public class SchedulerClaimService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SchedulerClaimService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<LeaseAssignment> claimNext() {
        return transactions.execute(status -> claimInTransaction());
    }

    private Optional<LeaseAssignment> claimInTransaction() {
        List<JobCandidate> jobs = jdbc.query("""
                SELECT j.id, r.min_vram_bytes
                FROM jobs j
                JOIN workload_recipes r ON r.id = j.recipe_id
                WHERE j.state = 'QUEUED' AND r.state = 'APPROVED'
                  AND EXISTS (
                      SELECT 1 FROM provider_gpus g
                      JOIN provider_nodes n ON n.id = g.node_id
                      WHERE n.enrollment_state = 'VERIFIED'
                        AND n.availability_state = 'AVAILABLE'
                        AND n.protocol_version = 1
                        AND n.last_seen_at > clock_timestamp() - INTERVAL '90 seconds'
                        AND g.verification_state = 'VERIFIED'
                        AND g.vram_bytes >= r.min_vram_bytes
                        AND EXISTS (
                            SELECT 1 FROM provider_certificates c
                            WHERE c.node_id = n.id AND c.activated_at IS NOT NULL
                              AND c.revoked_at IS NULL AND c.not_after > clock_timestamp()
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM gpu_leases l
                            WHERE l.node_id = n.id AND l.state IN ('ISSUED', 'ACKNOWLEDGED')
                        )
                  )
                ORDER BY j.created_at, j.id
                LIMIT 1
                FOR UPDATE OF j SKIP LOCKED
                FOR SHARE OF r SKIP LOCKED
                """, (rs, row) -> new JobCandidate(
                rs.getObject("id", UUID.class), rs.getLong("min_vram_bytes")));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        JobCandidate job = jobs.get(0);

        List<GpuCandidate> gpus = jdbc.query("""
                SELECT n.id AS node_id, g.gpu_uuid
                FROM provider_gpus g
                JOIN provider_nodes n ON n.id = g.node_id
                WHERE n.enrollment_state = 'VERIFIED'
                  AND n.availability_state = 'AVAILABLE'
                  AND n.protocol_version = 1
                  AND n.last_seen_at > clock_timestamp() - INTERVAL '90 seconds'
                  AND g.verification_state = 'VERIFIED'
                  AND g.vram_bytes >= ?
                  AND EXISTS (
                      SELECT 1 FROM provider_certificates c
                      WHERE c.node_id = n.id AND c.activated_at IS NOT NULL
                        AND c.revoked_at IS NULL AND c.not_after > clock_timestamp()
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM gpu_leases l
                      WHERE l.node_id = n.id AND l.state IN ('ISSUED', 'ACKNOWLEDGED')
                  )
                ORDER BY n.last_seen_at DESC, n.id, g.gpu_uuid
                LIMIT 1
                FOR UPDATE OF n, g SKIP LOCKED
                """, (rs, row) -> new GpuCandidate(
                rs.getObject("node_id", UUID.class), rs.getString("gpu_uuid")),
                job.minVramBytes());
        if (gpus.isEmpty()) {
            return Optional.empty();
        }
        GpuCandidate gpu = gpus.get(0);

        Integer nextAttempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1
                FROM job_attempts WHERE job_id = ?
                """, Integer.class, job.id());
        if (nextAttempt == null) {
            throw new IllegalStateException("Could not allocate job attempt number");
        }

        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_attempts (id, job_id, attempt_number, node_id, state)
                VALUES (?, ?, ?, ?, 'LEASED')
                """, attemptId, job.id(), nextAttempt, gpu.nodeId());
        List<LeaseExpiry> leases = jdbc.query("""
                INSERT INTO gpu_leases (id, attempt_id, node_id, gpu_uuid, state, expires_at)
                VALUES (?, ?, ?, ?, 'ISSUED', CURRENT_TIMESTAMP + INTERVAL '2 minutes')
                RETURNING fencing_token, expires_at
                """, (rs, row) -> new LeaseExpiry(
                rs.getLong("fencing_token"), rs.getObject("expires_at", Timestamp.class).toInstant()),
                leaseId, attemptId, gpu.nodeId(), gpu.gpuUuid());
        if (leases.size() != 1) {
            throw new IllegalStateException("Lease insertion returned no fencing token");
        }
        LeaseExpiry lease = leases.get(0);

        if (jdbc.update("""
                UPDATE provider_nodes
                SET availability_state = 'BUSY', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND availability_state = 'AVAILABLE'
                """, gpu.nodeId()) != 1) {
            throw new IllegalStateException("Provider became unavailable during claim");
        }
        if (jdbc.update("""
                UPDATE jobs SET state = 'LEASED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'QUEUED'
                """, job.id()) != 1) {
            throw new IllegalStateException("Job left queue during claim");
        }
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, 'JOB_LEASED',
                    jsonb_build_object('jobId', CAST(? AS text), 'attemptId', CAST(? AS text),
                        'leaseId', CAST(? AS text), 'nodeId', CAST(? AS text),
                        'gpuUuid', ?, 'fencingToken', ?))
                """, UUID.randomUUID(), "job:" + job.id() + ":attempt:" + nextAttempt + ":leased",
                job.id(), job.id(), attemptId, leaseId, gpu.nodeId(), gpu.gpuUuid(),
                lease.fencingToken());
        return Optional.of(new LeaseAssignment(job.id(), attemptId, leaseId,
                gpu.nodeId(), gpu.gpuUuid(), lease.fencingToken(), lease.expiresAt()));
    }

    private record JobCandidate(UUID id, long minVramBytes) {
    }

    private record GpuCandidate(UUID nodeId, String gpuUuid) {
    }

    private record LeaseExpiry(long fencingToken, Instant expiresAt) {
    }
}
