package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class ProviderIdentityCapabilityIntegrationTest {

    @Autowired
    private ProviderEnrollmentService enrollment;

    @Autowired
    private ProviderIdentityService identities;

    @Autowired
    private ProviderCapabilityService capabilities;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> inviteIds = new ArrayList<>();

    @AfterEach
    void removeTestRecords() {
        for (UUID inviteId : inviteIds) {
            List<UUID> nodeIds = jdbc.queryForList(
                    "SELECT node_id FROM provider_enrollment_invites WHERE id = ? AND node_id IS NOT NULL",
                    UUID.class, inviteId);
            for (UUID nodeId : nodeIds) {
                jdbc.update("DELETE FROM provider_gpus WHERE node_id = ?", nodeId);
                jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", nodeId);
                jdbc.update("DELETE FROM provider_certificates WHERE node_id = ?", nodeId);
            }
            jdbc.update("DELETE FROM provider_enrollment_invites WHERE id = ?", inviteId);
            for (UUID nodeId : nodeIds) {
                jdbc.update("DELETE FROM provider_nodes WHERE id = ?", nodeId);
            }
        }
    }

    @Test
    void expectedCertificateActivatesOnlyItsOwnPendingNode() throws Exception {
        UUID nodeId = pendingNode();
        X509Certificate certificate = certificate(nodeId, "original");
        ProviderPeerIdentity peer = ProviderPeerIdentity.fromCertificate(certificate);
        ProviderCapabilityReport report = report(nodeId, gpu("GPU-1", 8589934592L));

        identities.bindExpectedCertificate(nodeId, certificate);
        assertEquals("PENDING", nodeState(nodeId));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> capabilities.recordFromAuthenticatedPeer(peer, report));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> identities.activateFromAuthenticatedPeer(
                        ProviderPeerIdentity.fromCertificate(certificate(nodeId, "different"))));

        assertEquals(nodeId, identities.activateFromAuthenticatedPeer(peer));
        assertEquals(nodeId, identities.activateFromAuthenticatedPeer(peer));
        assertEquals("VERIFIED/OFFLINE", jdbc.queryForObject("""
                SELECT enrollment_state || '/' || availability_state
                FROM provider_nodes WHERE id = ?
                """, String.class, nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'PROVIDER_IDENTITY_ACTIVATED'", nodeId));
    }

    @Test
    void authenticatedReportsRemainUnverifiedAndChangesInvalidatePriorVerification() throws Exception {
        UUID nodeId = pendingNode();
        X509Certificate certificate = certificate(nodeId, "original");
        ProviderPeerIdentity peer = ProviderPeerIdentity.fromCertificate(certificate);
        identities.bindExpectedCertificate(nodeId, certificate);
        identities.activateFromAuthenticatedPeer(peer);

        GpuCapabilityReport gpu1 = gpu("GPU-1", 8589934592L);
        GpuCapabilityReport gpu2 = gpu("GPU-2", 8589934592L);
        capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, gpu1, gpu2));
        assertEquals("REPORTED", gpuState(nodeId, "GPU-1"));
        assertEquals("OFFLINE", jdbc.queryForObject(
                "SELECT availability_state FROM provider_nodes WHERE id = ?", String.class, nodeId));

        jdbc.update("""
                UPDATE provider_gpus SET verification_state = 'VERIFIED', verified_at = CURRENT_TIMESTAMP
                WHERE node_id = ?
                """, nodeId);
        capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, gpu1, gpu2));
        assertEquals("VERIFIED", gpuState(nodeId, "GPU-1"));

        capabilities.recordFromAuthenticatedPeer(peer,
                report(nodeId, gpu("GPU-1", 17179869184L)));
        assertEquals("REPORTED", gpuState(nodeId, "GPU-1"));
        assertEquals("REJECTED", gpuState(nodeId, "GPU-2"));
        assertEquals(0L, count("SELECT COUNT(*) FROM provider_gpus "
                + "WHERE node_id = ? AND verified_at IS NOT NULL", nodeId));

        assertThrows(ProviderIdentityDeniedException.class, () -> capabilities.recordFromAuthenticatedPeer(
                peer, report(UUID.randomUUID(), gpu1)));
        assertTrue(identities.revokeNode(nodeId));
        assertFalse(identities.revokeNode(nodeId));
        assertEquals("REVOKED", nodeState(nodeId));
        assertEquals("REJECTED", gpuState(nodeId, "GPU-1"));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, gpu1)));
    }

    @Test
    void certificateSanCannotBeBoundToDifferentNode() throws Exception {
        UUID nodeId = pendingNode();
        X509Certificate wrong = certificate(UUID.randomUUID(), "wrong");

        assertThrows(ProviderIdentityDeniedException.class,
                () -> identities.bindExpectedCertificate(nodeId, wrong));
        assertEquals(0L, count("SELECT COUNT(*) FROM provider_certificates WHERE node_id = ?", nodeId));
    }

    private UUID pendingNode() {
        ProviderEnrollmentInvite invite = enrollment.issueInvite(
                "integration:" + UUID.randomUUID(), Duration.ofMinutes(30));
        inviteIds.add(invite.inviteId());
        return enrollment.redeemInvite(invite.token()).nodeId();
    }

    private X509Certificate certificate(UUID nodeId, String serial) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:" + nodeId)));
        when(certificate.getEncoded()).thenReturn(
                ("test-cert-" + nodeId + "-" + serial).getBytes(StandardCharsets.US_ASCII));
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
        return certificate;
    }

    private ProviderCapabilityReport report(UUID nodeId, GpuCapabilityReport... gpus) {
        return new ProviderCapabilityReport(nodeId, 1, "phase-one-test", List.of(gpus));
    }

    private GpuCapabilityReport gpu(String gpuUuid, long vramBytes) {
        return new GpuCapabilityReport(
                gpuUuid, "Test GPU", vramBytes, "test-driver", "test-cuda");
    }

    private String nodeState(UUID nodeId) {
        return jdbc.queryForObject(
                "SELECT enrollment_state FROM provider_nodes WHERE id = ?", String.class, nodeId);
    }

    private String gpuState(UUID nodeId, String gpuUuid) {
        return jdbc.queryForObject(
                "SELECT verification_state FROM provider_gpus WHERE node_id = ? AND gpu_uuid = ?",
                String.class, nodeId, gpuUuid);
    }

    private long count(String sql, UUID nodeId) {
        Long value = jdbc.queryForObject(sql, Long.class, nodeId);
        return value == null ? 0 : value;
    }
}
