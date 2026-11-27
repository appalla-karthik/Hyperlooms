package dev.hyperlooms.controlplane.job;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hyperlooms.requester-api.enabled", havingValue = "true")
public class RequesterInputStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client requesterInputS3Client(
            @Value("${hyperlooms.requester-api.storage.region:}") String region,
            @Value("${hyperlooms.requester-api.storage.endpoint:}") String endpoint,
            @Value("${hyperlooms.requester-api.storage.path-style:false}") boolean pathStyle,
            @Value("${hyperlooms.requester-api.storage.allow-insecure-local-endpoint:false}")
            boolean allowInsecureLocal,
            @Value("${hyperlooms.requester-api.storage.access-key:}") String accessKey,
            @Value("${hyperlooms.requester-api.storage.secret-key:}") String secretKey) {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("Requester input S3 region is required");
        }
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region))
                .forcePathStyle(pathStyle);
        if (endpoint != null && !endpoint.isBlank()) {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Requester input S3 endpoint is invalid", exception);
            }
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean local = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost()));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !(secure || (allowInsecureLocal && local))) {
                throw new IllegalStateException("Requester input S3 endpoint must use HTTPS");
            }
            builder.endpointOverride(uri);
        }
        if ((accessKey == null || accessKey.isBlank()) != (secretKey == null || secretKey.isBlank())) {
            throw new IllegalStateException("Requester input S3 credentials must be complete");
        }
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }

    @Bean
    InputObjectStore requesterInputObjectStore(S3Client requesterInputS3Client,
            @Value("${hyperlooms.requester-api.storage.bucket:}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("Requester input S3 bucket is required");
        }
        return new S3InputObjectStore(requesterInputS3Client, bucket);
    }

    @Bean
    InputStagingService requesterInputStaging(JdbcTemplate jdbc, InputObjectStore store) {
        return new InputStagingService(jdbc, store);
    }

    @Bean
    JobInputQueueService requesterInputQueue(JdbcTemplate jdbc,
            TransactionTemplate transactions, InputObjectStore store) {
        return new JobInputQueueService(jdbc, transactions, new InputSnapshotStager(store));
    }

    @Bean
    ScopedInputReadService scopedInputReader(JdbcTemplate jdbc,
            LeaseExecutionPlanService plans, InputObjectStore store) {
        return new ScopedInputReadService(jdbc, plans, store);
    }
}
