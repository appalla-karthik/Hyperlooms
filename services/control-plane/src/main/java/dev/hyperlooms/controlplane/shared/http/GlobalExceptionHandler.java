package dev.hyperlooms.controlplane.shared.http;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import dev.hyperlooms.controlplane.job.IdempotencyConflictException;
import dev.hyperlooms.controlplane.job.JobCancellationConflictException;
import dev.hyperlooms.controlplane.job.InputObjectMissingException;
import dev.hyperlooms.controlplane.job.InputStagingDeniedException;
import dev.hyperlooms.controlplane.job.JobInputQueueDeniedException;
import dev.hyperlooms.controlplane.job.RecipeUnavailableException;

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

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(createError(
                HttpStatus.CONFLICT, "Idempotency conflict",
                "This key was already used for a different job request.", request, List.of()));
    }

    @ExceptionHandler(JobCancellationConflictException.class)
    public ResponseEntity<ApiError> handleCancellationConflict(
            JobCancellationConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(createError(
                HttpStatus.CONFLICT, "Cancellation unavailable",
                "An active or terminal job cannot be canceled through this API.",
                request, List.of()));
    }

    @ExceptionHandler(RecipeUnavailableException.class)
    public ResponseEntity<ApiError> handleRecipeUnavailable(
            RecipeUnavailableException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createError(
                HttpStatus.NOT_FOUND, "Recipe unavailable",
                "The requested recipe is unavailable.", request, List.of()));
    }

    @ExceptionHandler({InputStagingDeniedException.class, JobInputQueueDeniedException.class})
    public ResponseEntity<ApiError> handleInputAccessDenied(
            RuntimeException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createError(
                HttpStatus.NOT_FOUND, "Job unavailable",
                "The requested job or input operation is unavailable.", request, List.of()));
    }

    @ExceptionHandler(InputObjectMissingException.class)
    public ResponseEntity<ApiError> handleMissingInput(
            InputObjectMissingException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(createError(
                HttpStatus.CONFLICT, "Input incomplete",
                "A required input object has not been staged.", request, List.of()));
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
