package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Tag("integration")
@SpringBootTest(properties = {
        "hyperlooms.requester-api.enabled=true",
        "hyperlooms.requester-api.issuer-uri=https://issuer.example.invalid",
        "hyperlooms.requester-api.jwk-set-uri=https://issuer.example.invalid/jwks",
        "hyperlooms.requester-api.audience=hyperlooms-api",
        "hyperlooms.requester-api.storage.bucket=hyperlooms-artifacts",
        "hyperlooms.requester-api.storage.endpoint=http://localhost:9000",
        "hyperlooms.requester-api.storage.path-style=true",
        "hyperlooms.requester-api.storage.allow-insecure-local-endpoint=true",
        "hyperlooms.requester-api.storage.access-key=hyperlooms-local",
        "hyperlooms.requester-api.storage.secret-key=local-development-only-change-me"
})
@AutoConfigureMockMvc
class JobControllerIntegrationTest {

    private static final String DIGEST = "a".repeat(64);

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private S3Client s3;

    private final List<UUID> recipes = new ArrayList<>();
    private final List<UUID> jobs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID recipeId : recipes) {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN "
                    + "(SELECT id FROM jobs WHERE recipe_id = ?)", recipeId);
            jdbc.update("DELETE FROM jobs WHERE recipe_id = ?", recipeId);
            jdbc.update("DELETE FROM workload_recipes WHERE id = ?", recipeId);
        }
        for (UUID jobId : jobs) {
            s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket("hyperlooms-artifacts").prefix("jobs/" + jobId + "/").build())
                    .contents().forEach(object -> s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket("hyperlooms-artifacts").key(object.key()).build()));
        }
    }

    @Test
    void bearerScopeAndOwnerBindSubmissionReplayAndRead() throws Exception {
        UUID recipeId = recipe();
        String requestKey = "request-" + UUID.randomUUID();
        String body = request(recipeId, DIGEST);
        String firstBody = mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(firstBody).get("jobId").asText());
        jobs.add(jobId);
        assertNotNull(jobId);
        assertEquals("oidc:", jdbc.queryForObject(
                "SELECT LEFT(requester_subject, 5) FROM jobs WHERE id = ?", String.class, jobId));

        mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(get("/api/v1/jobs/{jobId}", jobId)
                        .with(requester("alice", "jobs:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.inputArtifactPrefix").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{jobId}", jobId)
                        .with(requester("bob", "jobs:read")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json").content(request(recipeId, "b".repeat(64))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("bob", "jobs:write")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("alice", "jobs:read")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'JOB_CANCELED'", Long.class, jobId));
    }

    @Test
    void boundedAuthenticatedUploadQueuesOnlyVerifiedOwnerInput() throws Exception {
        UUID recipeId = recipe();
        String requestKey = "upload-" + UUID.randomUUID();
        byte[] file = "api-upload-input".getBytes(StandardCharsets.US_ASCII);
        byte[] manifest = ("{\"files\":[{\"path\":\"input.txt\",\"sha256\":\""
                + sha256(file) + "\",\"sizeBytes\":" + file.length
                + "}],\"schemaVersion\":1}").getBytes(StandardCharsets.US_ASCII);
        String body = mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json")
                        .content(request(recipeId, sha256(manifest))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("jobId").asText());
        jobs.add(jobId);

        mvc.perform(post("/api/v1/jobs/{jobId}/queue", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/manifest", jobId)
                        .with(requester("bob", "jobs:write"))
                        .contentType("application/json").content(manifest))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/manifest", jobId)
                        .with(requester("alice", "jobs:write"))
                        .contentType("application/json")
                        .content(new byte[InputManifestV1.MAX_BYTES + 1]))
                .andExpect(status().is(413));
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/manifest", jobId)
                        .with(requester("alice", "jobs:write"))
                        .contentType("application/json").content(manifest))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/manifest", jobId)
                        .with(requester("alice", "jobs:write"))
                        .contentType("application/json").content(manifest))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/files", jobId)
                        .with(requester("alice", "jobs:write"))
                        .param("path", "input.txt")
                        .contentType("application/octet-stream").content(file))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/jobs/{jobId}/queue", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/v1/jobs/{jobId}/queue", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(get("/api/v1/jobs/{jobId}", jobId)
                        .with(requester("alice", "jobs:read")))
                .andExpect(jsonPath("$.state").value("QUEUED"));
        mvc.perform(put("/api/v1/jobs/{jobId}/inputs/files", jobId)
                        .with(requester("alice", "jobs:write"))
                        .param("path", "input.txt")
                        .contentType("application/octet-stream").content(file))
                .andExpect(status().isNotFound());
        // Simulate the state after a scheduler claim to prove the public API
        // never treats an issued lease as safely stopped.
        jdbc.update("UPDATE jobs SET state = 'LEASED' WHERE id = ?", jobId);
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE jobs SET state = 'QUEUED' WHERE id = ?", jobId);
        mvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId)
                        .with(requester("alice", "jobs:write")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELED"));
        mvc.perform(get("/api/v1/jobs/{jobId}", jobId)
                        .with(requester("alice", "jobs:read")))
                .andExpect(jsonPath("$.state").value("CANCELED"));
    }

    @Test
    void unauthenticatedWrongScopeAndInvalidInputCannotCreateJobs() throws Exception {
        UUID recipeId = recipe();
        String body = request(recipeId, DIGEST);
        mvc.perform(post("/api/v1/jobs").header("Idempotency-Key", "request-2")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:read"))
                        .header("Idempotency-Key", "request-2")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", "invalid key")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs").with(requester("alice", "jobs:write"))
                        .header("Idempotency-Key", "request-2")
                        .contentType("application/json").content(request(recipeId, "bad-digest")))
                .andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE recipe_id = ?", Long.class, recipeId));
    }

    private UUID recipe() {
        UUID id = UUID.randomUUID();
        recipes.add(id);
        jdbc.update("""
                INSERT INTO workload_recipes
                    (id, name, image_repository, image_digest, max_runtime_seconds,
                     min_vram_bytes, state, approved_at)
                VALUES (?, ?, 'registry.example.invalid/hyperlooms/test', ?,
                        60, 1073741824, 'APPROVED', CURRENT_TIMESTAMP)
                """, id, "api-integration-" + id, "sha256:" + "a".repeat(64));
        return id;
    }

    private static String request(UUID recipeId, String digest) {
        return "{\"recipeId\":\"" + recipeId + "\",\"inputManifestSha256\":\"" + digest + "\"}";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            requester(String subject, String scope) {
        return jwt().jwt(token -> token.claim("iss", "https://issuer.example.invalid")
                        .claim("sub", subject).claim("aud", List.of("hyperlooms-api")))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
}
