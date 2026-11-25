package dev.hyperlooms.controlplane.job;

import java.util.UUID;

public record JobInputQueueResult(UUID jobId, JobState state, boolean replayed) {
}
