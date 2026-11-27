package dev.hyperlooms.controlplane.job;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

import dev.hyperlooms.controlplane.provider.ProviderPeerIdentity;

/** Verifies bounded result integrity and schema, not the mathematical honesty of an untrusted node. */
@Service
public final class VerifiedResultService {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LeaseRuntimeService runtime;
    private final ObjectProvider<InputObjectStore> stores;

    public VerifiedResultService(JdbcTemplate jdbc, TransactionTemplate transactions,
            LeaseRuntimeService runtime, ObjectProvider<InputObjectStore> stores) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runtime = runtime;
        this.stores = stores;
    }

    public String complete(ProviderPeerIdentity peer, UUID leaseId, UUID commandId,
            long token, byte[] data, String declaredHash) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES || declaredHash == null
                || !declaredHash.equals(sha256(data))) {
            throw new LeaseExecutionDeniedException();
        }
        validateRecipeResult(data);
        LeaseRuntimeService.Candidate candidate = transactions.execute(status -> {
            var current = runtime.currentLocked(peer, leaseId, commandId, token);
            if (!("RUNNING".equals(current.jobState()) && "RUNNING".equals(current.attemptState()))
                    && !("UPLOADING_RESULTS".equals(current.jobState())
                            && "UPLOADING_RESULTS".equals(current.attemptState()))) {
                throw new LeaseExecutionDeniedException();
            }
            if ("RUNNING".equals(current.jobState())) {
                jdbc.update("UPDATE job_attempts SET state = 'UPLOADING_RESULTS' WHERE id = ?", current.attemptId());
                jdbc.update("UPDATE jobs SET state = 'UPLOADING_RESULTS', updated_at = CURRENT_TIMESTAMP WHERE id = ?", current.jobId());
            }
            return current;
        });
        InputObjectStore store = stores.getIfAvailable();
        if (store == null) {
            throw new LeaseExecutionDeniedException();
        }
        String key = resultKey(candidate.jobId(), candidate.attemptId());
        if (!store.putIfAbsent(key, data)) {
            byte[] existing = readBounded(store, key);
            if (!MessageDigest.isEqual(data, existing)) {
                throw new LeaseExecutionDeniedException();
            }
        }
        byte[] readback = readBounded(store, key);
        if (!MessageDigest.isEqual(data, readback)) {
            throw new LeaseExecutionDeniedException();
        }
        transactions.executeWithoutResult(status -> {
            var current = runtime.currentLocked(peer, leaseId, commandId, token);
            if (!candidate.jobId().equals(current.jobId())
                    || !candidate.attemptId().equals(current.attemptId())
                    || !"UPLOADING_RESULTS".equals(current.jobState())
                    || !"UPLOADING_RESULTS".equals(current.attemptState())) {
                throw new LeaseExecutionDeniedException();
            }
            jdbc.update("UPDATE job_attempts SET state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP WHERE id = ?",
                    current.attemptId());
            jdbc.update("UPDATE jobs SET state = 'SUCCEEDED', result_artifact_prefix = ?, result_sha256 = ?, "
                    + "result_size_bytes = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    key, declaredHash, data.length, current.jobId());
            jdbc.update("UPDATE gpu_leases SET state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", leaseId);
            // Explicit operator re-enable after every run until crash/recovery qualification.
            jdbc.update("UPDATE provider_nodes SET availability_state = 'OFFLINE', "
                    + "updated_at = CURRENT_TIMESTAMP WHERE id = ?", peer.nodeId());
            jdbc.update("""
                    INSERT INTO outbox_events (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, 'JOB', ?, 'JOB_SUCCEEDED',
                        jsonb_build_object('jobId', CAST(? AS text), 'attemptId', CAST(? AS text),
                            'leaseId', CAST(? AS text), 'resultSha256', ?))
                    """, UUID.randomUUID(), "lease:" + leaseId + ":succeeded", current.jobId(),
                    current.jobId(), current.attemptId(), leaseId, declaredHash);
        });
        return declaredHash;
    }

    public byte[] readOwned(UUID jobId, String requester) {
        List<ResultRecord> rows = jdbc.query("""
                SELECT result_artifact_prefix, result_sha256, result_size_bytes
                FROM jobs WHERE id = ? AND requester_subject = ? AND request_version = 1
                  AND state = 'SUCCEEDED' AND result_artifact_prefix IS NOT NULL
                """, (rs, index) -> new ResultRecord(rs.getString(1), rs.getString(2).trim(), rs.getLong(3)),
                jobId, requester);
        if (rows.size() != 1) {
            throw new ResultUnavailableException();
        }
        ResultRecord record = rows.get(0);
        if (!record.key().matches("jobs/" + jobId + "/results/[0-9a-f-]{36}/result\\.json")
                || record.length() < 1 || record.length() > MAX_BYTES) {
            throw new ResultUnavailableException();
        }
        InputObjectStore store = stores.getIfAvailable();
        if (store == null) {
            throw new ResultUnavailableException();
        }
        byte[] bytes = readBounded(store, record.key());
        if (bytes.length != record.length() || !sha256(bytes).equals(record.hash())) {
            throw new ResultUnavailableException();
        }
        return bytes;
    }

    private static String resultKey(UUID job, UUID attempt) {
        return "jobs/" + job + "/results/" + attempt + "/result.json";
    }

    private static byte[] readBounded(InputObjectStore store, String key) {
        try (InputStream input = store.get(key)) {
            byte[] data = input.readNBytes(MAX_BYTES + 1);
            if (data.length == 0 || data.length > MAX_BYTES) {
                throw new ResultUnavailableException();
            }
            return data;
        } catch (IOException | InputObjectMissingException exception) {
            throw new ResultUnavailableException();
        }
    }

    private static void validateRecipeResult(byte[] data) {
        try {
            JsonNode root = JSON.readTree(data);
            if (root == null || !root.isObject() || root.size() != 6
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1
                    || !"cuda".equals(root.path("device").textValue())
                    || !root.path("epochs").isIntegralNumber()
                    || root.path("epochs").intValue() < 1 || root.path("epochs").intValue() > 100
                    || !root.path("weights").isArray() || root.path("weights").isEmpty()
                    || root.path("weights").size() > 64
                    || !finite(root.path("bias")) || !finite(root.path("meanSquaredError"))
                    || root.path("meanSquaredError").doubleValue() < 0) {
                throw new LeaseExecutionDeniedException();
            }
            for (JsonNode weight : root.path("weights")) {
                if (!finite(weight)) {
                    throw new LeaseExecutionDeniedException();
                }
            }
        } catch (IOException exception) {
            throw new LeaseExecutionDeniedException();
        }
    }

    private static boolean finite(JsonNode number) {
        return number.isNumber() && Double.isFinite(number.doubleValue());
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ResultRecord(String key, String hash, long length) { }

    public static final class ResultUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
