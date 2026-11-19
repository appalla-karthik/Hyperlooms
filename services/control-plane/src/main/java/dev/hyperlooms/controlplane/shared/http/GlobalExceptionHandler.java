package dev.hyperlooms.controlplane.shared.http;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ApiError error = createError(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more fields are invalid.",
                request,
                violations);
        return ResponseEntity.badRequest().body(error);
    }

    private ApiError createError(
            HttpStatus status,
            String title,
            String detail,
            HttpServletRequest request,
            List<ApiError.FieldViolation> violations) {
        String traceId = MDC.get("traceId");
        return new ApiError(
                "https://docs.hyperlooms.dev/problems/" + status.value(),
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                traceId == null ? "unavailable" : traceId,
                Instant.now(clock),
                violations);
    }
}
