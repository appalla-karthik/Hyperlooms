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
        JobSubmissionCommand command = new JobSubmissionCommand(requester, recipeId, "request-1", DIGEST);

        JobSubmissionResult first = submissions.submit(command);
        JobSubmissionResult replay = submissions.submit(command);

        assertEquals(first.jobId(), replay.jobId());
        assertEquals(JobState.CREATED, first.state());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE recipe_id = ?", recipeId));
        assertEquals(1L, count("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_CREATED'", first.jobId()));
    }

    @Test
    void reusedKeyCannotChangeRequestOrRecipe() {
        UUID firstRecipe = recipe("APPROVED");
        UUID secondRecipe = recipe("APPROVED");
        String requester = "integration:" + UUID.randomUUID();
        submissions.submit(new JobSubmissionCommand(requester, firstRecipe, "request-1", DIGEST));

        assertThrows(IdempotencyConflictException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, firstRecipe, "request-1", "b".repeat(64))));
        assertThrows(IdempotencyConflictException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, secondRecipe, "request-1", DIGEST)));
        assertEquals(1L, count("SELECT COUNT(*) FROM jobs WHERE requester_subject = ?", requester));
    }

    @Test
    void draftAndUnknownRecipesAreRejectedWithoutSideEffects() {
        UUID draftRecipe = recipe("DRAFT");
        String requester = "integration:" + UUID.randomUUID();

        assertThrows(RecipeUnavailableException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, draftRecipe, "draft", DIGEST)));
        assertThrows(RecipeUnavailableException.class, () -> submissions.submit(
                new JobSubmissionCommand(requester, UUID.randomUUID(), "unknown", DIGEST)));
        assertEquals(0L, count("SELECT COUNT(*) FROM jobs WHERE requester_subject = ?", requester));
    }

    @Test
    void concurrentRetriesStillCreateOnlyOneJob() throws Exception {
        UUID recipeId = recipe("APPROVED");
        JobSubmissionCommand command = new JobSubmissionCommand(
                "integration:" + UUID.randomUUID(), recipeId, "concurrent", DIGEST);
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

    private long count(String query, Object argument) {
        Long result = jdbc.queryForObject(query, Long.class, argument);
        return result == null ? 0 : result;
    }
}
