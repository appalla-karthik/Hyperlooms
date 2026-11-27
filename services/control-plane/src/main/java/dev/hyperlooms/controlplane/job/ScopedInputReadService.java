package dev.hyperlooms.controlplane.job;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/**
 * Internal, credential-free input reader for one staged command and one
 * manifest-listed file. Not an agent endpoint or execution grant.
 */
public final class ScopedInputReadService {

    private final JdbcTemplate jdbc;
    private final LeaseExecutionPlanService plans;
    private final InputObjectStore objects;

    public ScopedInputReadService(JdbcTemplate jdbc, LeaseExecutionPlanService plans,
            InputObjectStore objects) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    /** Never accepts an arbitrary key or bucket from a caller. */
    public byte[] readFile(ProviderPeerIdentity peer, UUID leaseId, UUID commandId,
            long fencingToken, String path) {
        LeaseExecutionPlan plan = requireStagedPlan(peer, leaseId, commandId, fencingToken);
        String prefix = plan.verifiedInputPrefix();
        byte[] manifestBytes = readBounded(prefix + "manifest.json", InputManifestV1.MAX_BYTES);
        InputManifestV1 manifest;
        try {
            manifest = InputManifestV1.parse(manifestBytes);
        } catch (IllegalArgumentException exception) {
            throw new LeaseExecutionDeniedException();
        }
        if (!manifest.matchesDeclaredDigest(plan.inputManifestSha256())
                || manifest.files().size() != plan.inputFileCount()) {
            throw new LeaseExecutionDeniedException();
        }
        long total = 0;
        for (InputManifestV1.Entry file : manifest.files()) {
            if (file.sizeBytes() > InputSnapshotStager.MAX_FILE_BYTES
                    || total > InputSnapshotStager.MAX_TOTAL_BYTES - file.sizeBytes()) {
                throw new LeaseExecutionDeniedException();
            }
            total += file.sizeBytes();
        }
        if (total != plan.inputTotalBytes() || path == null) {
            throw new LeaseExecutionDeniedException();
        }
        InputManifestV1.Entry entry = manifest.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst().orElseThrow(LeaseExecutionDeniedException::new);
        byte[] content = readBounded(prefix + "files/" + entry.path(), entry.sizeBytes());
        if (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256())) {
            throw new LeaseExecutionDeniedException();
        }
        // The lease, certificate, recipe, verified GPU and staging receipt can
        // change while S3 is read. Recheck before returning any bytes.
        LeaseExecutionPlan current = requireStagedPlan(peer, leaseId, commandId, fencingToken);
        if (!current.equals(plan)) {
            throw new LeaseExecutionDeniedException();
        }
        return content;
    }

    private LeaseExecutionPlan requireStagedPlan(ProviderPeerIdentity peer, UUID leaseId,
            UUID commandId, long fencingToken) {
        if (commandId == null) {
            throw new LeaseExecutionDeniedException();
        }
        LeaseExecutionPlan plan = plans.loadCurrentForPeer(peer, leaseId, fencingToken);
        List<UUID> rows = jdbc.query("""
                SELECT c.id FROM lease_execution_commands c
                JOIN lease_execution_command_staging s ON s.command_id = c.id
                WHERE c.id = ? AND c.lease_id = ? AND c.node_id = ?
                  AND c.fencing_token = ? AND s.lease_id = c.lease_id
                  AND s.node_id = c.node_id AND s.fencing_token = c.fencing_token
                  AND c.gpu_uuid = ? AND c.image_repository = ? AND c.image_digest = ?
                  AND c.max_runtime_seconds = ? AND c.verified_input_prefix = ?
                  AND c.input_manifest_sha256 = ? AND c.input_file_count = ?
                  AND c.input_total_bytes = ?
                """, (rs, row) -> rs.getObject(1, UUID.class), commandId,
                plan.leaseId(), plan.nodeId(), plan.fencingToken(), plan.gpuUuid(),
                plan.imageRepository(), plan.imageDigest(), plan.maxRuntimeSeconds(),
                plan.verifiedInputPrefix(), plan.inputManifestSha256(),
                plan.inputFileCount(), plan.inputTotalBytes());
        if (rows.size() != 1) {
            throw new LeaseExecutionDeniedException();
        }
        return plan;
    }

    private byte[] readBounded(String key, long maximum) {
        try (InputStream input = objects.get(key)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maximum + 1));
            if (bytes.length > maximum) {
                throw new LeaseExecutionDeniedException();
            }
            return bytes;
        } catch (IOException | InputObjectMissingException exception) {
            throw new LeaseExecutionDeniedException();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
