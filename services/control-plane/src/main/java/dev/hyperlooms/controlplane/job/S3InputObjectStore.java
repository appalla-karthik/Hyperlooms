package dev.hyperlooms.controlplane.job;

import java.io.InputStream;
import java.util.Objects;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3-compatible adapter. The caller owns client lifecycle and credential scope. */
public final class S3InputObjectStore implements InputObjectStore {

    private final S3Client client;
    private final String bucket;

    public S3InputObjectStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket is required");
        }
        this.bucket = bucket;
    }

    @Override
    public InputStream get(String key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void put(String key, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(content));
    }
}
