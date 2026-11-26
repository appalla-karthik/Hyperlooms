package dev.hyperlooms.controlplane.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SchedulerClaimSchedulingConfigurationTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(SchedulerClaimService.class, () -> mock(SchedulerClaimService.class))
            .withUserConfiguration(SchedulerClaimSchedulingConfiguration.class);

    @Test
    void pollingIsAbsentByDefault() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(SchedulerClaimWorker.class));
    }

    @Test
    void explicitFlagRegistersWorker() {
        contexts.withPropertyValues("hyperlooms.scheduler.enabled=true",
                "hyperlooms.scheduler.poll-interval-ms=60000")
                .run(context -> assertThat(context).hasSingleBean(SchedulerClaimWorker.class));
    }
}
