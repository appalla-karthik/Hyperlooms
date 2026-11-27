package dev.hyperlooms.controlplane.job;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.List;

import dev.hyperlooms.controlplane.shared.security.RequesterIdentity;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs")
@SecurityRequirement(name = "requesterBearer")
@ConditionalOnProperty(name = "hyperlooms.requester-api.enabled", havingValue = "true")
public class JobController {

    private final JobSubmissionService submissions;
    private final JobReadService reads;
    private final InputStagingService staging;
    private final JobInputQueueService queue;
    private final JobCancellationService cancellations;
    private final VerifiedResultService results;

    public JobController(JobSubmissionService submissions, JobReadService reads,
            InputStagingService staging, JobInputQueueService queue,
            JobCancellationService cancellations, VerifiedResultService results) {
        this.submissions = submissions;
        this.reads = reads;
        this.staging = staging;
        this.queue = queue;
        this.cancellations = cancellations;
        this.results = results;
    }

    @GetMapping
    public List<JobView> list(@AuthenticationPrincipal Jwt jwt) {
        return reads.recentOwned(RequesterIdentity.fromVerifiedJwt(jwt));
    }

    @GetMapping("/recipes")
    public List<JobReadService.RecipeView> recipes() {
        return reads.approvedRecipes();
    }

    @PostMapping
    public ResponseEntity<JobSubmissionResult> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitJobRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,200}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String requester = RequesterIdentity.fromVerifiedJwt(jwt);
        JobSubmissionResult result = submissions.submit(new JobSubmissionCommand(requester,
                idempotencyKey, new JobRequestV1(request.recipeId(), request.inputManifestSha256())));
        if (result.replayed()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.created(URI.create("/api/v1/jobs/" + result.jobId())).body(result);
    }

    @GetMapping("/{jobId}")
    public JobView get(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return reads.findOwned(jobId, RequesterIdentity.fromVerifiedJwt(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping(path = "/{jobId}/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> result(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        try {
            byte[] bytes = results.readOwned(jobId, RequesterIdentity.fromVerifiedJwt(jwt));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes);
        } catch (VerifiedResultService.ResultUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @PutMapping(path = "/{jobId}/inputs/manifest", consumes = "application/json")
    public ResponseEntity<Void> uploadManifest(@PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        String requester = RequesterIdentity.fromVerifiedJwt(jwt);
        requireOwnedJob(jobId, requester);
        byte[] bytes = readBounded(request, InputManifestV1.MAX_BYTES);
        boolean created = staging.stageManifest(jobId, requester, bytes);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @PutMapping(path = "/{jobId}/inputs/files", consumes = "application/octet-stream")
    public ResponseEntity<Void> uploadFile(@PathVariable UUID jobId,
            @RequestParam String path, @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) throws IOException {
        String requester = RequesterIdentity.fromVerifiedJwt(jwt);
        requireOwnedJob(jobId, requester);
        byte[] bytes = readBounded(request, InputSnapshotStager.MAX_FILE_BYTES);
        boolean created = staging.stageFile(jobId, requester, path, bytes);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @PostMapping("/{jobId}/queue")
    public JobInputQueueResult queue(@PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        try {
            return queue.queueVerifiedInput(jobId, RequesterIdentity.fromVerifiedJwt(jwt));
        } catch (IllegalArgumentException | InputObjectMissingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Input files are incomplete or invalid");
        }
    }

    @PostMapping("/{jobId}/cancel")
    public JobCancellationResult cancel(@PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return cancellations.cancelUnleased(jobId, RequesterIdentity.fromVerifiedJwt(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static byte[] readBounded(HttpServletRequest request, long maximum) throws IOException {
        if (request.getContentLengthLong() > maximum) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(413));
        }
        byte[] bytes = request.getInputStream().readNBytes(Math.toIntExact(maximum + 1));
        if (bytes.length > maximum) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(413));
        }
        return bytes;
    }

    private void requireOwnedJob(UUID jobId, String requester) {
        if (reads.findOwned(jobId, requester).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public record SubmitJobRequest(@NotNull UUID recipeId,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String inputManifestSha256) {
    }
}
