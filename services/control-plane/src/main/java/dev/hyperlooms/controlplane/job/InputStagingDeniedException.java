package dev.hyperlooms.controlplane.job;

/** Avoid revealing whether a job or staged object exists to an untrusted caller. */
public final class InputStagingDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InputStagingDeniedException() {
        super("Input staging is not permitted for this job");
    }
}
