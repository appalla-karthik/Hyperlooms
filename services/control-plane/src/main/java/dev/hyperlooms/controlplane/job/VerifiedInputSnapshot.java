package dev.hyperlooms.controlplane.job;

/** Staged and read-back-verified object prefix; not yet bound to a database job. */
public record VerifiedInputSnapshot(String prefix, String manifestSha256,
        int fileCount, long totalBytes) {
}
