package dev.hyperlooms.controlplane.provider;

public class ProviderIdentityDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProviderIdentityDeniedException() {
        super("Provider certificate identity is not authorized");
    }
}
