package dev.hyperlooms.controlplane.job;

/** Deliberately does not distinguish a missing, stale, or wrong-node lease. */
public final class LeaseAcknowledgementDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LeaseAcknowledgementDeniedException() {
        super("Lease acknowledgement denied");
    }
}
