package dev.hyperlooms.controlplane.job;

import java.util.Objects;

/** Authoritative job transitions; retries create a new attempt, never rewrite attempt history. */
public enum JobState {
    CREATED,
    UPLOADING,
    QUEUED,
    LEASED,
    STARTING,
    RUNNING,
    UPLOADING_RESULTS,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELED,
    EXPIRED;

    public boolean canTransitionTo(JobState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> next == UPLOADING || next == QUEUED || next == CANCELED;
            case UPLOADING -> next == QUEUED || next == FAILED || next == CANCELED;
            case QUEUED -> next == LEASED || next == CANCELED || next == EXPIRED;
            case LEASED -> next == STARTING || next == RETRY_WAIT || next == CANCELED;
            case STARTING -> next == RUNNING || next == RETRY_WAIT || next == FAILED || next == CANCELED;
            case RUNNING -> next == UPLOADING_RESULTS || next == RETRY_WAIT || next == FAILED || next == CANCELED;
            case UPLOADING_RESULTS -> next == SUCCEEDED || next == RETRY_WAIT || next == FAILED || next == CANCELED;
            case RETRY_WAIT -> next == QUEUED || next == FAILED || next == CANCELED || next == EXPIRED;
            case SUCCEEDED, FAILED, CANCELED, EXPIRED -> false;
        };
    }

    public void requireTransitionTo(JobState next) {
        Objects.requireNonNull(next, "next state");
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Job transition " + this + " -> " + next + " is not allowed");
        }
    }
}
