package dev.hyperlooms.controlplane.system;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final Clock clock;
    private final String applicationVersion;

    public SystemStatusController(
            Clock clock,
            @Value("${spring.application.version:development}") String applicationVersion) {
        this.clock = clock;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/status")
    public ResponseEntity<SystemStatusResponse> status() {
        return ResponseEntity.ok(new SystemStatusResponse(
                "hyperlooms-control-plane",
                applicationVersion,
                "FOUNDATION",
                Instant.now(clock)));
    }
}
