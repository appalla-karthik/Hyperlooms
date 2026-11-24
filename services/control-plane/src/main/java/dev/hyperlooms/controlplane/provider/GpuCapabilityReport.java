package dev.hyperlooms.controlplane.provider;

public record GpuCapabilityReport(
        String gpuUuid,
        String model,
        long vramBytes,
        String driverVersion,
        String cudaVersion) {

    public GpuCapabilityReport {
        requireText(gpuUuid, 100, "gpuUuid");
        requireText(model, 200, "model");
        requireText(driverVersion, 100, "driverVersion");
        requireText(cudaVersion, 100, "cudaVersion");
        if (vramBytes <= 0) {
            throw new IllegalArgumentException("vramBytes must be positive");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
    }
}
