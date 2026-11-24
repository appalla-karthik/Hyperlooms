package dev.hyperlooms.controlplane.job;

import java.time.Instant;
import java.util.UUID;

/** A committed database claim, not yet a command delivered to an agent. */
public record LeaseAssignment(
        UUID jobId,
        UUID attemptId,
        UUID leaseId,
        UUID nodeId,
        String gpuUuid,
        long fencingToken,
        Instant expiresAt) {
}
