package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Tag("integration")
class S3InputSnapshotIntegrationTest {

    @Test
    void verifiesStagedObjectsAndReadsBackServerSnapshotFromPrivateS3() throws Exception {
        String bucket = System.getenv().getOrDefault("HYPERLOOMS_ARTIFACT_BUCKET",
                "hyperlooms-artifacts");
        String endpoint = System.getenv().getOrDefault("HYPERLOOMS_S3_ENDPOINT",
                "http://localhost:9000");
        String accessKey = System.getenv().getOrDefault("HYPERLOOMS_S3_ACCESS_KEY",
                "hyperlooms-local");
        String secretKey = System.getenv().getOrDefault("HYPERLOOMS_S3_SECRET_KEY",
                "local-development-only-change-me");
        UUID jobId = UUID.randomUUID();
        String jobPrefix = "jobs/" + jobId + "/";
        byte[] content = "staged-input".getBytes(StandardCharsets.US_ASCII);
        byte[] manifest = ("{\"files\":[{\"path\":\"input.txt\",\"sha256\":\""
                + sha256(content) + "\",\"sizeBytes\":" + content.length
                + "}],\"schemaVersion\":1}").getBytes(StandardCharsets.US_ASCII);

        try (S3Client client = S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build()) {
            S3InputObjectStore store = new S3InputObjectStore(client, bucket);
            try {
                store.put(jobPrefix + "staging/manifest.json", manifest);
                store.put(jobPrefix + "staging/files/input.txt", content);
                assertFalse(store.putIfAbsent(jobPrefix + "staging/files/input.txt",
                        "different".getBytes(StandardCharsets.US_ASCII)));
                try (var staged = store.get(jobPrefix + "staging/files/input.txt")) {
                    assertArrayEquals(content, staged.readAllBytes());
                }

                VerifiedInputSnapshot snapshot = new InputSnapshotStager(store)
                        .stage(jobId, sha256(manifest));

                assertEquals(1, snapshot.fileCount());
                assertEquals(content.length, snapshot.totalBytes());
                assertTrue(snapshot.prefix().startsWith(jobPrefix + "verified/"));
            } finally {
                client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket).prefix(jobPrefix).build()).contents().forEach(object ->
                        client.deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket).key(object.key()).build()));
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
