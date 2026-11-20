package dev.hyperlooms.controlplane.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal persistence workflow; transport authentication and mTLS issuance are separate. */
@Service
public class ProviderEnrollmentService {

    private static final Duration MIN_TTL = Duration.ofMinutes(5);
    private static final Duration MAX_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public ProviderEnrollmentService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Must only be invoked by a trusted operator workflow, never by an unauthenticated agent. */
    public ProviderEnrollmentInvite issueInvite(String ownerSubject, Duration ttl) {
        if (ownerSubject == null || ownerSubject.isBlank() || ownerSubject.length() > 200) {
            throw new IllegalArgumentException("ownerSubject must contain 1-200 characters");
        }
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("invite TTL must be between 5 minutes and 24 hours");
        }

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID inviteId = UUID.randomUUID();
        Instant expiresAt = jdbc.queryForObject("""
                INSERT INTO provider_enrollment_invites
                    (id, owner_subject, token_sha256, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                RETURNING expires_at
                """, (rs, row) -> rs.getTimestamp("expires_at").toInstant(),
                inviteId, ownerSubject, sha256(token), ttl.toSeconds());
        return new ProviderEnrollmentInvite(inviteId, token, expiresAt);
    }

    /** Redemption creates only a PENDING/OFFLINE node; it does not authenticate an agent session. */
    public ProviderEnrollmentResult redeemInvite(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new EnrollmentDeniedException();
        }
        return transactions.execute(status -> redeemLocked(sha256(token)));
    }

    public boolean revokeUnusedInvite(UUID inviteId) {
        if (inviteId == null) {
            throw new IllegalArgumentException("inviteId is required");
        }
        return jdbc.update("""
                UPDATE provider_enrollment_invites SET revoked_at = CURRENT_TIMESTAMP
                WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, inviteId) == 1;
    }

    private ProviderEnrollmentResult redeemLocked(String tokenSha256) {
        List<StoredInvite> matches = jdbc.query("""
                SELECT owner_subject, expires_at > clock_timestamp() AS is_live,
                       node_id, revoked_at IS NOT NULL AS revoked
                FROM provider_enrollment_invites
                WHERE token_sha256 = ? FOR UPDATE
                """, (rs, row) -> new StoredInvite(
                rs.getString("owner_subject"),
                rs.getBoolean("is_live"),
                rs.getObject("node_id", UUID.class),
                rs.getBoolean("revoked")),
                tokenSha256);
        if (matches.isEmpty()) {
            throw new EnrollmentDeniedException();
        }
        StoredInvite invite = matches.get(0);
        if (invite.revoked()) {
            throw new EnrollmentDeniedException();
        }
        if (invite.nodeId() != null) {
            return new ProviderEnrollmentResult(invite.nodeId(), true);
        }
        if (!invite.live()) {
            throw new EnrollmentDeniedException();
        }

        UUID nodeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_nodes (id, owner_subject, enrollment_state, availability_state)
                VALUES (?, ?, 'PENDING', 'OFFLINE')
                """, nodeId, invite.ownerSubject());
        jdbc.update("""
                UPDATE provider_enrollment_invites
                SET consumed_at = CURRENT_TIMESTAMP, node_id = ?
                WHERE token_sha256 = ?
                """, nodeId, tokenSha256);
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, 'PROVIDER_NODE', ?, 'PROVIDER_NODE_PENDING',
                    jsonb_build_object('nodeId', CAST(? AS text)))
                """, UUID.randomUUID(), "provider-node:" + nodeId + ":pending", nodeId, nodeId);
        return new ProviderEnrollmentResult(nodeId, false);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record StoredInvite(String ownerSubject, boolean live, UUID nodeId, boolean revoked) {
    }
}
