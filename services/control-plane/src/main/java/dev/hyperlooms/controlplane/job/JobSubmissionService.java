package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobSubmissionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JobSubmissionService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public JobSubmissionResult submit(JobSubmissionCommand command) {
        try {
            return transactions.execute(status -> createOrReplay(command));
        } catch (DuplicateKeyException duplicate) {
            // A concurrent submission may have committed the same requester/key first.
            // Query in a fresh transaction: the failed PostgreSQL transaction is unusable.
            StoredJob winner = findByRequesterAndKey(command);
            if (winner == null) {
                throw duplicate;
            }
            return replayOrConflict(command, winner);
        }
    }

    private JobSubmissionResult createOrReplay(JobSubmissionCommand command) {
        StoredJob existing = findByRequesterAndKey(command);
        if (existing != null) {
            return replayOrConflict(command, existing);
        }

        List<String> recipeStates = jdbc.queryForList(
                "SELECT state FROM workload_recipes WHERE id = ?", String.class,
                command.request().recipeId());
        if (recipeStates.isEmpty() || !"APPROVED".equals(recipeStates.get(0))) {
            throw new RecipeUnavailableException();
        }

        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs (id, requester_subject, recipe_id, state, idempotency_key,
                                  request_sha256, request_version, input_manifest_sha256)
                VALUES (?, ?, ?, 'CREATED', ?, ?, 1, ?)
                """, jobId, command.requesterSubject(), command.request().recipeId(),
                command.idempotencyKey(), command.request().requestSha256(),
                command.request().inputManifestSha256());
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, 'JOB_CREATED',
                    jsonb_build_object('jobId', CAST(? AS text), 'recipeId', CAST(? AS text)))
                """, UUID.randomUUID(), "job:" + jobId + ":created", jobId, jobId,
                command.request().recipeId());
        return new JobSubmissionResult(jobId, JobState.CREATED, false);
    }

    private StoredJob findByRequesterAndKey(JobSubmissionCommand command) {
        List<StoredJob> matches = jdbc.query("""
                SELECT id, recipe_id, request_sha256, request_version,
                       input_manifest_sha256, state
                FROM jobs WHERE requester_subject = ? AND idempotency_key = ?
                """, (rs, row) -> new StoredJob(
                rs.getObject("id", UUID.class),
                rs.getObject("recipe_id", UUID.class),
                rs.getString("request_sha256").trim(),
                rs.getInt("request_version"),
                rs.getString("input_manifest_sha256") == null ? null
                        : rs.getString("input_manifest_sha256").trim(),
                JobState.valueOf(rs.getString("state"))),
                command.requesterSubject(), command.idempotencyKey());
        return matches.isEmpty() ? null : matches.get(0);
    }

    private JobSubmissionResult replayOrConflict(JobSubmissionCommand command, StoredJob existing) {
        if (existing.requestVersion() != 1
                || !existing.recipeId().equals(command.request().recipeId())
                || !existing.inputManifestSha256().equals(command.request().inputManifestSha256())
                || !existing.requestSha256().equals(command.request().requestSha256())) {
            throw new IdempotencyConflictException();
        }
        return new JobSubmissionResult(existing.id(), existing.state(), true);
    }

    private record StoredJob(UUID id, UUID recipeId, String requestSha256,
            int requestVersion, String inputManifestSha256, JobState state) {
    }
}
