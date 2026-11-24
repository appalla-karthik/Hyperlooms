package dev.hyperlooms.controlplane.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ProviderCapabilityReport(
        UUID claimedNodeId,
        int protocolVersion,
        String agentVersion,
        List<GpuCapabilityReport> gpus) {

    public ProviderCapabilityReport {
        if (claimedNodeId == null) {
            throw new IllegalArgumentException("claimedNodeId is required");
        }
        if (protocolVersion != 1) {
            throw new IllegalArgumentException("unsupported agent protocol version");
        }
        if (agentVersion == null || agentVersion.isBlank() || agentVersion.length() > 100) {
            throw new IllegalArgumentException("agentVersion must contain 1-100 characters");
        }
        if (gpus == null || gpus.isEmpty() || gpus.size() > 16) {
            throw new IllegalArgumentException("between 1 and 16 GPUs must be reported");
        }
        gpus = List.copyOf(gpus);
        Set<String> uuids = new HashSet<>();
        for (GpuCapabilityReport gpu : gpus) {
            if (!uuids.add(gpu.gpuUuid())) {
                throw new IllegalArgumentException("duplicate gpuUuid in capability report");
            }
        }
    }
}
