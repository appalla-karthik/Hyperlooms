package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancels only unleased owner jobs; active work requires a separate agent stop protocol. */
@Service
public class JobCancellationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JobCancellationService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<JobCancellationResult> cancelUnleased(UUID jobId, String requester) {
        if (jobId == null || requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("Job and verified requester are required");
        }
        return transactions.execute(status -> {
            List<JobState> states = jdbc.query("""
                    SELECT state FROM jobs
                    WHERE id = ? AND requester_subject = ? AND request_version = 1
                    FOR UPDATE
                    """, (rs, row) -> JobState.valueOf(rs.getString("state")), jobId, requester);
            if (states.isEmpty()) {
                return Optional.empty();
            }
            JobState current = states.get(0);
            if (current == JobState.CANCELED) {
                return Optional.of(new JobCancellationResult(jobId, JobState.CANCELED, true));
            }
            if (current != JobState.CREATED && current != JobState.QUEUED) {
                throw new JobCancellationConflictException();
            }
            if (jdbc.update("""
                    UPDATE jobs SET state = 'CANCELED', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state = ? AND requester_subject = ? AND request_version = 1
                    """, jobId, current.name(), requester) != 1) {
                throw new IllegalStateException("Locked job changed during cancellation");
            }
            jdbc.update("""
                    INSERT INTO outbox_events
                        (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, 'JOB', ?, 'JOB_CANCELED',
                        jsonb_build_object('jobId', CAST(? AS text), 'previousState', ?))
                    """, UUID.randomUUID(), "job:" + jobId + ":canceled", jobId,
                    jobId, current.name());
            return Optional.of(new JobCancellationResult(jobId, JobState.CANCELED, false));
        });
    }
}
