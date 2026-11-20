package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class ProviderEnrollmentServiceIntegrationTest {

    @Autowired
    private ProviderEnrollmentService enrollment;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> inviteIds = new ArrayList<>();

    @AfterEach
    void removeTestRecords() {
        for (UUID inviteId : inviteIds) {
            List<UUID> nodeIds = jdbc.queryForList(
                    "SELECT node_id FROM provider_enrollment_invites WHERE id = ? AND node_id IS NOT NULL",
                    UUID.class, inviteId);
            jdbc.update("DELETE FROM provider_enrollment_invites WHERE id = ?", inviteId);
            for (UUID nodeId : nodeIds) {
                jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", nodeId);
                jdbc.update("DELETE FROM provider_nodes WHERE id = ?", nodeId);
            }
        }
    }

    @Test
    void inviteIsHashedAndRedemptionCreatesOnlyPendingOfflineNode() {
        ProviderEnrollmentInvite invite = invite();
        String storedHash = jdbc.queryForObject(
                "SELECT token_sha256 FROM provider_enrollment_invites WHERE id = ?",
                String.class, invite.inviteId());

        assertNotEquals(invite.token(), storedHash);
        assertFalse(invite.toString().contains(invite.token()));

        ProviderEnrollmentResult result = enrollment.redeemInvite(invite.token());
        ProviderEnrollmentResult replay = enrollment.redeemInvite(invite.token());

        assertFalse(result.replayed());
        assertTrue(replay.replayed());
        assertEquals(result.nodeId(), replay.nodeId());
        assertEquals("PENDING/OFFLINE", jdbc.queryForObject("""
                SELECT enrollment_state || '/' || availability_state
                FROM provider_nodes WHERE id = ?
                """, String.class, result.nodeId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'PROVIDER_NODE_PENDING'", result.nodeId()));
    }

    @Test
    void revokedExpiredAndMalformedInvitesCannotEnroll() {
        ProviderEnrollmentInvite revoked = invite();
        ProviderEnrollmentInvite expired = invite();

        assertTrue(enrollment.revokeUnusedInvite(revoked.inviteId()));
        assertFalse(enrollment.revokeUnusedInvite(revoked.inviteId()));
        jdbc.update("""
                UPDATE provider_enrollment_invites
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                WHERE id = ?
                """, expired.inviteId());

        assertThrows(EnrollmentDeniedException.class, () -> enrollment.redeemInvite(revoked.token()));
        assertThrows(EnrollmentDeniedException.class, () -> enrollment.redeemInvite(expired.token()));
        assertThrows(EnrollmentDeniedException.class, () -> enrollment.redeemInvite("invalid"));
        assertEquals(0L, count("SELECT COUNT(*) FROM provider_enrollment_invites "
                + "WHERE id = ? AND node_id IS NOT NULL", revoked.inviteId()));
        assertEquals(0L, count("SELECT COUNT(*) FROM provider_enrollment_invites "
                + "WHERE id = ? AND node_id IS NOT NULL", expired.inviteId()));
    }

    @Test
    void simultaneousRedemptionCreatesOneNode() throws Exception {
        ProviderEnrollmentInvite invite = invite();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProviderEnrollmentResult> first = executor.submit(() -> {
                start.await();
                return enrollment.redeemInvite(invite.token());
            });
            Future<ProviderEnrollmentResult> second = executor.submit(() -> {
                start.await();
                return enrollment.redeemInvite(invite.token());
            });
            start.countDown();

            ProviderEnrollmentResult firstResult = first.get();
            ProviderEnrollmentResult secondResult = second.get();
            assertEquals(firstResult.nodeId(), secondResult.nodeId());
            assertTrue(firstResult.replayed() != secondResult.replayed());
            assertEquals(1L, count("SELECT COUNT(*) FROM provider_nodes WHERE id = ?",
                    firstResult.nodeId()));
        } finally {
            executor.shutdownNow();
        }
    }

    private ProviderEnrollmentInvite invite() {
        ProviderEnrollmentInvite invite = enrollment.issueInvite(
                "integration:" + UUID.randomUUID(), Duration.ofMinutes(30));
        inviteIds.add(invite.inviteId());
        return invite;
    }

    private long count(String query, UUID argument) {
        Long result = jdbc.queryForObject(query, Long.class, argument);
        return result == null ? 0 : result;
    }
}
