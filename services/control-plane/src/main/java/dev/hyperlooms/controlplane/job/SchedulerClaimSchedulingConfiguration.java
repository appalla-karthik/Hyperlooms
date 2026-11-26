package dev.hyperlooms.controlplane.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Experimental claim polling remains off until executable commands and recovery exist. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hyperlooms.scheduler", name = "enabled", havingValue = "true")
@EnableScheduling
class SchedulerClaimSchedulingConfiguration {

    @Bean
    SchedulerClaimWorker schedulerClaimWorker(SchedulerClaimService claims) {
        return new SchedulerClaimWorker(claims);
    }
}
