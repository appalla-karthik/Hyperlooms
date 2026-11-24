package dev.hyperlooms.controlplane.job;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/** Reads one issued lease for a verified mTLS peer; delivery is repeatable until receipt. */
@Service
public class LeaseOfferService {

    private final JdbcTemplate jdbc;

    public LeaseOfferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Caller must have just authenticated this peer on the agent stream. */
    public Optional<LeaseAssignment> nextIssuedForPeer(ProviderPeerIdentity peer) {
        if (peer == null) {
            return Optional.empty();
        }
        List<LeaseAssignment> offers = jdbc.query("""
                SELECT j.id AS job_id, a.id AS attempt_id, l.id AS lease_id,
                       l.node_id, l.gpu_uuid, l.fencing_token, l.expires_at
                FROM gpu_leases l
                JOIN job_attempts a ON a.id = l.attempt_id
                JOIN jobs j ON j.id = a.job_id
                JOIN provider_nodes n ON n.id = l.node_id
                WHERE l.node_id = ? AND l.state = 'ISSUED'
                  AND l.expires_at > clock_timestamp()
                  AND a.state = 'LEASED' AND j.state = 'LEASED'
                  AND n.enrollment_state = 'VERIFIED' AND n.availability_state = 'BUSY'
                  AND EXISTS (
                      SELECT 1 FROM provider_certificates c
                      WHERE c.node_id = n.id AND c.certificate_sha256 = ?
                        AND c.activated_at IS NOT NULL AND c.revoked_at IS NULL
                        AND c.not_after > clock_timestamp()
                  )
                ORDER BY l.issued_at, l.id
                LIMIT 1
                """, (rs, row) -> new LeaseAssignment(
                rs.getObject("job_id", java.util.UUID.class),
                rs.getObject("attempt_id", java.util.UUID.class),
                rs.getObject("lease_id", java.util.UUID.class),
                rs.getObject("node_id", java.util.UUID.class),
                rs.getString("gpu_uuid"), rs.getLong("fencing_token"),
                rs.getObject("expires_at", Timestamp.class).toInstant()),
                peer.nodeId(), peer.certificateSha256());
        return offers.stream().findFirst();
    }
}
