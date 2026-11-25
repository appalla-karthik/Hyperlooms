package dev.hyperlooms.controlplane.job;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.hyperlooms.controlplane.provider.ProviderIdentityDeniedException;
import dev.hyperlooms.controlplane.provider.ProviderIdentityService;
import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/** Internal-only, certificate- and fencing-bound lifecycle; no agent RPC calls it yet. */
@Service
public class LeaseExecutionStateService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProviderIdentityService identities;

    public LeaseExecutionStateService(JdbcTemplate jdbc, TransactionTemplate transactions,
            ProviderIdentityService identities) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identities = identities;
    }

    public void markStarting(ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        transition(peer, leaseId, fencingToken, "LEASED", "STARTING", "JOB_STARTING");
    }

    public void markRunning(ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        transition(peer, leaseId, fencingToken, "STARTING", "RUNNING", "JOB_RUNNING");
    }

    private void transition(ProviderPeerIdentity peer, UUID leaseId, long fencingToken,
            String from, String to, String eventType) {
        requireArguments(peer, leaseId, fencingToken);
        transactions.executeWithoutResult(status -> {
            Candidate candidate = lockedCandidate(leaseId);
            requireCurrentPeer(peer, fencingToken, candidate);
            if (!"ACKNOWLEDGED".equals(candidate.leaseState()) || !candidate.unexpired()
                    || !"BUSY".equals(candidate.availabilityState())) {
                throw new LeaseExecutionDeniedException();
            }
            if (to.equals(candidate.attemptState()) && to.equals(candidate.jobState())) {
                return;
            }
            if (!from.equals(candidate.attemptState()) || !from.equals(candidate.jobState())) {
                throw new LeaseExecutionDeniedException();
            }
            JobState.valueOf(from).requireTransitionTo(JobState.valueOf(to));
            if (jdbc.update("UPDATE job_attempts SET state = ? WHERE id = ? AND state = ? "
                    + "AND EXISTS (SELECT 1 FROM gpu_leases WHERE id = ? "
                    + "AND state = 'ACKNOWLEDGED' AND expires_at > clock_timestamp())",
                    to, candidate.attemptId(), from, leaseId) != 1
                    || jdbc.update("UPDATE jobs SET state = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND state = ? "
                            + "AND EXISTS (SELECT 1 FROM gpu_leases WHERE id = ? "
                            + "AND state = 'ACKNOWLEDGED' AND expires_at > clock_timestamp())",
                            to, candidate.jobId(), from, leaseId) != 1) {
                throw new LeaseExecutionDeniedException();
            }
            writeEvent(candidate, leaseId, eventType);
        });
    }

    /** Explicit failure quarantines the node; success requires a future artifact-verification path. */
    public void markFailed(ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        requireArguments(peer, leaseId, fencingToken);
        transactions.executeWithoutResult(status -> {
            Candidate candidate = lockedCandidate(leaseId);
            requireCurrentPeer(peer, fencingToken, candidate);
            if ("COMPLETED".equals(candidate.leaseState())
                    && "FAILED".equals(candidate.attemptState())
                    && "FAILED".equals(candidate.jobState())
                    && "EXECUTION_FAILED".equals(candidate.failureCode())
                    && "OFFLINE".equals(candidate.availabilityState())) {
                return;
            }
            if (!"ACKNOWLEDGED".equals(candidate.leaseState()) || !candidate.unexpired()
                    || !"BUSY".equals(candidate.availabilityState())
                    || !("STARTING".equals(candidate.attemptState())
                            || "RUNNING".equals(candidate.attemptState()))
                    || !candidate.attemptState().equals(candidate.jobState())) {
                throw new LeaseExecutionDeniedException();
            }
            JobState.valueOf(candidate.jobState()).requireTransitionTo(JobState.FAILED);
            if (jdbc.update("UPDATE job_attempts SET state = 'FAILED', "
                    + "failure_code = 'EXECUTION_FAILED', completed_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND state = ?", candidate.attemptId(), candidate.attemptState()) != 1
                    || jdbc.update("UPDATE jobs SET state = 'FAILED', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ? AND state = ?", candidate.jobId(), candidate.jobState()) != 1
                    || jdbc.update("UPDATE gpu_leases SET state = 'COMPLETED', "
                            + "completed_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'ACKNOWLEDGED' "
                            + "AND expires_at > clock_timestamp()", leaseId) != 1
                    || jdbc.update("UPDATE provider_nodes SET availability_state = 'OFFLINE', "
                            + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND availability_state = 'BUSY'",
                            candidate.nodeId()) != 1) {
                throw new LeaseExecutionDeniedException();
            }
            writeEvent(candidate, leaseId, "JOB_EXECUTION_FAILED");
        });
    }

    private Candidate lockedCandidate(UUID leaseId) {
        List<Candidate> matches = jdbc.query("""
                SELECT l.node_id, l.fencing_token, l.state AS lease_state,
                       l.expires_at > clock_timestamp() AS unexpired,
                       a.id AS attempt_id, a.state AS attempt_state, a.failure_code,
                       j.id AS job_id, j.state AS job_state, n.availability_state
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id
                JOIN provider_nodes n ON n.id = l.node_id
                WHERE l.id = ?
                FOR UPDATE OF l, a, j, n
                """, (rs, row) -> new Candidate(
                rs.getObject("node_id", UUID.class), rs.getLong("fencing_token"),
                rs.getString("lease_state"), rs.getBoolean("unexpired"),
                rs.getObject("attempt_id", UUID.class), rs.getString("attempt_state"),
                rs.getString("failure_code"),
                rs.getObject("job_id", UUID.class), rs.getString("job_state"),
                rs.getString("availability_state")), leaseId);
        if (matches.size() != 1) {
            throw new LeaseExecutionDeniedException();
        }
        return matches.get(0);
    }

    private void requireCurrentPeer(ProviderPeerIdentity peer, long fencingToken, Candidate candidate) {
        if (!peer.nodeId().equals(candidate.nodeId()) || fencingToken != candidate.fencingToken()) {
            throw new LeaseExecutionDeniedException();
        }
        try {
            identities.requireActivePeerLocked(peer);
        } catch (ProviderIdentityDeniedException exception) {
            throw new LeaseExecutionDeniedException();
        }
    }

    private static void requireArguments(ProviderPeerIdentity peer, UUID leaseId, long fencingToken) {
        if (peer == null || leaseId == null || fencingToken <= 0) {
            throw new LeaseExecutionDeniedException();
        }
    }

    private void writeEvent(Candidate candidate, UUID leaseId, String eventType) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'JOB', ?, ?,
                    jsonb_build_object('jobId', CAST(? AS text), 'attemptId', CAST(? AS text),
                        'leaseId', CAST(? AS text), 'nodeId', CAST(? AS text),
                        'fencingToken', ?))
                """, UUID.randomUUID(), "lease:" + leaseId + ":" + eventType,
                candidate.jobId(), eventType, candidate.jobId(), candidate.attemptId(),
                leaseId, candidate.nodeId(), candidate.fencingToken());
    }

    private record Candidate(UUID nodeId, long fencingToken, String leaseState,
            boolean unexpired, UUID attemptId, String attemptState, String failureCode,
            UUID jobId, String jobState, String availabilityState) {
    }
}
