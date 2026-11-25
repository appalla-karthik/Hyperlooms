package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ProviderHardwareReviewIntegrationTest {

    private static final String GPU_UUID = "GPU-00000000-1111-2222-3333-444444444444";
    private static final String EVIDENCE_SHA256 = "a".repeat(64);

    @Autowired
    private ProviderEnrollmentService enrollment;

    @Autowired
    private ProviderIdentityService identities;

    @Autowired
    private ProviderCapabilityService capabilities;

    @Autowired
    private ProviderHardwareReviewService reviews;

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
                jdbc.update("DELETE FROM provider_gpu_reviews WHERE node_id = ?", nodeId);
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
    void reviewedReportIsAuditedButDoesNotEnableSchedulingAndChangedReportNeedsNewReview()
            throws Exception {
        UUID nodeId = connectedNode();
        ProviderPeerIdentity peer = peer(nodeId);
        capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, 8589934592L));
        ProviderHardwareReviewService.Candidate candidate = reviews.inspect(nodeId, GPU_UUID);
        assertEquals("REPORTED", candidate.state());
        assertEquals(64, candidate.reportSha256().length());

        reviews.approve(nodeId, GPU_UUID, candidate.reportSha256(), EVIDENCE_SHA256,
                "operator:hardware-review-test");
        assertEquals("VERIFIED", gpuState(nodeId));
        assertEquals("OFFLINE", nodeAvailability(nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM provider_gpu_reviews WHERE node_id = ?", nodeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'PROVIDER_GPU_REVIEW_APPROVED'", nodeId));
        assertThrows(ProviderHardwareReviewDeniedException.class,
                () -> reviews.approve(nodeId, GPU_UUID, candidate.reportSha256(),
                        EVIDENCE_SHA256, "operator:hardware-review-test"));

        capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, 8589934592L));
        assertEquals("VERIFIED", gpuState(nodeId));
        capabilities.recordFromAuthenticatedPeer(peer, report(nodeId, 17179869184L));
        assertEquals("REPORTED", gpuState(nodeId));
        assertThrows(ProviderHardwareReviewDeniedException.class,
                () -> reviews.approve(nodeId, GPU_UUID, candidate.reportSha256(),
                        EVIDENCE_SHA256, "operator:hardware-review-test"));
        assertEquals(1L, count("SELECT COUNT(*) FROM provider_gpu_reviews WHERE node_id = ?", nodeId));
        assertEquals("OFFLINE", nodeAvailability(nodeId));
    }

    @Test
    void staleOrRevokedNodeAndMalformedEvidenceCannotBeApproved() throws Exception {
        UUID nodeId = connectedNode();
        capabilities.recordFromAuthenticatedPeer(peer(nodeId), report(nodeId, 8589934592L));
        String hash = reviews.inspect(nodeId, GPU_UUID).reportSha256();

        assertThrows(IllegalArgumentException.class,
                () -> reviews.approve(nodeId, GPU_UUID, hash, "not-a-digest", "operator:test"));
        jdbc.update("UPDATE provider_nodes SET last_seen_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes' "
                + "WHERE id = ?", nodeId);
        assertThrows(ProviderHardwareReviewDeniedException.class,
                () -> reviews.approve(nodeId, GPU_UUID, hash, EVIDENCE_SHA256, "operator:test"));
        capabilities.recordHeartbeatFromAuthenticatedPeer(peer(nodeId));
        assertTrue(identities.revokeNode(nodeId));
        assertThrows(ProviderHardwareReviewDeniedException.class,
                () -> reviews.approve(nodeId, GPU_UUID, hash, EVIDENCE_SHA256, "operator:test"));
        assertEquals(0L, count("SELECT COUNT(*) FROM provider_gpu_reviews WHERE node_id = ?", nodeId));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'PROVIDER_GPU_REVIEW_APPROVED'", nodeId));
    }

    private UUID connectedNode() throws Exception {
        ProviderEnrollmentInvite invite = enrollment.issueInvite(
                "integration:" + UUID.randomUUID(), Duration.ofMinutes(30));
        inviteIds.add(invite.inviteId());
        UUID nodeId = enrollment.redeemInvite(invite.token()).nodeId();
        X509Certificate certificate = certificate(nodeId);
        identities.bindExpectedCertificate(nodeId, certificate);
        identities.activateFromAuthenticatedPeer(ProviderPeerIdentity.fromCertificate(certificate));
        return nodeId;
    }

    private ProviderPeerIdentity peer(UUID nodeId) throws Exception {
        return ProviderPeerIdentity.fromCertificate(certificate(nodeId));
    }

    private X509Certificate certificate(UUID nodeId) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:" + nodeId)));
        when(certificate.getEncoded()).thenReturn(
                ("review-test-cert-" + nodeId).getBytes(StandardCharsets.US_ASCII));
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
        return certificate;
    }

    private ProviderCapabilityReport report(UUID nodeId, long vramBytes) {
        return new ProviderCapabilityReport(nodeId, 1, "phase-one-test", List.of(
                new GpuCapabilityReport(GPU_UUID, "Test GPU", vramBytes,
                        "test-driver", "test-cuda")));
    }

    private String gpuState(UUID nodeId) {
        return jdbc.queryForObject("SELECT verification_state FROM provider_gpus "
                + "WHERE node_id = ? AND gpu_uuid = ?", String.class, nodeId, GPU_UUID);
    }

    private String nodeAvailability(UUID nodeId) {
        return jdbc.queryForObject("SELECT availability_state FROM provider_nodes WHERE id = ?",
                String.class, nodeId);
    }

    private long count(String sql, UUID nodeId) {
        Long value = jdbc.queryForObject(sql, Long.class, nodeId);
        return value == null ? 0 : value;
    }
}
