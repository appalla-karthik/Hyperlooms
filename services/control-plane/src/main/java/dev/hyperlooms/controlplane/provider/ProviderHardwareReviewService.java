package dev.hyperlooms.controlplane.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Trusted local-operator review only; this does not attest hardware or enable scheduling. */
@Service
public class ProviderHardwareReviewService {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern GPU_UUID = Pattern.compile(
            "^GPU-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ProviderHardwareReviewService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** A read-only snapshot for a human operator to compare with independent host evidence. */
    public Candidate inspect(UUID nodeId, String gpuUuid) {
        requireIdentity(nodeId, gpuUuid);
        List<GpuSnapshot> rows = gpuRows(nodeId, gpuUuid, false);
        if (rows.size() != 1) {
            throw new ProviderHardwareReviewDeniedException();
        }
        return rows.get(0).candidate(nodeId);
    }

    /** Record a manually reviewed evidence digest for precisely this reported GPU snapshot. */
    public void approve(UUID nodeId, String gpuUuid, String expectedReportSha256,
            String evidenceSha256, String reviewerSubject) {
        requireIdentity(nodeId, gpuUuid);
        if (!validSha256(expectedReportSha256) || !validSha256(evidenceSha256)
                || reviewerSubject == null || reviewerSubject.isBlank()
                || reviewerSubject.length() > 200 || reviewerSubject.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("review requires report/evidence SHA-256 and reviewer subject");
        }
        transactions.executeWithoutResult(status -> {
            List<NodeSnapshot> nodes = jdbc.query("""
                    SELECT enrollment_state, availability_state,
                           last_seen_at > clock_timestamp() - INTERVAL '90 seconds' AS fresh
                    FROM provider_nodes WHERE id = ? FOR UPDATE
                    """, (rs, row) -> new NodeSnapshot(
                    rs.getString("enrollment_state"), rs.getString("availability_state"),
                    rs.getBoolean("fresh")), nodeId);
            if (nodes.size() != 1 || !nodes.get(0).eligible()) {
                throw new ProviderHardwareReviewDeniedException();
            }
            Integer activeCertificates = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM provider_certificates
                    WHERE node_id = ? AND activated_at IS NOT NULL AND revoked_at IS NULL
                      AND not_after > clock_timestamp()
                    """, Integer.class, nodeId);
            if (activeCertificates == null || activeCertificates != 1) {
                throw new ProviderHardwareReviewDeniedException();
            }
            List<GpuSnapshot> gpus = gpuRows(nodeId, gpuUuid, true);
            if (gpus.size() != 1 || !"REPORTED".equals(gpus.get(0).state())
                    || !expectedReportSha256.equals(gpus.get(0).candidate(nodeId).reportSha256())) {
                throw new ProviderHardwareReviewDeniedException();
            }
            if (jdbc.update("""
                    UPDATE provider_gpus
                    SET verification_state = 'VERIFIED', verified_at = CURRENT_TIMESTAMP
                    WHERE node_id = ? AND gpu_uuid = ? AND verification_state = 'REPORTED'
                    """, nodeId, gpuUuid) != 1) {
                throw new ProviderHardwareReviewDeniedException();
            }
            jdbc.update("""
                    INSERT INTO provider_gpu_reviews
                        (id, node_id, gpu_uuid, report_sha256, evidence_sha256,
                         reviewer_subject, decision)
                    VALUES (?, ?, ?, ?, ?, ?, 'APPROVED')
                    """, UUID.randomUUID(), nodeId, gpuUuid, expectedReportSha256,
                    evidenceSha256, reviewerSubject);
            UUID eventId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO outbox_events
                        (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, 'PROVIDER_NODE', ?, 'PROVIDER_GPU_REVIEW_APPROVED',
                        jsonb_build_object('nodeId', CAST(? AS text), 'gpuUuid', ?,
                            'reportSha256', ?, 'evidenceSha256', ?, 'reviewer', ?))
                    """, eventId, "provider-node:" + nodeId + ":gpu-review:" + eventId,
                    nodeId, nodeId, gpuUuid, expectedReportSha256, evidenceSha256, reviewerSubject);
        });
    }

    private List<GpuSnapshot> gpuRows(UUID nodeId, String gpuUuid, boolean lock) {
        return jdbc.query("""
                SELECT gpu_uuid, model, vram_bytes, driver_version, cuda_version,
                       verification_state
                FROM provider_gpus WHERE node_id = ? AND gpu_uuid = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new GpuSnapshot(
                rs.getString("gpu_uuid"), rs.getString("model"), rs.getLong("vram_bytes"),
                rs.getString("driver_version"), rs.getString("cuda_version"),
                rs.getString("verification_state")), nodeId, gpuUuid);
    }

    private static void requireIdentity(UUID nodeId, String gpuUuid) {
        if (nodeId == null || gpuUuid == null || !GPU_UUID.matcher(gpuUuid).matches()) {
            throw new IllegalArgumentException("canonical node and GPU UUID are required");
        }
    }

    private static boolean validSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static String reportHash(UUID nodeId, GpuSnapshot gpu) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of("hyperlooms-gpu-report-v1", nodeId.toString(), gpu.gpuUuid(),
                    gpu.model(), Long.toString(gpu.vramBytes()), gpu.driverVersion(), gpu.cudaVersion())) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Candidate(UUID nodeId, String gpuUuid, String model, long vramBytes,
            String driverVersion, String cudaVersion, String state, String reportSha256) {
    }

    private record NodeSnapshot(String enrollment, String availability, boolean fresh) {
        boolean eligible() {
            return "VERIFIED".equals(enrollment) && "OFFLINE".equals(availability) && fresh;
        }
    }

    private record GpuSnapshot(String gpuUuid, String model, long vramBytes,
            String driverVersion, String cudaVersion, String state) {
        Candidate candidate(UUID nodeId) {
            return new Candidate(nodeId, gpuUuid, model, vramBytes, driverVersion,
                    cudaVersion, state, reportHash(nodeId, this));
        }
    }
}
