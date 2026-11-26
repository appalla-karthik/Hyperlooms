package dev.hyperlooms.controlplane.job;

/** Cancellation requires a remote stop protocol after a lease has been issued. */
public final class JobCancellationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobCancellationConflictException() {
        super("An active lease cannot be canceled through the requester API");
    }
}
