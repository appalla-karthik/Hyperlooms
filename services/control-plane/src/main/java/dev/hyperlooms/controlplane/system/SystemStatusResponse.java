package dev.hyperlooms.controlplane.system;

import java.time.Instant;

public record SystemStatusResponse(
        String service,
        String version,
        String phase,
        Instant timestamp) {
}
