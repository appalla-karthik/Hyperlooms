package dev.hyperlooms.controlplane.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists bounded hello reports; a reported GPU is not automatically verified. */
@Service
public class ProviderCapabilityService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProviderIdentityService identities;

    public ProviderCapabilityService(
            JdbcTemplate jdbc, TransactionTemplate transactions, ProviderIdentityService identities) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.identities = identities;
    }

    /** Call only after the transport has authenticated the presented mTLS peer certificate. */
    public void recordFromAuthenticatedPeer(
            ProviderPeerIdentity peer, ProviderCapabilityReport report) {
        if (peer == null || report == null || !peer.nodeId().equals(report.claimedNodeId())) {
            throw new ProviderIdentityDeniedException();
        }
        transactions.executeWithoutResult(status -> {
            identities.requireActivePeerLocked(peer);
            jdbc.update("""
                    UPDATE provider_nodes
                    SET agent_version = ?, protocol_version = ?, last_seen_at = CURRENT_TIMESTAMP,
                        availability_state = 'OFFLINE', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, report.agentVersion(), report.protocolVersion(), peer.nodeId());

            Map<String, StoredGpu> existing = new HashMap<>();
            List<StoredGpu> oldGpus = jdbc.query("""
                    SELECT gpu_uuid, model, vram_bytes, driver_version, cuda_version,
                           verification_state
                    FROM provider_gpus WHERE node_id = ? FOR UPDATE
                    """, (rs, row) -> new StoredGpu(
                    rs.getString("gpu_uuid"), rs.getString("model"), rs.getLong("vram_bytes"),
                    rs.getString("driver_version"), rs.getString("cuda_version"),
                    rs.getString("verification_state")), peer.nodeId());
            for (StoredGpu gpu : oldGpus) {
                existing.put(gpu.gpuUuid(), gpu);
            }

            for (GpuCapabilityReport gpu : report.gpus()) {
                StoredGpu previous = existing.remove(gpu.gpuUuid());
                if (previous != null && previous.matchesVerified(gpu)) {
                    jdbc.update("""
                            UPDATE provider_gpus SET reported_at = CURRENT_TIMESTAMP
                            WHERE node_id = ? AND gpu_uuid = ?
                            """, peer.nodeId(), gpu.gpuUuid());
                } else {
                    jdbc.update("""
                            INSERT INTO provider_gpus
                                (node_id, gpu_uuid, model, vram_bytes, driver_version,
                                 cuda_version, verification_state, verified_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'REPORTED', NULL)
                            ON CONFLICT (node_id, gpu_uuid) DO UPDATE SET
                                model = EXCLUDED.model,
                                vram_bytes = EXCLUDED.vram_bytes,
                                driver_version = EXCLUDED.driver_version,
                                cuda_version = EXCLUDED.cuda_version,
                                verification_state = 'REPORTED',
                                verified_at = NULL,
                                reported_at = CURRENT_TIMESTAMP
                            """, peer.nodeId(), gpu.gpuUuid(), gpu.model(), gpu.vramBytes(),
                            gpu.driverVersion(), gpu.cudaVersion());
                }
            }
            for (String missingGpuUuid : existing.keySet()) {
                jdbc.update("""
                        UPDATE provider_gpus
                        SET verification_state = 'REJECTED', verified_at = NULL
                        WHERE node_id = ? AND gpu_uuid = ?
                        """, peer.nodeId(), missingGpuUuid);
            }
            UUID eventId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO outbox_events
                        (id, event_key, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, 'PROVIDER_NODE', ?, 'PROVIDER_CAPABILITIES_REPORTED',
                        jsonb_build_object('nodeId', CAST(? AS text), 'gpuCount', ?))
                    """, eventId, "provider-node:" + peer.nodeId() + ":report:" + eventId,
                    peer.nodeId(), peer.nodeId(), report.gpus().size());
        });
    }

    private record StoredGpu(
            String gpuUuid, String model, long vramBytes, String driverVersion,
            String cudaVersion, String verificationState) {

        boolean matchesVerified(GpuCapabilityReport reported) {
            return "VERIFIED".equals(verificationState)
                    && model.equals(reported.model())
                    && vramBytes == reported.vramBytes()
                    && driverVersion.equals(reported.driverVersion())
                    && cudaVersion.equals(reported.cudaVersion());
        }
    }
}
