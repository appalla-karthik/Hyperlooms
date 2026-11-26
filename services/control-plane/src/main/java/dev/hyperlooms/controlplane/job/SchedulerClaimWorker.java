package dev.hyperlooms.controlplane.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded, opt-in polling; each claim commits in its own transaction. */
final class SchedulerClaimWorker {

    static final int MAX_CLAIMS_PER_POLL = 16;
    private static final Logger LOG = LoggerFactory.getLogger(SchedulerClaimWorker.class);

    private final SchedulerClaimService claims;

    SchedulerClaimWorker(SchedulerClaimService claims) {
        this.claims = claims;
    }

    @Scheduled(fixedDelayString = "${hyperlooms.scheduler.poll-interval-ms:5000}")
    void poll() {
        int count = drainOnce();
        if (count > 0) {
            LOG.info("Issued {} GPU leases", count);
        }
    }

    int drainOnce() {
        int count = 0;
        while (count < MAX_CLAIMS_PER_POLL && claims.claimNext().isPresent()) {
            count++;
        }
        return count;
    }
}
