package dev.hyperlooms.controlplane.job;

/** A stale, unauthenticated, or otherwise ineligible execution transition. */
public final class LeaseExecutionDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LeaseExecutionDeniedException() {
        super("lease execution transition denied");
    }
}
