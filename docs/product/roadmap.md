# Roadmap

Roadmap entries are outcome gates, not date promises.

## Phase 0 — Engineering foundation

Status: complete. The [verification record](../development/phase-zero-verification.md) links the live-stack and component CI evidence.

- approved product and architecture baseline;
- modular monorepo scaffolding;
- local PostgreSQL and S3-compatible storage;
- compilable Spring Boot, Go, and Next.js foundations;
- OpenAPI and health endpoints;
- CI quality gates and security documentation.

Exit criterion: a new developer can understand the system, start dependencies, build each component, and distinguish implemented behavior from planned behavior.

## Phase 1 — Single-node vertical slice

Status: in progress. Durable execution state, an agent hello/heartbeat/transport-offer contract, internal idempotent job creation, internal transactional scheduler claim, opt-in bounded lease-expiry polling, certificate-bound lease receipt, internal fenced start/running/failure transitions, and an unconnected restricted Docker runtime foundation now exist alongside pending-node invites, operator certificate operations, an opt-in mTLS agent session with local receipt journal, a read-only provider console, and a Linux service unit. PostgreSQL migration/schema smoke, claim/expiry/receipt/execution-state tests, and real-certificate offer/receipt mTLS integration tests passed locally (see the [verification record](../development/phase-one-verification.md)). Claim, execution-state transitions, and Docker runtime have no worker/agent caller; the expiry worker is disabled by default, and the transport offer cannot execute a job. Linux NVIDIA host qualification, provider installer/setup flow, authenticated public API, verified hardware, safe queue/availability transitions, activated lease recovery/renewal, artifact staging and verification, runtime crash recovery, and live GPU execution are still pending. Production PKI issuance and operator SSO/RBAC are not yet implemented.

### Remaining Phase 1 subphases (exit gates, not dates)

1. **1A — Host qualification:** a read-only provider preflight, then a witnessed Linux amd64 NVIDIA Container Toolkit and restricted-container smoke on the actual provider host. The preflight alone cannot qualify execution. The only currently available physical device is a Jetson Nano; its [read-only host survey](../development/jetson-host-survey.md) starts a separate ARM64 compatibility track and does not satisfy the amd64 exit gate.
2. **1B — Trusted provider:** production certificate issuance and operator authorization, verified hardware evidence, and an explicit, revocable `AVAILABLE` transition. A trusted local manual GPU review record now binds an unchanged report to an operator-supplied evidence digest, but there is no automated attestation, authenticated reviewer, evidence custody, or live host qualification. A GPU report or review must never grant availability by itself.
3. **1C — Requester intake:** authenticated requester identity, canonical versioned job request/idempotency contract, approved PyTorch recipe, private input staging, and guarded `CREATED -> QUEUED` transition. The internal v1 request hash/database fields, Python input-manifest builder, strict Java parser, and an internal S3-backed bounded byte-verification/snapshot path now exist. Upload authorization, authenticated requester identity, database-bound verified input, approved runnable recipe, bucket write-once policy, and queueing remain open.
4. **1D — Orchestration:** bounded scheduler worker, fenced executable lease command, agent-side durable acceptance, renewal/expiry/cancellation, and safe stop/quarantine behavior across disconnects.
5. **1E — Execution and evidence:** verified digest/signature policy, one selected GPU in the restricted runtime, bounded logs, result upload, artifact verification, and fenced terminal completion. Demonstrate crash/restart behavior.
6. **1F — Usable delivery:** provider installer and first-run enrollment, requester/operator job pages, observability/runbooks, and a reproducible one-provider/one-requester end-to-end acceptance run.

Phase 1 closes only when all six gates have passing evidence. Software work can proceed while 1A awaits a real Linux NVIDIA host; do not label the system executable or public-ready based on mocked Docker or database tests.

- one provider registers verified capabilities;
- one supported PyTorch recipe is submitted;
- scheduler creates a durable attempt and time-bounded lease;
- agent executes the approved image on one exclusive GPU;
- logs and status are visible;
- result artifacts are uploaded and retrievable;
- restart and disconnect behavior are demonstrated.

## Phase 2 — Multi-node reliability

- compatibility-aware scheduling;
- multiple provider nodes;
- lease renewal, expiry, fencing, retry, and cancellation;
- checkpoint-aware recipes;
- agent upgrade compatibility;
- measurable scheduling and completion SLOs.

## Phase 3 — Security and verification

- signed agents and images;
- hardened sandbox policies;
- restricted egress and short-lived artifact credentials;
- metering evidence, challenge workloads, and selected result verification;
- abuse controls, audit tooling, and security review.

## Phase 4 — Community product

- Compute Circles;
- reservation and policy rules;
- append-only double-entry credit ledger;
- Compute Passport;
- invitation-only real-user pilot.

## Phase 5 — Public-network readiness

- public provider onboarding;
- dispute and moderation workflows;
- legal, privacy, tax, and abuse-response readiness;
- tested backup, recovery, incident response, and on-call operations.
