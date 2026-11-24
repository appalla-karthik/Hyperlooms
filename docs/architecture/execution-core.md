# Phase 1 Execution Core

This first Phase 1 increment defines durable records and constraints, not an operational GPU scheduler.

## Records

- `provider_nodes` stores a provider identity and enrollment/availability state. A claimed node ID must later be bound to its mTLS client certificate; an agent-supplied string is not proof of identity.
- `provider_enrollment_invites` stores a one-time, short-lived, revocable invite digest. Its 256-bit bearer token is returned only when issued, never persisted in plaintext. Redeeming it creates a `PENDING/OFFLINE` node, not a verified provider.
- `provider_certificates` binds a node to one active certificate fingerprint and, during rotation, at most one staged replacement. Binding is an operator-side expectation; the node becomes `VERIFIED/OFFLINE` only when that exact certificate is presented through an authenticated mTLS session. Revocation disables the credential and node.
- `provider_gpus` stores reported hardware separately from verification state. Reports are never schedulable solely because the agent sent them.
- `workload_recipes` identifies an OCI repository and immutable SHA-256 digest, with explicit approval and resource ceilings.
- `jobs` records the requester, recipe, state, request hash, and a requester-scoped idempotency key. The internal submission service now rejects a reused key with a different recipe or hash, and returns the same job for retries.
- `job_attempts` preserves each execution attempt. Retry creates a higher attempt number rather than erasing prior evidence.
- `gpu_leases` has an expiry and database-issued, monotonic fencing token. A partial unique index permits at most one active lease per `(node_id, gpu_uuid)`. A completion from an old token must later be rejected even if the old process kept running.
- `outbox_events` is written in the same transaction as initial job creation. No dispatcher is implemented yet.

The database also limits one active attempt per job. Scheduler transactions must atomically claim a queued job, create its attempt and lease, and update its state; these application operations are not yet implemented. The live PostgreSQL CI smoke tests the migration, idempotency uniqueness, exclusive lease, fencing sequence, and outbox insertion inside a rolled-back transaction. A separate live-database integration test exercises approved-recipe submission, retries, conflicts, and concurrent duplicate requests.

## Provider enrollment boundary

`ProviderEnrollmentService` is internal-only. A trusted operator workflow can issue an invite for an owner subject, with a 5-minute to 24-hour lifetime, and revoke it before use. The token is randomly generated and only its SHA-256 digest is stored. Redemption locks the invite row so concurrent retries create exactly one node and one `PROVIDER_NODE_PENDING` outbox event. The same token can replay the resulting node ID, but it does **not** authenticate later agent sessions. No invite issuance or redemption HTTP endpoint is exposed.

`ProviderIdentityService` can register an expected, operator-approved certificate for a pending node. The certificate must contain exactly one provider URI SAN in the form `urn:hyperlooms:provider:<canonical-lowercase-uuid>` matching the node ID. Its DER SHA-256 fingerprint is stored; a different certificate or a claimed node ID alone is not enough. The opt-in gRPC listener requires client certificates signed by its configured CA and extracts the peer certificate from the authenticated TLS session. The stream then checks the exact stored fingerprint and node state before accepting a hello. An operator-only, no-listener CLI can issue/redeem invites, validate and bind CA-issued certificates, stage a replacement, and revoke a node. During rotation the old certificate stays active until the replacement authenticates; then the switch is atomic and the node remains offline. Production PKI issuance and operator SSO/RBAC are **not** implemented. Do not expose these internal methods through an unauthenticated endpoint. See [provider identity operations](../security/provider-identity-operations.md).

`ProviderCapabilityService` accepts a bounded protocol-v1 hello report only for an active certificate-bound peer. It cross-checks the claimed node ID, records GPU attributes as `REPORTED`, and keeps the node `OFFLINE`. An unchanged, previously verified GPU remains verified; changed attributes reset it to `REPORTED`, and a GPU missing from the report becomes `REJECTED`. Actual hardware attestation/verification and the transition to `AVAILABLE` are still absent, so reports alone never authorize scheduling.

## Submission boundary

`JobSubmissionService` is deliberately internal: it accepts a trusted requester subject, an approved recipe ID, a caller-generated idempotency key, and a canonical request SHA-256. It commits a `CREATED` job and `JOB_CREATED` outbox event together. `CREATED` is not schedulable: inputs, authentication, artifact access, and the transition to `QUEUED` are not implemented yet. No public job API is exposed until a real authenticated subject can be bound to the request. The caller must compute the hash over a documented canonical request representation; that representation will be fixed with the authenticated API contract.

## Protocol boundary

`proto/hyperlooms/agent/v1/agent_session.proto` defines only a versioned agent hello, GPU capability report, and heartbeat exchange. It deliberately does not define job assignment or completion before the command/lease runtime is implemented. The control plane exposes a separate, disabled-by-default gRPC port with required mTLS and a 64 KiB inbound message cap. The Go agent can discover local NVIDIA GPUs and initiate the outbound stream when all TLS and node settings are supplied. Hello is required before heartbeats; wrong node identity, unsupported protocol, malformed reports, and non-increasing heartbeat sequences are rejected. Heartbeats update `last_seen_at` only and **do not** turn a reported GPU or node into schedulable capacity. Artifacts remain on the S3 data path. This transport has not yet been qualified in a live, cert-issued, database-backed, Linux NVIDIA test.

The agent also serves a read-only console and status JSON on its local health port. Console routes require a loopback peer and Host header. They show locally discovered NVIDIA hardware, authenticated-session state, and bounded local connection/hardware events; they do not provide enrollment, private-key upload, workload controls, or public access. Local hardware discovery does not imply control-plane verification.

## Next implementation increment

Qualify real NVIDIA discovery on a Linux host, integrate production PKI and operator SSO/RBAC, then implement verified hardware and safe availability transitions. Build the canonical authenticated job API, input staging, and `CREATED` to `QUEUED` transition. Only after those are tested should scheduling and agent job commands be wired. Real execution qualification requires a Linux NVIDIA host.
