package dev.hyperlooms.controlplane.job;

import java.util.UUID;

public record JobCancellationResult(UUID jobId, JobState state, boolean replayed) {
}
