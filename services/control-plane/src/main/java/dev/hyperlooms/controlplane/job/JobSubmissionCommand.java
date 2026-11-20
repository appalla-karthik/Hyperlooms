package dev.hyperlooms.controlplane.job;

import java.util.UUID;

public record JobSubmissionCommand(
        String requesterSubject,
        UUID recipeId,
        String idempotencyKey,
        String requestSha256) {

    public JobSubmissionCommand {
        if (requesterSubject == null || requesterSubject.isBlank() || requesterSubject.length() > 200) {
            throw new IllegalArgumentException("requesterSubject must contain 1-200 characters");
        }
        if (recipeId == null) {
            throw new IllegalArgumentException("recipeId is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey must contain 1-200 characters");
        }
        if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestSha256 must be a lowercase SHA-256 hex digest");
        }
    }
}
