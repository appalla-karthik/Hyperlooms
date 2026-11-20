package dev.hyperlooms.controlplane.job;

public class RecipeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecipeUnavailableException() {
        super("The requested recipe is not approved or available");
    }
}
