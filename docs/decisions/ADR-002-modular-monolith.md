# ADR-002: Modular Monolith Control Plane

- Status: Accepted
- Date: 2026-11-20

## Decision

Implement the control plane as one deployable Spring Boot application with enforced internal modules.

## Rationale

The early product needs strong transactions across jobs, leases, artifacts, accounting, and audit history. Independent microservices would add network failure modes and operational overhead before there is evidence that separate scaling or ownership is required.

## Consequences

Modules expose deliberate interfaces and avoid cross-module table manipulation. Stateless instances may scale horizontally. A module may be extracted later only with a measured reason and migration plan.
