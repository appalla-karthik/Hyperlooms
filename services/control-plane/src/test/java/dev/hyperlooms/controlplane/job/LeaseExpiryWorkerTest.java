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

class LeaseExpiryWorkerTest {

    private final LeaseExpiryService expirations = mock(LeaseExpiryService.class);
    private final LeaseExpiryWorker worker = new LeaseExpiryWorker(expirations);

    @Test
    void emptyPollMakesOneCheck() {
        when(expirations.expireNext()).thenReturn(Optional.empty());

        assertEquals(0, worker.drainOnce());
        verify(expirations).expireNext();
    }

    @Test
    void pollStopsAtBoundEvenWhenMoreWorkExists() {
        LeaseExpiration expired = new LeaseExpiration(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1);
        when(expirations.expireNext()).thenReturn(Optional.of(expired));

        assertEquals(LeaseExpiryWorker.MAX_EXPIRATIONS_PER_POLL, worker.drainOnce());
        verify(expirations, times(LeaseExpiryWorker.MAX_EXPIRATIONS_PER_POLL)).expireNext();
    }

    @Test
    void databaseFailureIsNotSwallowed() {
        when(expirations.expireNext()).thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertThrows(DataAccessResourceFailureException.class, worker::drainOnce);
    }
}
