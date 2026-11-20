package dev.hyperlooms.controlplane.provider;

public class EnrollmentDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnrollmentDeniedException() {
        super("Provider enrollment invite is invalid, expired, or revoked");
    }
}
