package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderIdentityDeniedException;
import dev.hyperlooms.controlplane.provider.ProviderIdentityService;
import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/** Short, fenced execution authorizations. A heartbeat never renews a lease. */
@Service
public final class LeaseRuntimeService {
    private static final int RENEW_SECONDS = 120;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProviderIdentityService identities;

    public LeaseRuntimeService(JdbcTemplate jdbc, TransactionTemplate transactions,
            ProviderIdentityService identities) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identities = identities;
    }

    public Instant advance(ProviderPeerIdentity peer, UUID leaseId, UUID commandId,
            long fencingToken, Action action) {
        if (peer == null || leaseId == null || commandId == null || fencingToken <= 0 || action == null) {
            throw new LeaseExecutionDeniedException();
        }
        return transactions.execute(status -> {
            Candidate row = currentLocked(peer, leaseId, commandId, fencingToken);
            if (action == Action.FAILED) {
                if (!List.of("STARTING", "RUNNING", "UPLOADING_RESULTS").contains(row.jobState())
                        || !row.jobState().equals(row.attemptState())) {
                    throw new LeaseExecutionDeniedException();
                }
                jdbc.update("UPDATE job_attempts SET state = 'FAILED', failure_code = 'EXECUTION_FAILED', "
                        + "completed_at = CURRENT_TIMESTAMP WHERE id = ?", row.attemptId());
                jdbc.update("UPDATE jobs SET state = 'FAILED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", row.jobId());
                jdbc.update("UPDATE gpu_leases SET state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", leaseId);
                jdbc.update("UPDATE provider_nodes SET availability_state = 'OFFLINE', updated_at = CURRENT_TIMESTAMP WHERE id = ?", peer.nodeId());
                event(row, leaseId, "JOB_EXECUTION_FAILED");
                return row.expiresAt();
            }
            if (action == Action.STARTING && "LEASED".equals(row.jobState())
                    && "LEASED".equals(row.attemptState())) {
                jdbc.update("UPDATE job_attempts SET state = 'STARTING' WHERE id = ?", row.attemptId());
                jdbc.update("UPDATE jobs SET state = 'STARTING', updated_at = CURRENT_TIMESTAMP WHERE id = ?", row.jobId());
                jdbc.update("UPDATE gpu_leases SET execution_started_at = CURRENT_TIMESTAMP WHERE id = ?", leaseId);
                event(row, leaseId, "JOB_STARTING");
            } else if (action == Action.RUNNING && "STARTING".equals(row.jobState())
                    && "STARTING".equals(row.attemptState())) {
                jdbc.update("UPDATE job_attempts SET state = 'RUNNING' WHERE id = ?", row.attemptId());
                jdbc.update("UPDATE jobs SET state = 'RUNNING', updated_at = CURRENT_TIMESTAMP WHERE id = ?", row.jobId());
                event(row, leaseId, "JOB_RUNNING");
            } else if (action == Action.RENEW
                    && List.of("STARTING", "RUNNING", "UPLOADING_RESULTS").contains(row.jobState())
                    && row.jobState().equals(row.attemptState())) {
                // No lease can outlive the approved recipe's wall-clock budget.
            } else {
                throw new LeaseExecutionDeniedException();
            }
            Instant started = action == Action.STARTING ? Instant.now() : row.startedAt();
            if (started == null) {
                throw new LeaseExecutionDeniedException();
            }
            Instant hardStop = started.plusSeconds(row.maxRuntimeSeconds());
            Instant extension = Instant.now().plusSeconds(RENEW_SECONDS);
            Instant expires = extension.isBefore(hardStop) ? extension : hardStop;
            if (!expires.isAfter(Instant.now().plusSeconds(5))) {
                throw new LeaseExecutionDeniedException();
            }
            if (jdbc.update("UPDATE gpu_leases SET expires_at = ? WHERE id = ? AND state = 'ACKNOWLEDGED' "
                    + "AND expires_at > clock_timestamp()", Timestamp.from(expires), leaseId) != 1) {
                throw new LeaseExecutionDeniedException();
            }
            return expires;
        });
    }

    public Candidate currentLocked(ProviderPeerIdentity peer, UUID leaseId, UUID commandId, long token) {
        if (peer == null || leaseId == null || commandId == null || token <= 0) {
            throw new LeaseExecutionDeniedException();
        }
        List<Candidate> rows = jdbc.query("""
                SELECT j.id AS job_id, j.state AS job_state, a.id AS attempt_id,
                       a.state AS attempt_state, l.expires_at, l.execution_started_at,
                       r.max_runtime_seconds
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id AND j.request_version = 1
                JOIN workload_recipes r ON r.id = j.recipe_id
                JOIN provider_nodes n ON n.id = l.node_id
                JOIN provider_gpus g ON g.node_id = l.node_id AND g.gpu_uuid = l.gpu_uuid
                JOIN lease_execution_commands c ON c.lease_id = l.id
                JOIN lease_execution_command_staging s ON s.command_id = c.id
                WHERE l.id = ? AND l.node_id = ? AND l.fencing_token = ?
                  AND c.id = ? AND c.node_id = l.node_id AND c.fencing_token = l.fencing_token
                  AND s.lease_id = l.id AND s.node_id = l.node_id AND s.fencing_token = l.fencing_token
                  AND c.image_repository = r.image_repository AND c.image_digest = r.image_digest
                  AND c.max_runtime_seconds = r.max_runtime_seconds
                  AND c.gpu_uuid = l.gpu_uuid AND c.verified_input_prefix = j.input_artifact_prefix
                  AND c.input_manifest_sha256 = j.verified_input_manifest_sha256
                  AND c.input_file_count = j.input_file_count
                  AND c.input_total_bytes = j.input_total_bytes
                  AND j.input_verified_at IS NOT NULL AND r.state = 'APPROVED'
                  AND j.stop_requested_at IS NULL
                  AND n.enrollment_state = 'VERIFIED' AND n.availability_state = 'BUSY'
                  AND g.verification_state = 'VERIFIED' AND g.vram_bytes >= r.min_vram_bytes
                  AND l.state = 'ACKNOWLEDGED' AND l.expires_at > clock_timestamp()
                FOR UPDATE OF l, a, j, n, r, g
                """, (rs, index) -> new Candidate(rs.getObject("job_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("job_state"),
                rs.getString("attempt_state"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("execution_started_at") == null ? null
                        : rs.getTimestamp("execution_started_at").toInstant(),
                rs.getInt("max_runtime_seconds")), leaseId, peer.nodeId(), token, commandId);
        if (rows.size() != 1) {
            throw new LeaseExecutionDeniedException();
        }
        try {
            identities.requireActivePeerLocked(peer);
        } catch (ProviderIdentityDeniedException exception) {
            throw new LeaseExecutionDeniedException();
        }
        return rows.get(0);
    }

    private void event(Candidate row, UUID leaseId, String type) {
        jdbc.update("""
                INSERT INTO outbox_events (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, ?, jsonb_build_object('jobId', CAST(? AS text),
                    'attemptId', CAST(? AS text), 'leaseId', CAST(? AS text)))
                """, UUID.randomUUID(), "lease:" + leaseId + ":" + type,
                row.jobId(), type, row.jobId(), row.attemptId(), leaseId);
    }

    public enum Action { STARTING, RUNNING, RENEW, FAILED }

    public record Candidate(UUID jobId, UUID attemptId, String jobState, String attemptState,
            Instant expiresAt, Instant startedAt, int maxRuntimeSeconds) { }
}
