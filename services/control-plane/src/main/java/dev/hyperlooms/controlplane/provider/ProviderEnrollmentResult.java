package dev.hyperlooms.controlplane.provider;

import java.util.UUID;

public record ProviderEnrollmentResult(UUID nodeId, boolean replayed) {
}
