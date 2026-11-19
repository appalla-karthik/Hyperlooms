# Component Responsibilities

## Web dashboard

Owns presentation, form validation, session-aware navigation, live status display, and generated API-client use. It does not contain authoritative scheduling, credit, or trust rules.

## Control plane

Owns authentication integration, authorization, provider inventory, job state, scheduling, leases, artifact metadata, circles, accounting, reputation, audit history, and administrative policy.

## Provider agent

Owns host capability discovery, secure control-plane connectivity, local provider policy, image verification, sandbox lifecycle, telemetry, artifact transfer, graceful draining, and emergency stop behavior. It cannot mint credits or decide final business state.

## Workload image and Python SDK

Own recipe-specific validation, deterministic entrypoints where possible, checkpoint contracts, progress reporting, and output manifests. Workloads receive scoped inputs and credentials, never control-plane database access.

## PostgreSQL

Owns authoritative relational state, state-transition transactions, leases, idempotency records, transactional outbox entries, accounting, and audit history.

## Artifact storage

Stores immutable inputs, outputs, checkpoints, and large logs. Access uses short-lived, least-privilege URLs or credentials. Database records store ownership, digest, size, retention, and storage key.
