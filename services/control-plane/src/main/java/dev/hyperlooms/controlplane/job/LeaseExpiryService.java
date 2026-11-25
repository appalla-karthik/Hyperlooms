package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles one expired active lease at a time. The opt-in background poller
 * calls this transaction; an expiry is not proof of remote process shutdown.
 */
@Service
public class LeaseExpiryService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LeaseExpiryService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<LeaseExpiration> expireNext() {
        return transactions.execute(status -> expireInTransaction());
    }

    private Optional<LeaseExpiration> expireInTransaction() {
        List<ExpiredCandidate> candidates = jdbc.query("""
                SELECT l.id AS lease_id, l.attempt_id, l.node_id, l.fencing_token,
                       a.job_id, a.state AS attempt_state, j.state AS job_state
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id
                JOIN provider_nodes n ON n.id = l.node_id
                WHERE l.state IN ('ISSUED', 'ACKNOWLEDGED')
                  AND l.expires_at <= clock_timestamp()
                ORDER BY l.expires_at, l.id
                LIMIT 1
                FOR UPDATE OF l, a, j, n SKIP LOCKED
                """, (rs, row) -> new ExpiredCandidate(
                rs.getObject("job_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("lease_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getLong("fencing_token"),
                rs.getString("attempt_state"),
                JobState.valueOf(rs.getString("job_state"))));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ExpiredCandidate candidate = candidates.get(0);

        if (jdbc.update("""
                UPDATE gpu_leases
                SET state = 'EXPIRED', completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN ('ISSUED', 'ACKNOWLEDGED')
                """, candidate.leaseId()) != 1) {
            throw new IllegalStateException("Locked lease could not be expired");
        }
        boolean activeAttempt = isActiveAttempt(candidate.attemptState());
        if (activeAttempt) {
            if (jdbc.update("""
                    UPDATE job_attempts
                    SET state = 'LEASE_EXPIRED', failure_code = 'LEASE_TIMEOUT',
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state IN ('LEASED', 'STARTING', 'RUNNING', 'UPLOADING_RESULTS')
                    """, candidate.attemptId()) != 1) {
                throw new IllegalStateException("Locked attempt changed during expiry");
            }
        }
        if (activeAttempt && candidate.jobState().canTransitionTo(JobState.RETRY_WAIT)) {
            if (jdbc.update("""
                    UPDATE jobs SET state = 'RETRY_WAIT', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state IN ('LEASED', 'STARTING', 'RUNNING', 'UPLOADING_RESULTS')
                    """, candidate.jobId()) != 1) {
                throw new IllegalStateException("Locked job changed during expiry");
            }
        }
        jdbc.update("""
                UPDATE provider_nodes
                SET availability_state = 'OFFLINE', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND availability_state = 'BUSY'
                """, candidate.nodeId());
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, 'JOB_LEASE_EXPIRED',
                    jsonb_build_object('jobId', CAST(? AS text), 'attemptId', CAST(? AS text),
                        'leaseId', CAST(? AS text), 'nodeId', CAST(? AS text),
                        'fencingToken', ?))
                """, UUID.randomUUID(), "lease:" + candidate.leaseId() + ":expired",
                candidate.jobId(), candidate.jobId(), candidate.attemptId(),
                candidate.leaseId(), candidate.nodeId(), candidate.fencingToken());
        return Optional.of(new LeaseExpiration(candidate.jobId(), candidate.attemptId(),
                candidate.leaseId(), candidate.nodeId(), candidate.fencingToken()));
    }

    private static boolean isActiveAttempt(String state) {
        return "LEASED".equals(state) || "STARTING".equals(state)
                || "RUNNING".equals(state) || "UPLOADING_RESULTS".equals(state);
    }

    private record ExpiredCandidate(
            UUID jobId, UUID attemptId, UUID leaseId, UUID nodeId,
            long fencingToken, String attemptState, JobState jobState) {
    }
}
