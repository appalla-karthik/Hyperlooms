# Phase 1 Execution Core

This first Phase 1 increment defines durable records and constraints, not an operational GPU scheduler.

## Records

- `provider_nodes` stores a provider identity and enrollment/availability state. A claimed node ID must later be bound to its mTLS client certificate; an agent-supplied string is not proof of identity.
- `provider_gpus` stores reported hardware separately from verification state. Reports are never schedulable solely because the agent sent them.
- `workload_recipes` identifies an OCI repository and immutable SHA-256 digest, with explicit approval and resource ceilings.
- `jobs` records the requester, recipe, state, request hash, and a requester-scoped idempotency key. Reusing a key with a different hash must be rejected by the future submission API.
- `job_attempts` preserves each execution attempt. Retry creates a higher attempt number rather than erasing prior evidence.
- `gpu_leases` has an expiry and database-issued, monotonic fencing token. A partial unique index permits at most one active lease per `(node_id, gpu_uuid)`. A completion from an old token must later be rejected even if the old process kept running.
- `outbox_events` will be written in the same transaction as a business transition before asynchronous delivery. No dispatcher is implemented yet.

The database also limits one active attempt per job. Scheduler transactions must atomically claim a queued job, create its attempt and lease, and update its state; these application operations are not yet implemented. The live PostgreSQL CI smoke tests the migration, idempotency uniqueness, exclusive lease, fencing sequence, and outbox insertion inside a rolled-back transaction.

## Protocol boundary

`proto/hyperlooms/agent/v1/agent_session.proto` defines only a versioned agent hello, GPU capability report, and heartbeat exchange. It deliberately does not define job assignment or completion before the command/lease runtime is implemented. Transport is planned as an outbound bidirectional gRPC stream over mTLS, with bounded payloads; artifacts remain on the S3 data path.

## Next implementation increment

Implement authenticated, controlled provider enrollment and persistence adapters, then a single approved recipe and idempotent job submission. Only after those are tested should scheduling and agent job commands be wired. Real execution qualification requires a Linux NVIDIA host.
