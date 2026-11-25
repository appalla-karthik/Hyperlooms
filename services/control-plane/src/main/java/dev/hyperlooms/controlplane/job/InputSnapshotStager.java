package dev.hyperlooms.controlplane.job;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal bounded verifier. No upload grant, database mutation, or queue transition. */
public final class InputSnapshotStager {

    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final InputObjectStore store;

    public InputSnapshotStager(InputObjectStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    public VerifiedInputSnapshot stage(UUID jobId, String declaredManifestSha256) {
        if (jobId == null || declaredManifestSha256 == null
                || !SHA256.matcher(declaredManifestSha256).matches()) {
            throw new IllegalArgumentException("job ID and canonical manifest digest are required");
        }
        String staging = "jobs/" + jobId + "/staging/";
        byte[] manifestBytes = readBounded(staging + "manifest.json", InputManifestV1.MAX_BYTES);
        InputManifestV1 manifest = InputManifestV1.parse(manifestBytes);
        if (!manifest.matchesDeclaredDigest(declaredManifestSha256)) {
            throw new IllegalArgumentException("staged manifest differs from job declaration");
        }

        long total = 0;
        for (InputManifestV1.Entry entry : manifest.files()) {
            if (entry.sizeBytes() > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES - entry.sizeBytes()) {
                throw new IllegalArgumentException("input exceeds staging quota");
            }
            total += entry.sizeBytes();
        }

        String verified = "jobs/" + jobId + "/verified/" + UUID.randomUUID() + "/";
        for (InputManifestV1.Entry entry : manifest.files()) {
            String path = "files/" + entry.path();
            byte[] content = readBounded(staging + path, entry.sizeBytes());
            requireMatch(content, entry.sizeBytes(), entry.sha256());
            store.put(verified + path, content);
            requireMatch(readBounded(verified + path, entry.sizeBytes()),
                    entry.sizeBytes(), entry.sha256());
        }
        store.put(verified + "manifest.json", manifestBytes);
        byte[] copiedManifest = readBounded(verified + "manifest.json", InputManifestV1.MAX_BYTES);
        if (!Arrays.equals(manifestBytes, copiedManifest)) {
            throw new IllegalStateException("verified manifest read-back mismatch");
        }
        return new VerifiedInputSnapshot(verified, manifest.sha256(), manifest.files().size(), total);
    }

    private byte[] readBounded(String key, long maximum) {
        try (InputStream stream = store.get(key);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximum) {
                    throw new IllegalArgumentException("staged object exceeds declared size or quota");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("could not read input object", exception);
        }
    }

    private static void requireMatch(byte[] content, long size, String expectedSha256) {
        if (content.length != size || !sha256(content).equals(expectedSha256)) {
            throw new IllegalArgumentException("staged object does not match manifest");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
