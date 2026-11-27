package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderIdentityDeniedException;
import dev.hyperlooms.controlplane.provider.ProviderIdentityService;
import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/**
 * Reads the only work that could later become an executable command. This has
 * no gRPC/HTTP caller; it neither grants S3 access nor starts a container.
 */
@Service
public class LeaseExecutionPlanService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProviderIdentityService identities;

    public LeaseExecutionPlanService(JdbcTemplate jdbc, TransactionTemplate transactions,
            ProviderIdentityService identities) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identities = identities;
    }

    /** Caller must supply the authenticated mTLS peer, not an agent-claimed node ID. */
    public LeaseExecutionPlan loadCurrentForPeer(
            ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        if (peer == null || leaseId == null || fencingToken <= 0) {
            throw new LeaseExecutionDeniedException();
        }
        return transactions.execute(status -> loadInTransaction(peer, leaseId, fencingToken));
    }

    private LeaseExecutionPlan loadInTransaction(
            ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        List<LeaseExecutionPlan> plans = jdbc.query("""
                SELECT j.id AS job_id, a.id AS attempt_id, l.id AS lease_id,
                       l.node_id, l.gpu_uuid, l.fencing_token, l.expires_at,
                       r.image_repository, r.image_digest, r.max_runtime_seconds,
                       j.input_artifact_prefix, j.input_manifest_sha256,
                       j.input_file_count, j.input_total_bytes
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id AND a.node_id = l.node_id
                JOIN jobs j ON j.id = a.job_id
                JOIN workload_recipes r ON r.id = j.recipe_id
                JOIN provider_nodes n ON n.id = l.node_id
                JOIN provider_gpus g ON g.node_id = l.node_id AND g.gpu_uuid = l.gpu_uuid
                WHERE l.id = ? AND l.node_id = ? AND l.fencing_token = ?
                  AND l.state = 'ACKNOWLEDGED' AND l.expires_at > clock_timestamp()
                  AND a.state = 'LEASED' AND j.state = 'LEASED'
                  AND j.request_version = 1 AND j.input_verified_at IS NOT NULL
                  AND j.input_manifest_sha256 = j.verified_input_manifest_sha256
                  AND j.input_artifact_prefix LIKE 'jobs/' || j.id::text || '/verified/%'
                  AND j.input_file_count IS NOT NULL AND j.input_total_bytes IS NOT NULL
                  AND r.state = 'APPROVED' AND r.approved_at IS NOT NULL
                  AND n.enrollment_state = 'VERIFIED' AND n.availability_state = 'BUSY'
                  AND g.verification_state = 'VERIFIED' AND g.vram_bytes >= r.min_vram_bytes
                FOR UPDATE OF l, a, j, n, r, g
                """, (rs, row) -> new LeaseExecutionPlan(
                rs.getObject("job_id", UUID.class), rs.getObject("attempt_id", UUID.class),
                rs.getObject("lease_id", UUID.class), rs.getObject("node_id", UUID.class),
                rs.getString("gpu_uuid"), rs.getLong("fencing_token"),
                rs.getObject("expires_at", Timestamp.class).toInstant(),
                rs.getString("image_repository"), rs.getString("image_digest"),
                rs.getInt("max_runtime_seconds"), rs.getString("input_artifact_prefix"),
                rs.getString("input_manifest_sha256"), rs.getInt("input_file_count"),
                rs.getLong("input_total_bytes")), leaseId, peer.nodeId(), fencingToken);
        if (plans.size() != 1) {
            throw new LeaseExecutionDeniedException();
        }
        try {
            identities.requireActivePeerLocked(peer);
        } catch (ProviderIdentityDeniedException exception) {
            throw new LeaseExecutionDeniedException();
        }
        return plans.get(0);
    }
}
