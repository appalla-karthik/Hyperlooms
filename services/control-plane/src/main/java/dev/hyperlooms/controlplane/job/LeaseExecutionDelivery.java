package dev.hyperlooms.controlplane.job;

/** A current plan paired with its pinned prepared command for transport. */
public record LeaseExecutionDelivery(LeaseExecutionPlan plan, LeaseExecutionCommand command) {
}
