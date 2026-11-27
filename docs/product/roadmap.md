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

Status: in progress. Durable execution state, an agent hello/heartbeat/transport-offer contract, an opt-in authenticated requester submit/upload/queue/read API, transactional scheduler claim with an experimental bounded poller, opt-in bounded lease-expiry polling, certificate-bound lease receipt, internal fenced start/running/failure transitions, and an unconnected restricted Docker runtime foundation now exist alongside pending-node invites, operator certificate operations, an opt-in mTLS agent session with local receipt journal, a read-only provider console, and a Linux service unit. A loopback-only Keycloak development issuer and a signed, published PyTorch recipe **candidate** now exist. PostgreSQL/S3 intake integration, migration/schema smoke, claim/expiry/receipt/execution-state tests, and real-certificate offer/receipt mTLS integration tests passed locally (see the [verification record](../development/phase-one-verification.md)). The requester API is disabled by default; production identity deployment is absent; both pollers are disabled by default; execution-state transitions and Docker runtime have no worker/agent caller. A transport offer cannot execute a job. Linux NVIDIA host qualification, provider installer/setup flow, NVIDIA-tested and operator-approved runnable recipe, bucket policy/cleanup, verified hardware, safe provider-availability transitions, activated lease recovery/renewal, runtime crash recovery, and live GPU execution are still pending. Production PKI issuance and operator SSO/RBAC are not yet implemented.

### Remaining Phase 1 subphases (exit gates, not dates)

1. **1A — Host qualification:** a read-only provider preflight, then a witnessed Linux amd64 NVIDIA Container Toolkit and restricted-container smoke on the actual provider host. The preflight alone cannot qualify execution. The only currently available physical device is an original Jetson Nano; its [operator-reported host, root-container, and restricted non-root CUDA smokes](../development/jetson-host-survey.md) establish an ARM64 diagnostic baseline. The restricted run verified a kernel result and exact container cleanup, but not the agent's lease-bound runtime, PyTorch, artifact exchange, or remote execution. This does not satisfy the amd64 exit gate or make Jetson schedulable.
2. **1B — Trusted provider:** production certificate issuance and operator authorization, verified hardware evidence, and an explicit, revocable `AVAILABLE` transition. A trusted local manual GPU review record now binds an unchanged report to an operator-supplied evidence digest, but there is no automated attestation, authenticated reviewer, evidence custody, or live host qualification. A GPU report or review must never grant availability by itself.
3. **1C — Requester intake:** authenticated requester identity, canonical versioned job request/idempotency contract, approved PyTorch recipe, private input staging, and guarded `CREATED -> QUEUED` transition. The v1 request contract, Python manifest builder and requester intake client, strict Java parser, opt-in OIDC/JWT scope-gated API, owner-scoped reads, bounded server-mediated S3 staging, create-once object writes, byte-verified snapshot, and transactional queue event now exist. A loopback-only Keycloak realm and bounded, signed/published PyTorch recipe candidate are present. Production identity deployment, GPU-host testing and operator approval of the image, production ingress/abuse controls, bucket write-once policy, and orphan cleanup remain open. The API does not execute a job.
4. **1D — Orchestration:** a bounded, default-off scheduler claim worker and owner-scoped cancellation of unleased jobs now exist. An internal, certificate/fencing-bound execution-plan reader rejects legacy or unverified-input jobs and binds an acknowledged lease to the approved recipe image and verified input snapshot. The control plane persists one immutable prepared-command snapshot and outbox event per lease, then can deliver it over the authenticated gRPC stream when the default-off command-staging gate and agent capability are both enabled. The agent validates and syncs the command to its private journal before reporting staging; the server records that receipt. A real-certificate mTLS integration test passed for this exchange. A separately gated mTLS `ReadInput` RPC now exposes only the verified, bounded `request.json` for the staged command; the opt-in agent checks chunk order, length, digest, and JSON, then discards it. This is **staging and input transfer, not execution**: lease renewal, safe stop, image run, result upload and verification are not wired. Claim polling must not be enabled for real users until those execution and recovery gates are implemented and qualified.
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

### Provider compatibility sequence

The first execution-qualified provider target remains **Linux x86-64 with a supported NVIDIA GPU**. After that one-node path passes on real hardware, qualify Jetson/ARM64, AMD GPU hosts, and Windows hosts as separate tracks with their own driver/runtime, image, isolation, packaging, and recovery evidence. A Ryzen CPU alone is not GPU eligibility; an AMD GPU host must be qualified by its actual GPU and software stack. None of these later tracks is currently supported or promised for Phase 1.

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
