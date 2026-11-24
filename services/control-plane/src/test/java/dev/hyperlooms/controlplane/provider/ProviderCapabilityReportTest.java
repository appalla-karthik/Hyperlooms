package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProviderCapabilityReportTest {

    @Test
    void validatesBoundsAndCopiesGpuList() {
        GpuCapabilityReport gpu = new GpuCapabilityReport(
                "GPU-1", "Test GPU", 8589934592L, "test-driver", "test-cuda");
        List<GpuCapabilityReport> mutable = new ArrayList<>();
        mutable.add(gpu);
        ProviderCapabilityReport report = new ProviderCapabilityReport(UUID.randomUUID(), 1, "1.0", mutable);
        mutable.clear();
        assertEquals(1, report.gpus().size());

        assertThrows(IllegalArgumentException.class,
                () -> new ProviderCapabilityReport(UUID.randomUUID(), 2, "1.0", List.of(gpu)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderCapabilityReport(UUID.randomUUID(), 1, "1.0", List.of(gpu, gpu)));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuCapabilityReport("GPU-1", "Test GPU", 0, "driver", "cuda"));
    }
}
