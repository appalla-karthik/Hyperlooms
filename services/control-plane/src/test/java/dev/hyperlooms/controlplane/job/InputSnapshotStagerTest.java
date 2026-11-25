package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InputSnapshotStagerTest {

    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.US_ASCII);

    @Test
    void copiesOnlyVerifiedBytesAndReturnsPrivateSnapshotPrefix() {
        UUID jobId = UUID.randomUUID();
        MemoryStore store = seeded(jobId, CONTENT);

        VerifiedInputSnapshot snapshot = new InputSnapshotStager(store).stage(jobId,
                sha256(manifest(CONTENT)));

        assertTrue(snapshot.prefix().startsWith("jobs/" + jobId + "/verified/"));
        assertEquals(1, snapshot.fileCount());
        assertEquals(CONTENT.length, snapshot.totalBytes());
        assertArrayEquals(CONTENT, store.objects.get(snapshot.prefix() + "files/input.txt"));
        assertArrayEquals(manifest(CONTENT),
                store.objects.get(snapshot.prefix() + "manifest.json"));
        assertEquals(sha256(manifest(CONTENT)), snapshot.manifestSha256());
    }

    @Test
    void rejectsDeclarationOrStagedByteMismatchBeforePublishingSnapshot() {
        UUID jobId = UUID.randomUUID();
        MemoryStore store = seeded(jobId, CONTENT);
        InputSnapshotStager stager = new InputSnapshotStager(store);

        assertThrows(IllegalArgumentException.class,
                () -> stager.stage(jobId, "a".repeat(64)));
        store.objects.put("jobs/" + jobId + "/staging/files/input.txt",
                "wrong".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class,
                () -> stager.stage(jobId, sha256(manifest(CONTENT))));
        assertTrue(store.objects.keySet().stream().noneMatch(key -> key.contains("/verified/")));
    }

    @Test
    void enforcesQuotaBeforeReadingFiles() {
        UUID jobId = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        byte[] manifest = ("{\"files\":[{\"path\":\"big.bin\",\"sha256\":\""
                + "a".repeat(64) + "\",\"sizeBytes\":" + (InputSnapshotStager.MAX_FILE_BYTES + 1)
                + "}],\"schemaVersion\":1}").getBytes(StandardCharsets.US_ASCII);
        store.objects.put("jobs/" + jobId + "/staging/manifest.json", manifest);

        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshotStager(store).stage(jobId, sha256(manifest)));
        assertEquals(1, store.reads);
    }

    @Test
    void failsClosedWhenVerifiedCopyReadbackDiffers() {
        UUID jobId = UUID.randomUUID();
        MemoryStore store = seeded(jobId, CONTENT);
        store.corruptVerifiedWrites = true;

        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshotStager(store).stage(jobId,
                        sha256(manifest(CONTENT))));
    }

    private static MemoryStore seeded(UUID jobId, byte[] content) {
        MemoryStore store = new MemoryStore();
        String prefix = "jobs/" + jobId + "/staging/";
        store.objects.put(prefix + "manifest.json", manifest(content));
        store.objects.put(prefix + "files/input.txt", content.clone());
        return store;
    }

    private static byte[] manifest(byte[] content) {
        return ("{\"files\":[{\"path\":\"input.txt\",\"sha256\":\"" + sha256(content)
                + "\",\"sizeBytes\":" + content.length + "}],\"schemaVersion\":1}")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MemoryStore implements InputObjectStore {
        private final Map<String, byte[]> objects = new HashMap<>();
        private int reads;
        private boolean corruptVerifiedWrites;

        @Override
        public InputStream get(String key) {
            reads++;
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new IllegalArgumentException("missing object");
            }
            return new ByteArrayInputStream(bytes.clone());
        }

        @Override
        public void put(String key, byte[] content) {
            objects.put(key, corruptVerifiedWrites ? new byte[] {0} : content.clone());
        }
    }
}
