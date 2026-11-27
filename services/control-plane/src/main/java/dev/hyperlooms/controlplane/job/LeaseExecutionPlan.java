package dev.hyperlooms.controlplane.job;

import java.time.Instant;
import java.util.UUID;

/** Internal immutable snapshot of approved work; not an agent command or artifact grant. */
public record LeaseExecutionPlan(
        UUID jobId,
        UUID attemptId,
        UUID leaseId,
        UUID nodeId,
        String gpuUuid,
        long fencingToken,
        Instant expiresAt,
        String imageRepository,
        String imageDigest,
        int maxRuntimeSeconds,
        String verifiedInputPrefix,
        String inputManifestSha256,
        int inputFileCount,
        long inputTotalBytes) {
}
