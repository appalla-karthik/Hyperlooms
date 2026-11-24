package dev.hyperlooms.controlplane.job;

import java.util.UUID;

/** A committed timeout, not proof that the remote process has stopped. */
public record LeaseExpiration(
        UUID jobId,
        UUID attemptId,
        UUID leaseId,
        UUID nodeId,
        long fencingToken) {
}
