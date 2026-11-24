package dev.hyperlooms.controlplane.job;

import java.time.Instant;
import java.util.UUID;

/** Durable receipt of an issued lease, not authorization to execute a workload. */
public record LeaseAcknowledgement(UUID leaseId, Instant expiresAt) {
}
