package dev.hyperlooms.controlplane.job;

import java.util.UUID;

public record JobSubmissionResult(UUID jobId, JobState state, boolean replayed) {
}
