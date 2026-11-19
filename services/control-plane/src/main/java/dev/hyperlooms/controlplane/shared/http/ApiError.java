package dev.hyperlooms.controlplane.shared.http;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String traceId,
        Instant timestamp,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
