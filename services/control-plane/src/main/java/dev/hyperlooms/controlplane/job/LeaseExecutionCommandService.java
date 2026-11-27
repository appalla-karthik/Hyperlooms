package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/**
 * Atomically persists one immutable command snapshot and outbox event per lease.
 * No network endpoint, artifact grant, or agent runtime consumes it yet.
 */
@Service
public class LeaseExecutionCommandService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LeaseExecutionPlanService plans;

    public LeaseExecutionCommandService(JdbcTemplate jdbc, TransactionTemplate transactions,
            LeaseExecutionPlanService plans) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.plans = plans;
    }

    /** The peer must come from the authenticated mTLS stream. */
    public LeaseExecutionCommand prepareForPeer(
            ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        return transactions.execute(status -> prepareInTransaction(peer, leaseId, fencingToken));
    }

    /** Read only one current, unstaged command; no queue dispatcher consumes the outbox. */
    public Optional<LeaseExecutionDelivery> nextForPeer(ProviderPeerIdentity peer) {
        if (peer == null) {
            return Optional.empty();
        }
        List<UUID> candidates = jdbc.query("""
                SELECT l.id FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id AND j.request_version = 1
                LEFT JOIN lease_execution_command_staging s ON s.lease_id = l.id
                WHERE l.node_id = ? AND l.state = 'ACKNOWLEDGED'
                  AND l.expires_at > clock_timestamp() AND s.lease_id IS NULL
                ORDER BY l.issued_at, l.id LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), peer.nodeId());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID leaseId = candidates.get(0);
        Long token = jdbc.queryForObject(
                "SELECT fencing_token FROM gpu_leases WHERE id = ?", Long.class, leaseId);
        if (token == null) {
            return Optional.empty();
        }
        try {
            LeaseExecutionCommand command = prepareForPeer(peer, leaseId, token);
            LeaseExecutionPlan plan = plans.loadCurrentForPeer(peer, leaseId, token);
            return command.matches(plan)
                    ? Optional.of(new LeaseExecutionDelivery(plan, command))
                    : Optional.empty();
        } catch (LeaseExecutionDeniedException denied) {
            return Optional.empty();
        }
    }

    /** Records only a current, exact command staged by its certificate-bound node. */
    public void acknowledgeStaged(ProviderPeerIdentity peer, UUID leaseId,
            UUID commandId, long fencingToken) {
        transactions.executeWithoutResult(status -> {
            LeaseExecutionPlan plan = plans.loadCurrentForPeer(peer, leaseId, fencingToken);
            List<LeaseExecutionCommand> commands = load(leaseId);
            if (commands.size() != 1 || !commands.get(0).id().equals(commandId)
                    || !commands.get(0).matches(plan)) {
                throw new LeaseExecutionDeniedException();
            }
            jdbc.update("""
                    INSERT INTO lease_execution_command_staging
                        (command_id, lease_id, node_id, fencing_token)
                    VALUES (?, ?, ?, ?) ON CONFLICT (command_id) DO NOTHING
                    """, commandId, leaseId, peer.nodeId(), fencingToken);
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM lease_execution_command_staging
                    WHERE command_id = ? AND lease_id = ? AND node_id = ? AND fencing_token = ?
                    """, Long.class, commandId, leaseId, peer.nodeId(), fencingToken);
            if (count == null || count != 1L) {
                throw new LeaseExecutionDeniedException();
            }
        });
    }

    private LeaseExecutionCommand prepareInTransaction(
            ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        // This obtains row locks and rejects stale credentials, unverified inputs,
        // legacy jobs, revoked GPU reviews, or a changed recipe before any write.
        LeaseExecutionPlan plan = plans.loadCurrentForPeer(peer, leaseId, fencingToken);
        List<LeaseExecutionCommand> existing = load(leaseId);
        if (!existing.isEmpty()) {
            LeaseExecutionCommand command = existing.get(0);
            if (!command.matches(plan)) {
                throw new LeaseExecutionDeniedException();
            }
            return command;
        }

        UUID commandId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO lease_execution_commands
                    (id, lease_id, node_id, gpu_uuid, fencing_token, image_repository, image_digest,
                     max_runtime_seconds, verified_input_prefix, input_manifest_sha256,
                     input_file_count, input_total_bytes)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM gpu_leases
                    WHERE id = ? AND node_id = ? AND fencing_token = ?
                      AND state = 'ACKNOWLEDGED' AND expires_at > clock_timestamp()
                )
                """, commandId, plan.leaseId(), plan.nodeId(), plan.gpuUuid(), plan.fencingToken(),
                plan.imageRepository(), plan.imageDigest(), plan.maxRuntimeSeconds(),
                plan.verifiedInputPrefix(), plan.inputManifestSha256(),
                plan.inputFileCount(), plan.inputTotalBytes(),
                plan.leaseId(), plan.nodeId(), plan.fencingToken());
        if (inserted != 1) {
            throw new LeaseExecutionDeniedException();
        }
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, 'JOB_EXECUTION_COMMAND_PREPARED',
                    jsonb_build_object('jobId', CAST(? AS text),
                        'attemptId', CAST(? AS text), 'leaseId', CAST(? AS text),
                        'commandId', CAST(? AS text), 'fencingToken', ?))
                """, UUID.randomUUID(), "lease:" + plan.leaseId() + ":command-prepared",
                plan.jobId(), plan.jobId(), plan.attemptId(), plan.leaseId(),
                commandId, plan.fencingToken());
        List<LeaseExecutionCommand> created = load(leaseId);
        if (created.size() != 1) {
            throw new IllegalStateException("Prepared execution command was not found");
        }
        return created.get(0);
    }

    private List<LeaseExecutionCommand> load(UUID leaseId) {
        return jdbc.query("""
                SELECT id, lease_id, node_id, gpu_uuid, fencing_token, image_repository,
                       image_digest, max_runtime_seconds, verified_input_prefix,
                       input_manifest_sha256, input_file_count, input_total_bytes,
                       prepared_at
                FROM lease_execution_commands WHERE lease_id = ?
                """, (rs, row) -> new LeaseExecutionCommand(
                rs.getObject("id", UUID.class), rs.getObject("lease_id", UUID.class),
                rs.getObject("node_id", UUID.class), rs.getString("gpu_uuid"),
                rs.getLong("fencing_token"),
                rs.getString("image_repository"), rs.getString("image_digest"),
                rs.getInt("max_runtime_seconds"), rs.getString("verified_input_prefix"),
                rs.getString("input_manifest_sha256"), rs.getInt("input_file_count"),
                rs.getLong("input_total_bytes"),
                rs.getObject("prepared_at", Timestamp.class).toInstant()), leaseId);
    }
}
