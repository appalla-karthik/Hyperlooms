package dev.hyperlooms.controlplane.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LeaseExpirySchedulingConfigurationTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(LeaseExpiryService.class, () -> mock(LeaseExpiryService.class))
            .withUserConfiguration(LeaseExpirySchedulingConfiguration.class);

    @Test
    void expiryPollingIsAbsentByDefault() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(LeaseExpiryWorker.class));
    }

    @Test
    void explicitFlagRegistersWorker() {
        contexts.withPropertyValues("hyperlooms.lease-expiry.enabled=true",
                "hyperlooms.lease-expiry.poll-interval-ms=60000")
                .run(context -> assertThat(context).hasSingleBean(LeaseExpiryWorker.class));
    }
}
