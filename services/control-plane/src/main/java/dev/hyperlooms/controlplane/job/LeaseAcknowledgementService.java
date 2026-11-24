package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderIdentityService;
import dev.hyperlooms.controlplane.provider.ProviderIdentityDeniedException;
import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/** Internal mTLS-peer-bound receipt; no agent command or workload execution is wired yet. */
@Service
public class LeaseAcknowledgementService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProviderIdentityService identities;

    public LeaseAcknowledgementService(JdbcTemplate jdbc, TransactionTemplate transactions,
            ProviderIdentityService identities) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identities = identities;
    }

    /** The caller must pass the identity extracted from an authenticated mTLS stream. */
    public LeaseAcknowledgement acknowledge(ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        if (peer == null || leaseId == null || fencingToken <= 0) {
            throw new LeaseAcknowledgementDeniedException();
        }
        return transactions.execute(status -> acknowledgeInTransaction(peer, leaseId, fencingToken));
    }

    private LeaseAcknowledgement acknowledgeInTransaction(
            ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        List<Candidate> matches = jdbc.query("""
                SELECT l.node_id, l.fencing_token, l.state AS lease_state,
                       l.expires_at, l.expires_at > clock_timestamp() AS unexpired,
                       a.state AS attempt_state, j.state AS job_state,
                       n.availability_state
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id
                JOIN provider_nodes n ON n.id = l.node_id
                WHERE l.id = ?
                FOR UPDATE OF l, a, j, n
                """, (rs, row) -> new Candidate(
                rs.getObject("node_id", UUID.class), rs.getLong("fencing_token"),
                rs.getString("lease_state"),
                rs.getObject("expires_at", Timestamp.class).toInstant(),
                rs.getBoolean("unexpired"), rs.getString("attempt_state"),
                rs.getString("job_state"), rs.getString("availability_state")), leaseId);
        if (matches.size() != 1) {
            throw new LeaseAcknowledgementDeniedException();
        }
        Candidate candidate = matches.get(0);
        if (!peer.nodeId().equals(candidate.nodeId()) || fencingToken != candidate.fencingToken()
                || !candidate.unexpired() || !"LEASED".equals(candidate.attemptState())
                || !"LEASED".equals(candidate.jobState())
                || !"BUSY".equals(candidate.availabilityState())
                || !("ISSUED".equals(candidate.leaseState())
                        || "ACKNOWLEDGED".equals(candidate.leaseState()))) {
            throw new LeaseAcknowledgementDeniedException();
        }
        try {
            identities.requireActivePeerLocked(peer);
        } catch (ProviderIdentityDeniedException exception) {
            throw new LeaseAcknowledgementDeniedException();
        }
        if ("ISSUED".equals(candidate.leaseState())) {
            if (jdbc.update("""
                    UPDATE gpu_leases
                    SET state = 'ACKNOWLEDGED', acknowledged_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND state = 'ISSUED' AND expires_at > clock_timestamp()
                    """, leaseId) != 1) {
                throw new LeaseAcknowledgementDeniedException();
            }
            jdbc.update("""
                    INSERT INTO outbox_events
                        (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                    SELECT ?, ?, 'JOB', a.job_id, 'JOB_LEASE_ACKNOWLEDGED',
                        jsonb_build_object('jobId', CAST(a.job_id AS text),
                            'attemptId', CAST(a.id AS text), 'leaseId', CAST(l.id AS text),
                            'nodeId', CAST(l.node_id AS text), 'fencingToken', l.fencing_token)
                    FROM gpu_leases l JOIN job_attempts a ON a.id = l.attempt_id
                    WHERE l.id = ?
                    """, UUID.randomUUID(), "lease:" + leaseId + ":acknowledged", leaseId);
        }
        return new LeaseAcknowledgement(leaseId, candidate.expiresAt());
    }

    private record Candidate(UUID nodeId, long fencingToken, String leaseState, Instant expiresAt,
            boolean unexpired, String attemptState, String jobState, String availabilityState) {
    }
}
