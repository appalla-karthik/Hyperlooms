package dev.hyperlooms.controlplane.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded polling; each expiry commits in its own transaction. */
final class LeaseExpiryWorker {

    static final int MAX_EXPIRATIONS_PER_POLL = 64;
    private static final Logger LOG = LoggerFactory.getLogger(LeaseExpiryWorker.class);

    private final LeaseExpiryService expirations;

    LeaseExpiryWorker(LeaseExpiryService expirations) {
        this.expirations = expirations;
    }

    @Scheduled(fixedDelayString = "${hyperlooms.lease-expiry.poll-interval-ms:5000}")
    void poll() {
        int count = drainOnce();
        if (count > 0) {
            LOG.info("Reconciled {} expired GPU leases", count);
        }
    }

    int drainOnce() {
        int count = 0;
        while (count < MAX_EXPIRATIONS_PER_POLL && expirations.expireNext().isPresent()) {
            count++;
        }
        return count;
    }
}
