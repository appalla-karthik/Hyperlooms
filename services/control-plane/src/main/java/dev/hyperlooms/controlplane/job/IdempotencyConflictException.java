package dev.hyperlooms.controlplane.job;

public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("Idempotency key is already associated with a different request");
    }
}
