package dev.hyperlooms.controlplane.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Expiry polling is opt-in until remote execution and fencing are fully wired. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hyperlooms.lease-expiry", name = "enabled", havingValue = "true")
@EnableScheduling
class LeaseExpirySchedulingConfiguration {

    @Bean
    LeaseExpiryWorker leaseExpiryWorker(LeaseExpiryService expirations) {
        return new LeaseExpiryWorker(expirations);
    }
}
