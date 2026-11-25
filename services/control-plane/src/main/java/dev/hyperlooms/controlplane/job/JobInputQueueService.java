package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal-only queue boundary; caller identity must come from future authentication. */
public final class JobInputQueueService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final InputSnapshotStager stager;

    public JobInputQueueService(JdbcTemplate jdbc, TransactionTemplate transactions,
            InputSnapshotStager stager) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.stager = java.util.Objects.requireNonNull(stager, "stager");
    }

    public JobInputQueueResult queueVerifiedInput(UUID jobId, String trustedRequesterSubject) {
        if (jobId == null || trustedRequesterSubject == null
                || trustedRequesterSubject.isBlank() || trustedRequesterSubject.length() > 200) {
            throw new IllegalArgumentException("job ID and trusted requester subject are required");
        }

        JobRecord initial = findJob(jobId, false);
        requireOwnerAndVersion(initial, trustedRequesterSubject);
        if (initial.state() == JobState.QUEUED) {
            return replay(jobId, initial);
        }
        if (initial.state() != JobState.CREATED || !"APPROVED".equals(initial.recipeState())) {
            throw new JobInputQueueDeniedException();
        }

        // S3 I/O is outside the database transaction. A canceled job can leave an
        // orphaned copy; the locked final check never queues that copy.
        VerifiedInputSnapshot snapshot = stager.stage(jobId, initial.manifestSha256());
        return transactions.execute(status -> bindAndQueue(jobId, trustedRequesterSubject, snapshot));
    }

    private JobInputQueueResult bindAndQueue(UUID jobId, String requester,
            VerifiedInputSnapshot snapshot) {
        JobRecord locked = findJob(jobId, true);
        requireOwnerAndVersion(locked, requester);
        if (locked.state() == JobState.QUEUED) {
            return replay(jobId, locked);
        }
        if (locked.state() != JobState.CREATED || !"APPROVED".equals(locked.recipeState())
                || !locked.manifestSha256().equals(snapshot.manifestSha256())
                || !snapshot.prefix().startsWith("jobs/" + jobId + "/verified/")
                || !snapshot.prefix().endsWith("/")
                || snapshot.fileCount() < 0 || snapshot.fileCount() > InputManifestV1.MAX_FILES
                || snapshot.totalBytes() < 0
                || snapshot.totalBytes() > InputSnapshotStager.MAX_TOTAL_BYTES) {
            throw new JobInputQueueDeniedException();
        }

        if (jdbc.update("""
                UPDATE jobs
                SET state = 'QUEUED', input_artifact_prefix = ?,
                    verified_input_manifest_sha256 = ?, input_verified_at = CURRENT_TIMESTAMP,
                    input_file_count = ?, input_total_bytes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'CREATED' AND requester_subject = ?
                  AND request_version = 1 AND input_manifest_sha256 = ?
                """, snapshot.prefix(), snapshot.manifestSha256(), snapshot.fileCount(),
                snapshot.totalBytes(), jobId, requester, snapshot.manifestSha256()) != 1) {
            throw new JobInputQueueDeniedException();
        }
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, 'JOB_QUEUED',
                    jsonb_build_object('jobId', CAST(? AS text),
                        'inputManifestSha256', ?, 'inputFileCount', ?, 'inputTotalBytes', ?))
                """, UUID.randomUUID(), "job:" + jobId + ":queued", jobId, jobId,
                snapshot.manifestSha256(), snapshot.fileCount(), snapshot.totalBytes());
        return new JobInputQueueResult(jobId, JobState.QUEUED, false);
    }

    private JobRecord findJob(UUID jobId, boolean lock) {
        String sql = """
                SELECT j.requester_subject, j.request_version, j.input_manifest_sha256,
                       j.state, j.input_artifact_prefix, r.state AS recipe_state
                FROM jobs j JOIN workload_recipes r ON r.id = j.recipe_id
                WHERE j.id = ?
                """ + (lock ? " FOR UPDATE OF j, r" : "");
        List<JobRecord> jobs = jdbc.query(sql, (rs, row) -> new JobRecord(
                rs.getString("requester_subject"), rs.getInt("request_version"),
                rs.getString("input_manifest_sha256") == null ? null
                        : rs.getString("input_manifest_sha256").trim(),
                JobState.valueOf(rs.getString("state")),
                rs.getString("input_artifact_prefix"), rs.getString("recipe_state")), jobId);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private static void requireOwnerAndVersion(JobRecord job, String requester) {
        if (job == null || !job.requesterSubject().equals(requester)
                || job.requestVersion() != 1 || job.manifestSha256() == null) {
            throw new JobInputQueueDeniedException();
        }
    }

    private static JobInputQueueResult replay(UUID jobId, JobRecord job) {
        if (job.verifiedPrefix() == null) {
            throw new JobInputQueueDeniedException();
        }
        return new JobInputQueueResult(jobId, JobState.QUEUED, true);
    }

    private record JobRecord(String requesterSubject, int requestVersion,
            String manifestSha256, JobState state, String verifiedPrefix, String recipeState) {
    }
}
