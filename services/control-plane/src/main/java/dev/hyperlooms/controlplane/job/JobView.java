package dev.hyperlooms.controlplane.job;

import java.time.Instant;
import java.util.UUID;

/** Public owner-scoped state only; object keys and provider identities stay private. */
public record JobView(UUID jobId, UUID recipeId, JobState state,
        Instant createdAt, Instant updatedAt) {
}
