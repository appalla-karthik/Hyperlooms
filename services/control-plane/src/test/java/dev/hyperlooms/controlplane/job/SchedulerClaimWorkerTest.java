package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SchedulerClaimWorkerTest {

    private final SchedulerClaimService claims = mock(SchedulerClaimService.class);
    private final SchedulerClaimWorker worker = new SchedulerClaimWorker(claims);

    @Test
    void emptyPollChecksOnce() {
        when(claims.claimNext()).thenReturn(Optional.empty());

        assertEquals(0, worker.drainOnce());
        verify(claims).claimNext();
    }

    @Test
    void pollStopsAtBoundEvenWhenMoreWorkExists() {
        LeaseAssignment assignment = new LeaseAssignment(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "GPU-1", 1,
                java.time.Instant.now().plusSeconds(60));
        when(claims.claimNext()).thenReturn(Optional.of(assignment));

        assertEquals(SchedulerClaimWorker.MAX_CLAIMS_PER_POLL, worker.drainOnce());
        verify(claims, times(SchedulerClaimWorker.MAX_CLAIMS_PER_POLL)).claimNext();
    }

    @Test
    void databaseFailureIsNotSwallowed() {
        when(claims.claimNext()).thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertThrows(DataAccessResourceFailureException.class, worker::drainOnce);
    }
}
