package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Tag("integration")
@SpringBootTest
class JobInputQueueServiceIntegrationTest {

    private static final byte[] CONTENT = "queued-input".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JobSubmissionService submissions;

    private final List<UUID> recipeIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID recipeId : recipeIds) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN "
                    + "(SELECT id FROM jobs WHERE recipe_id = ?)", recipeId);
            jdbc.update("DELETE FROM jobs WHERE recipe_id = ?", recipeId);
            jdbc.update("DELETE FROM workload_recipes WHERE id = ?", recipeId);
        }
    }

    @Test
    void bindsVerifiedSnapshotAndQueueEventExactlyOnce() {
        UUID recipeId = recipe();
        String owner = "requester:" + UUID.randomUUID();
        UUID jobId = submit(recipeId, owner);
        MemoryStore store = seeded(jobId);
        JobInputQueueService service = service(store);

        JobInputQueueResult first = service.queueVerifiedInput(jobId, owner);
        int readsAfterFirst = store.reads.get();
        JobInputQueueResult replay = service.queueVerifiedInput(jobId, owner);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(jobId, replay.jobId());
        assertEquals(readsAfterFirst, store.reads.get());
        assertEquals("QUEUED", jdbc.queryForObject(
                "SELECT state FROM jobs WHERE id = ?", String.class, jobId));
        assertTrue(jdbc.queryForObject(
                "SELECT input_artifact_prefix FROM jobs WHERE id = ?", String.class, jobId)
                .startsWith("jobs/" + jobId + "/verified/"));
        assertEquals(sha256(manifest()), jdbc.queryForObject(
                "SELECT verified_input_manifest_sha256 FROM jobs WHERE id = ?",
                String.class, jobId).trim());
        assertNotNull(jdbc.queryForObject(
                "SELECT input_verified_at FROM jobs WHERE id = ?", Object.class, jobId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_QUEUED'", jobId));
    }

    @Test
    void rejectsWrongOwnerAndCorruptStagingWithoutStateChange() {
        UUID recipeId = recipe();
        String owner = "requester:" + UUID.randomUUID();
        UUID jobId = submit(recipeId, owner);
        MemoryStore store = seeded(jobId);
        JobInputQueueService service = service(store);

        assertThrows(JobInputQueueDeniedException.class,
                () -> service.queueVerifiedInput(jobId, "another-requester"));
        assertEquals(0, store.reads.get());
        store.objects.put("jobs/" + jobId + "/staging/files/input.txt",
                "not-the-declared-bytes".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class,
                () -> service.queueVerifiedInput(jobId, owner));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT state FROM jobs WHERE id = ?", String.class, jobId));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_QUEUED'", jobId));
    }

    @Test
    void finalLockRejectsCancellationDuringObjectVerification() {
        UUID recipeId = recipe();
        String owner = "requester:" + UUID.randomUUID();
        UUID jobId = submit(recipeId, owner);
        MemoryStore store = seeded(jobId);
        store.onFirstRead = () -> jdbc.update(
                "UPDATE jobs SET state = 'CANCELED' WHERE id = ?", jobId);

        assertThrows(JobInputQueueDeniedException.class,
                () -> service(store).queueVerifiedInput(jobId, owner));
        assertEquals("CANCELED", jdbc.queryForObject(
                "SELECT state FROM jobs WHERE id = ?", String.class, jobId));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_QUEUED'", jobId));
    }

    @Test
    void finalLockRejectsRecipeDisabledDuringObjectVerification() {
        UUID recipeId = recipe();
        String owner = "requester:" + UUID.randomUUID();
        UUID jobId = submit(recipeId, owner);
        MemoryStore store = seeded(jobId);
        store.onFirstRead = () -> jdbc.update(
                "UPDATE workload_recipes SET state = 'DISABLED' WHERE id = ?", recipeId);

        assertThrows(JobInputQueueDeniedException.class,
                () -> service(store).queueVerifiedInput(jobId, owner));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT state FROM jobs WHERE id = ?", String.class, jobId));
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_QUEUED'", jobId));
    }

    @Test
    void databaseRejectsDirectUnverifiedV1QueueFixture() {
        UUID recipeId = recipe();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO jobs
                    (id, requester_subject, recipe_id, state, idempotency_key,
                     request_sha256, request_version, input_manifest_sha256)
                VALUES (?, 'integration', ?, 'QUEUED', ?, ?, 1, ?)
                """, UUID.randomUUID(), recipeId, "unverified-" + UUID.randomUUID(),
                "a".repeat(64), sha256(manifest())));
    }

    @Test
    void realS3SnapshotBindsToPostgresJobBeforeQueueing() {
        UUID recipeId = recipe();
        String owner = "requester:" + UUID.randomUUID();
        UUID jobId = submit(recipeId, owner);
        String prefix = "jobs/" + jobId + "/";
        String bucket = System.getenv().getOrDefault("HYPERLOOMS_ARTIFACT_BUCKET",
                "hyperlooms-artifacts");
        String endpoint = System.getenv().getOrDefault("HYPERLOOMS_S3_ENDPOINT",
                "http://localhost:9000");
        String accessKey = System.getenv().getOrDefault("HYPERLOOMS_S3_ACCESS_KEY",
                "hyperlooms-local");
        String secretKey = System.getenv().getOrDefault("HYPERLOOMS_S3_SECRET_KEY",
                "local-development-only-change-me");

        try (S3Client client = S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build()) {
            S3InputObjectStore store = new S3InputObjectStore(client, bucket);
            try {
                store.put(prefix + "staging/manifest.json", manifest());
                store.put(prefix + "staging/files/input.txt", CONTENT);

                JobInputQueueResult queued = new JobInputQueueService(jdbc, transactions,
                        new InputSnapshotStager(store)).queueVerifiedInput(jobId, owner);

                assertEquals(JobState.QUEUED, queued.state());
                assertFalse(queued.replayed());
                assertEquals("QUEUED", jdbc.queryForObject(
                        "SELECT state FROM jobs WHERE id = ?", String.class, jobId));
                assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                        + "WHERE aggregate_id = ? AND event_type = 'JOB_QUEUED'", jobId));
            } finally {
                client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(prefix).build()).contents().forEach(object ->
                        client.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build()));
            }
        }
    }

    private JobInputQueueService service(MemoryStore store) {
        return new JobInputQueueService(jdbc, transactions, new InputSnapshotStager(store));
    }

    private UUID recipe() {
        UUID id = UUID.randomUUID();
        recipeIds.add(id);
        jdbc.update("""
                INSERT INTO workload_recipes
                    (id, name, image_repository, image_digest, max_runtime_seconds,
                     min_vram_bytes, state, approved_at)
                VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?,
                        60, 1073741824, 'APPROVED', CURRENT_TIMESTAMP)
                """, id, "integration-" + id, "sha256:" + "a".repeat(64));
        return id;
    }

    private UUID submit(UUID recipeId, String owner) {
        return submissions.submit(new JobSubmissionCommand(owner,
                "queue-test-" + UUID.randomUUID(),
                new JobRequestV1(recipeId, sha256(manifest())))).jobId();
    }

    private static MemoryStore seeded(UUID jobId) {
        MemoryStore store = new MemoryStore();
        String prefix = "jobs/" + jobId + "/staging/";
        store.objects.put(prefix + "manifest.json", manifest());
        store.objects.put(prefix + "files/input.txt", CONTENT.clone());
        return store;
    }

    private static byte[] manifest() {
        return ("{\"files\":[{\"path\":\"input.txt\",\"sha256\":\"" + sha256(CONTENT)
                + "\",\"sizeBytes\":" + CONTENT.length + "}],\"schemaVersion\":1}")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(String query, Object... arguments) {
        return jdbc.queryForObject(query, Long.class, arguments);
    }

    private static final class MemoryStore implements InputObjectStore {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicBoolean callbackInvoked = new AtomicBoolean();
        private Runnable onFirstRead;

        @Override
        public InputStream get(String key) {
            reads.incrementAndGet();
            if (onFirstRead != null && callbackInvoked.compareAndSet(false, true)) {
                onFirstRead.run();
            }
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new IllegalArgumentException("missing test object");
            }
            return new ByteArrayInputStream(bytes.clone());
        }

        @Override
        public void put(String key, byte[] content) {
            objects.put(key, content.clone());
        }
    }
}
