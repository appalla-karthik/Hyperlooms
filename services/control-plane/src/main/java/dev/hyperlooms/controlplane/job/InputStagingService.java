package dev.hyperlooms.controlplane.job;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Internal-only, server-mediated staging. The caller must supply an authenticated subject. */
public final class InputStagingService {

    private final JdbcTemplate jdbc;
    private final InputObjectStore store;

    public InputStagingService(JdbcTemplate jdbc, InputObjectStore store) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Writes the exact declared canonical manifest. An identical retry is harmless. */
    public boolean stageManifest(UUID jobId, String trustedRequesterSubject, byte[] bytes) {
        JobRecord job = requireCreatedJob(jobId, trustedRequesterSubject);
        byte[] stable = bytes == null ? null : bytes.clone();
        InputManifestV1 manifest = parseManifest(stable);
        requireManifest(job, manifest);
        return createOrReplay(key(jobId, "manifest.json"), stable, InputManifestV1.MAX_BYTES);
    }

    /** Writes only a file named by the already-staged, digest-matching manifest. */
    public boolean stageFile(UUID jobId, String trustedRequesterSubject, String path, byte[] bytes) {
        JobRecord job = requireCreatedJob(jobId, trustedRequesterSubject);
        if (path == null || bytes == null || bytes.length > InputSnapshotStager.MAX_FILE_BYTES) {
            throw new InputStagingDeniedException();
        }
        byte[] stable = bytes.clone();
        byte[] manifestBytes = readBounded(key(jobId, "manifest.json"), InputManifestV1.MAX_BYTES);
        InputManifestV1 manifest = parseManifest(manifestBytes);
        requireManifest(job, manifest);
        InputManifestV1.Entry entry = manifest.files().stream()
                .filter(candidate -> candidate.path().equals(path))
                .findFirst().orElseThrow(InputStagingDeniedException::new);
        if (entry.sizeBytes() != stable.length || !entry.sha256().equals(sha256(stable))) {
            throw new InputStagingDeniedException();
        }
        return createOrReplay(key(jobId, "files/" + path), stable, entry.sizeBytes());
    }

    private JobRecord requireCreatedJob(UUID jobId, String requester) {
        if (jobId == null || requester == null || requester.isBlank() || requester.length() > 200) {
            throw new InputStagingDeniedException();
        }
        List<JobRecord> rows = jdbc.query("""
                SELECT j.requester_subject, j.request_version, j.input_manifest_sha256,
                       j.state, r.state AS recipe_state
                FROM jobs j JOIN workload_recipes r ON r.id = j.recipe_id
                WHERE j.id = ?
                """, (rs, row) -> new JobRecord(rs.getString("requester_subject"),
                rs.getInt("request_version"), rs.getString("input_manifest_sha256"),
                rs.getString("state"), rs.getString("recipe_state")), jobId);
        if (rows.isEmpty()) {
            throw new InputStagingDeniedException();
        }
        JobRecord job = rows.get(0);
        if (!job.requester().equals(requester) || job.version() != 1
                || !"CREATED".equals(job.state()) || !"APPROVED".equals(job.recipeState())) {
            throw new InputStagingDeniedException();
        }
        return job;
    }

    private static void requireManifest(JobRecord job, InputManifestV1 manifest) {
        if (!manifest.matchesDeclaredDigest(job.manifestSha256().trim())) {
            throw new InputStagingDeniedException();
        }
        long total = 0;
        for (InputManifestV1.Entry entry : manifest.files()) {
            if (entry.sizeBytes() > InputSnapshotStager.MAX_FILE_BYTES
                    || total > InputSnapshotStager.MAX_TOTAL_BYTES - entry.sizeBytes()) {
                throw new InputStagingDeniedException();
            }
            total += entry.sizeBytes();
        }
    }

    private static InputManifestV1 parseManifest(byte[] bytes) {
        try {
            return InputManifestV1.parse(bytes);
        } catch (IllegalArgumentException exception) {
            throw new InputStagingDeniedException();
        }
    }

    private boolean createOrReplay(String key, byte[] bytes, long maximum) {
        if (store.putIfAbsent(key, bytes)) {
            return true;
        }
        if (!Arrays.equals(bytes, readBounded(key, maximum))) {
            throw new InputStagingDeniedException();
        }
        return false;
    }

    private byte[] readBounded(String key, long maximum) {
        try (InputStream stream = store.get(key)) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(maximum + 1));
            if (bytes.length > maximum) {
                throw new InputStagingDeniedException();
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read staged input", exception);
        }
    }

    private static String key(UUID jobId, String suffix) {
        return "jobs/" + jobId + "/staging/" + suffix;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record JobRecord(String requester, int version, String manifestSha256,
            String state, String recipeState) {
    }
}
