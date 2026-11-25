package dev.hyperlooms.controlplane.job;

/** Internal queueing precondition failed; do not expose existence details to a requester. */
public final class JobInputQueueDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobInputQueueDeniedException() {
        super("Job is not eligible for verified input queueing");
    }
}
