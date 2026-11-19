package dev.hyperlooms.controlplane.system;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
@Import(SystemStatusControllerTest.FixedClockConfiguration.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsFoundationStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("hyperlooms-control-plane"))
                .andExpect(jsonPath("$.phase").value("FOUNDATION"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern("2026-11-20.*")));
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
