package dev.hyperlooms.controlplane.provider;

import java.time.Instant;
import java.util.UUID;

/** The bearer token is returned once and must never be logged or persisted in plaintext. */
public record ProviderEnrollmentInvite(UUID inviteId, String token, Instant expiresAt) {

    @Override
    public String toString() {
        return "ProviderEnrollmentInvite[inviteId=" + inviteId
                + ", token=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
