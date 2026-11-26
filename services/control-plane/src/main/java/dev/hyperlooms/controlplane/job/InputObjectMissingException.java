package dev.hyperlooms.controlplane.job;

/** A required staged object has not been uploaded yet. */
public final class InputObjectMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InputObjectMissingException() {
        super("Required staged input is missing");
    }
}
