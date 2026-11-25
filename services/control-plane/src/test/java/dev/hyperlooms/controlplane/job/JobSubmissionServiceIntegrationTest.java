package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class JobSubmissionServiceIntegrationTest {

    private static final String DIGEST = "a".repeat(64);

    @Autowired
    private JobSubmissionService submissions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> recipeIds = new ArrayList<>();

    @AfterEach
    void removeTestRecords() {
        for (UUID recipeId : recipeIds) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN "
                    + "(SELECT id FROM jobs WHERE recipe_id = ?)", recipeId);
            jdbc.update("DELETE FROM jobs WHERE recipe_id = ?", recipeId);
            jdbc.update("DELETE FROM workload_recipes WHERE id = ?", recipeId);
        }
    }

    @Test
    void approvedRecipeCreatesOneDurableJobAndOutboxEvent() {
        UUID recipeId = recipe("APPROVED");
        String requester = "integration:" + UUID.randomUUID();
        JobSubmissionCommand command = new JobSubmissionCommand(
                requester, "request-1", new JobRequestV1(recipeId, DIGEST));

        JobSubmissionResult first = submissions.submit(command);
        JobSubmissionResult replay = submissions.submit(command);

        assertEquals(first.jobId(), replay.jobId());
        assertEquals(JobState.CREATED, first.state());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE recipe_id = ?", recipeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE recipe_id = ? "
                + "AND request_version = 1 AND input_manifest_sha256 = ?", recipeId, DIGEST));
        assertEquals(command.request().requestSha256(), jdbc.queryForObject(
                "SELECT request_sha256 FROM jobs WHERE id = ?", String.class, first.jobId()).trim());
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_CREATED'", first.jobId()));
    }

    @Test
    void reusedKeyCannotChangeRequestOrRecipe() {
        UUID firstRecipe = recipe("APPROVED");
        UUID secondRecipe = recipe("APPROVED");
        String requester = "integration:" + UUID.randomUUID();
        submissions.submit(new JobSubmissionCommand(
                requester, "request-1", new JobRequestV1(firstRecipe, DIGEST)));

        assertThrows(IdempotencyConflictException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, "request-1",
                        new JobRequestV1(firstRecipe, "b".repeat(64)))));
        assertThrows(IdempotencyConflictException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, "request-1",
                        new JobRequestV1(secondRecipe, DIGEST))));
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE requester_subject = ?", requester));
    }

    @Test
    void legacyUnversionedJobCannotReplayAsVersionOne() {
        UUID recipeId = recipe("APPROVED");
        String requester = "integration:" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO jobs
                    (id, requester_subject, recipe_id, state, idempotency_key, request_sha256)
                VALUES (?, ?, ?, 'CREATED', 'legacy-key', ?)
                """, UUID.randomUUID(), requester, recipeId, "a".repeat(64));

        assertThrows(IdempotencyConflictException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, "legacy-key",
                        new JobRequestV1(recipeId, DIGEST))));
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE requester_subject = ?", requester));
    }

    @Test
    void draftAndUnknownRecipesAreRejectedWithoutSideEffects() {
        UUID draftRecipe = recipe("DRAFT");
        String requester = "integration:" + UUID.randomUUID();

        assertThrows(RecipeUnavailableException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, "draft", new JobRequestV1(draftRecipe, DIGEST))));
        assertThrows(RecipeUnavailableException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, "unknown",
                        new JobRequestV1(UUID.randomUUID(), DIGEST))));
        assertEquals(0L, count("SELECT COUNT(*) FROM jobs WHERE requester_subject = ?", requester));
    }

    @Test
    void concurrentRetriesStillCreateOnlyOneJob() throws Exception {
        UUID recipeId = recipe("APPROVED");
        JobSubmissionCommand command = new JobSubmissionCommand(
                "integration:" + UUID.randomUUID(), "concurrent",
                new JobRequestV1(recipeId, DIGEST));
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JobSubmissionResult> first = executor.submit(() -> {
                start.await();
                return submissions.submit(command);
            });
            Future<JobSubmissionResult> second = executor.submit(() -> {
                start.await();
                return submissions.submit(command);
            });
            start.countDown();

            JobSubmissionResult firstResult = first.get();
            JobSubmissionResult secondResult = second.get();
            assertEquals(firstResult.jobId(), secondResult.jobId());
            assertTrue(firstResult.replayed() != secondResult.replayed());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE recipe_id = ?", recipeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'JOB' "
                + "AND aggregate_id IN (SELECT id FROM jobs WHERE recipe_id = ?)", recipeId));
    }

    private UUID recipe(String state) {
        UUID id = UUID.randomUUID();
        recipeIds.add(id);
        jdbc.update("""
                INSERT INTO workload_recipes
                    (id, name, image_repository, image_digest, max_runtime_seconds,
                     min_vram_bytes, state, approved_at)
                VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?, 60, 1073741824,
                    ?, CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, id, "integration-" + id, "sha256:" + DIGEST, state, state);
        return id;
    }

    private long count(String query, Object... arguments) {
        Long result = jdbc.queryForObject(query, Long.class, arguments);
        return result == null ? 0 : result;
    }
}
