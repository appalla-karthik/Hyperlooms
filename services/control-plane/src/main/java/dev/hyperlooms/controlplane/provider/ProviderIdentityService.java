package dev.hyperlooms.controlplane.provider;

import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal credential persistence; the caller must establish trusted TLS/operator context. */
@Service
public class ProviderIdentityService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ProviderIdentityService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Register an operator-approved, CA-issued leaf certificate for a pending node. */
    public void bindExpectedCertificate(UUID nodeId, X509Certificate certificate) {
        ProviderPeerIdentity identity = ProviderPeerIdentity.fromCertificate(certificate);
        if (nodeId == null || !nodeId.equals(identity.nodeId())) {
            throw new ProviderIdentityDeniedException();
        }
        transactions.executeWithoutResult(status -> {
            List<String> states = jdbc.queryForList(
                    "SELECT enrollment_state FROM provider_nodes WHERE id = ? FOR UPDATE",
                    String.class, nodeId);
            if (states.size() != 1 || !"PENDING".equals(states.get(0))) {
                throw new ProviderIdentityDeniedException();
            }
            jdbc.update("""
                    INSERT INTO provider_certificates
                        (id, node_id, certificate_sha256, not_after)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID(), nodeId, identity.certificateSha256(),
                    Timestamp.from(identity.notAfter()));
        });
    }

    /** Call only with the peer certificate from a successfully authenticated mTLS session. */
    public UUID activateFromAuthenticatedPeer(ProviderPeerIdentity peer) {
        if (peer == null) {
            throw new ProviderIdentityDeniedException();
        }
        return transactions.execute(status -> {
            Credential credential = lockCredential(peer);
            if (!credential.unexpired()) {
                throw new ProviderIdentityDeniedException();
            }
            if ("VERIFIED".equals(credential.nodeState()) && credential.activated()) {
                return peer.nodeId();
            }
            if (!"PENDING".equals(credential.nodeState()) || credential.activated()) {
                throw new ProviderIdentityDeniedException();
            }
            jdbc.update("""
                    UPDATE provider_certificates SET activated_at = CURRENT_TIMESTAMP
                    WHERE node_id = ? AND certificate_sha256 = ? AND activated_at IS NULL
                    """, peer.nodeId(), peer.certificateSha256());
            jdbc.update("""
                    UPDATE provider_nodes
                    SET enrollment_state = 'VERIFIED', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, peer.nodeId());
            writeNodeEvent(peer.nodeId(), "PROVIDER_IDENTITY_ACTIVATED");
            return peer.nodeId();
        });
    }

    /** Used within the capability report transaction; locks against concurrent revocation. */
    void requireActivePeerLocked(ProviderPeerIdentity peer) {
        if (peer == null) {
            throw new ProviderIdentityDeniedException();
        }
        Credential credential = lockCredential(peer);
        if (!credential.unexpired() || !credential.activated()
                || !"VERIFIED".equals(credential.nodeState())) {
            throw new ProviderIdentityDeniedException();
        }
    }

    /** An operator can revoke a node; a revoked node cannot be reactivated by its old certificate. */
    public boolean revokeNode(UUID nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId is required");
        }
        return transactions.execute(status -> {
            List<String> states = jdbc.queryForList(
                    "SELECT enrollment_state FROM provider_nodes WHERE id = ? FOR UPDATE",
                    String.class, nodeId);
            if (states.isEmpty() || "REVOKED".equals(states.get(0))) {
                return false;
            }
            jdbc.update("""
                    UPDATE provider_certificates SET revoked_at = CURRENT_TIMESTAMP
                    WHERE node_id = ? AND revoked_at IS NULL
                    """, nodeId);
            jdbc.update("""
                    UPDATE provider_gpus
                    SET verification_state = 'REJECTED', verified_at = NULL
                    WHERE node_id = ?
                    """, nodeId);
            jdbc.update("""
                    UPDATE provider_nodes
                    SET enrollment_state = 'REVOKED', availability_state = 'OFFLINE',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, nodeId);
            writeNodeEvent(nodeId, "PROVIDER_NODE_REVOKED");
            return true;
        });
    }

    private Credential lockCredential(ProviderPeerIdentity peer) {
        List<String> states = jdbc.queryForList(
                "SELECT enrollment_state FROM provider_nodes WHERE id = ? FOR UPDATE",
                String.class, peer.nodeId());
        if (states.size() != 1) {
            throw new ProviderIdentityDeniedException();
        }
        List<Credential> matches = jdbc.query("""
                SELECT c.activated_at IS NOT NULL AS activated,
                       c.not_after > clock_timestamp() AS unexpired
                FROM provider_certificates c
                WHERE c.node_id = ? AND c.certificate_sha256 = ? AND c.revoked_at IS NULL
                FOR UPDATE
                """, (rs, row) -> new Credential(
                states.get(0),
                rs.getBoolean("activated"),
                rs.getBoolean("unexpired")),
                peer.nodeId(), peer.certificateSha256());
        if (matches.size() != 1) {
            throw new ProviderIdentityDeniedException();
        }
        return matches.get(0);
    }

    private void writeNodeEvent(UUID nodeId, String eventType) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'PROVIDER_NODE', ?, ?,
                    jsonb_build_object('nodeId', CAST(? AS text)))
                """, UUID.randomUUID(), "provider-node:" + nodeId + ":" + eventType,
                nodeId, eventType, nodeId);
    }

    private record Credential(String nodeState, boolean activated, boolean unexpired) {
    }
}
