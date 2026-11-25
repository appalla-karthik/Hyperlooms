package dev.hyperlooms.controlplane.provider;

/** An operator review cannot approve this current provider/GPU snapshot. */
public final class ProviderHardwareReviewDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProviderHardwareReviewDeniedException() {
        super("provider hardware review denied");
    }
}
