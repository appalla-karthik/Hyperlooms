# Phase 1 Execution Core

This first Phase 1 increment defines durable records and constraints, not an operational GPU scheduler.

## Records

- `provider_nodes` stores a provider identity and enrollment/availability state. A claimed node ID must later be bound to its mTLS client certificate; an agent-supplied string is not proof of identity.
- `provider_gpus` stores reported hardware separately from verification state. Reports are never schedulable solely because the agent sent them.
- `workload_recipes` identifies an OCI repository and immutable SHA-256 digest, with explicit approval and resource ceilings.
- `jobs` records the requester, recipe, state, request hash, and a requester-scoped idempotency key. The internal submission service now rejects a reused key with a different recipe or hash, and returns the same job for retries.
- `job_attempts` preserves each execution attempt. Retry creates a higher attempt number rather than erasing prior evidence.
- `gpu_leases` has an expiry and database-issued, monotonic fencing token. A partial unique index permits at most one active lease per `(node_id, gpu_uuid)`. A completion from an old token must later be rejected even if the old process kept running.
- `outbox_events` is written in the same transaction as initial job creation. No dispatcher is implemented yet.

The database also limits one active attempt per job. Scheduler transactions must atomically claim a queued job, create its attempt and lease, and update its state; these application operations are not yet implemented. The live PostgreSQL CI smoke tests the migration, idempotency uniqueness, exclusive lease, fencing sequence, and outbox insertion inside a rolled-back transaction. A separate live-database integration test exercises approved-recipe submission, retries, conflicts, and concurrent duplicate requests.

## Submission boundary

`JobSubmissionService` is deliberately internal: it accepts a trusted requester subject, an approved recipe ID, a caller-generated idempotency key, and a canonical request SHA-256. It commits a `CREATED` job and `JOB_CREATED` outbox event together. `CREATED` is not schedulable: inputs, authentication, artifact access, and the transition to `QUEUED` are not implemented yet. No public job API is exposed until a real authenticated subject can be bound to the request. The caller must compute the hash over a documented canonical request representation; that representation will be fixed with the authenticated API contract.

## Protocol boundary

`proto/hyperlooms/agent/v1/agent_session.proto` defines only a versioned agent hello, GPU capability report, and heartbeat exchange. It deliberately does not define job assignment or completion before the command/lease runtime is implemented. Transport is planned as an outbound bidirectional gRPC stream over mTLS, with bounded payloads; artifacts remain on the S3 data path.

## Next implementation increment

Implement authenticated, controlled provider enrollment and the canonical authenticated job API, then input staging and the `CREATED` to `QUEUED` transition. Only after those are tested should scheduling and agent job commands be wired. Real execution qualification requires a Linux NVIDIA host.
