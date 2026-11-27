package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner stop requests fence renewal immediately; the reconciler terminates active leases. */
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
            List<StoredState> states = jdbc.query("""
                    SELECT state, stop_requested_at FROM jobs
                    WHERE id = ? AND requester_subject = ? AND request_version = 1
                    FOR UPDATE
                    """, (rs, row) -> new StoredState(JobState.valueOf(rs.getString("state")),
                    rs.getTimestamp("stop_requested_at") != null), jobId, requester);
            if (states.isEmpty()) {
                return Optional.empty();
            }
            JobState current = states.get(0).state();
            if (current == JobState.CANCELED) {
                return Optional.of(new JobCancellationResult(jobId, JobState.CANCELED, true));
            }
            if (states.get(0).stopRequested()) {
                return Optional.of(new JobCancellationResult(jobId, current, true));
            }
            if (current == JobState.LEASED || current == JobState.STARTING
                    || current == JobState.RUNNING || current == JobState.UPLOADING_RESULTS) {
                Long active = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM gpu_leases l
                        JOIN job_attempts a ON a.id = l.attempt_id
                        WHERE a.job_id = ? AND l.state IN ('ISSUED', 'ACKNOWLEDGED')
                        """, Long.class, jobId);
                if (active == null || active != 1L) {
                    throw new JobCancellationConflictException();
                }
                jdbc.update("UPDATE jobs SET stop_requested_at = CURRENT_TIMESTAMP, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?", jobId);
                return Optional.of(new JobCancellationResult(jobId, current, false));
            }
            if (current != JobState.CREATED && current != JobState.QUEUED
                    && current != JobState.RETRY_WAIT) {
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

    private record StoredState(JobState state, boolean stopRequested) { }
}
