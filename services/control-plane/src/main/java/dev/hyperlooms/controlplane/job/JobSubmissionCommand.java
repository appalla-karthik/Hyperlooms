package dev.hyperlooms.controlplane.job;

public record JobSubmissionCommand(
        String requesterSubject,
        String idempotencyKey,
        JobRequestV1 request) {

    public JobSubmissionCommand {
        if (requesterSubject == null || requesterSubject.isBlank() || requesterSubject.length() > 200) {
            throw new IllegalArgumentException("requesterSubject must contain 1-200 characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey must contain 1-200 characters");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
    }
}
