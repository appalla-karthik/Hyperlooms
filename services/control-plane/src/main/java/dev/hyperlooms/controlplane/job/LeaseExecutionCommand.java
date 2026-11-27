package dev.hyperlooms.controlplane.job;

import java.time.Instant;
import java.util.UUID;

/** Durable prepared work, not an agent-delivered or runnable instruction. */
public record LeaseExecutionCommand(
        UUID id,
        UUID leaseId,
        UUID nodeId,
        String gpuUuid,
        long fencingToken,
        String imageRepository,
        String imageDigest,
        int maxRuntimeSeconds,
        String verifiedInputPrefix,
        String inputManifestSha256,
        int inputFileCount,
        long inputTotalBytes,
        Instant preparedAt) {

    boolean matches(LeaseExecutionPlan plan) {
        return leaseId.equals(plan.leaseId())
                && nodeId.equals(plan.nodeId())
                && gpuUuid.equals(plan.gpuUuid())
                && fencingToken == plan.fencingToken()
                && imageRepository.equals(plan.imageRepository())
                && imageDigest.equals(plan.imageDigest())
                && maxRuntimeSeconds == plan.maxRuntimeSeconds()
                && verifiedInputPrefix.equals(plan.verifiedInputPrefix())
                && inputManifestSha256.equals(plan.inputManifestSha256())
                && inputFileCount == plan.inputFileCount()
                && inputTotalBytes == plan.inputTotalBytes();
    }
}
