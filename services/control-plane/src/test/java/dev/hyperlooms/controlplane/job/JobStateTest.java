package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobStateTest {

    @Test
    void normalExecutionPathIsAllowed() {
        JobState[] path = {
            JobState.CREATED, JobState.UPLOADING, JobState.QUEUED, JobState.LEASED,
            JobState.STARTING, JobState.RUNNING, JobState.UPLOADING_RESULTS, JobState.SUCCEEDED
        };
        for (int index = 0; index < path.length - 1; index++) {
            JobState current = path[index];
            JobState next = path[index + 1];
            assertTrue(current.canTransitionTo(next));
            assertDoesNotThrow(() -> current.requireTransitionTo(next));
        }
    }

    @Test
    void retryRequiresReturningThroughQueue() {
        assertTrue(JobState.RUNNING.canTransitionTo(JobState.RETRY_WAIT));
        assertTrue(JobState.RETRY_WAIT.canTransitionTo(JobState.QUEUED));
        assertFalse(JobState.RETRY_WAIT.canTransitionTo(JobState.RUNNING));
        assertFalse(JobState.RUNNING.canTransitionTo(JobState.LEASED));
    }

    @Test
    void terminalAndBackwardTransitionsAreRejected() {
        for (JobState terminal : new JobState[] {
            JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELED, JobState.EXPIRED
        }) {
            for (JobState next : JobState.values()) {
                assertFalse(terminal.canTransitionTo(next));
                assertThrows(IllegalStateException.class, () -> terminal.requireTransitionTo(next));
            }
        }
        assertFalse(JobState.QUEUED.canTransitionTo(JobState.UPLOADING));
        assertFalse(JobState.LEASED.canTransitionTo(JobState.LEASED));
        assertFalse(JobState.CREATED.canTransitionTo(null));
        assertThrows(NullPointerException.class, () -> JobState.CREATED.requireTransitionTo(null));
    }
}
