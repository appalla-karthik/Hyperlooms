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

Status: in progress. The single-node software path now connects an authenticated requester console to owner-scoped job APIs, verified input staging, a PostgreSQL lease, certificate-bound gRPC command delivery, a default-off Go Docker worker, bounded renewal/stop, and digest-checked result storage. The worker is Linux amd64 NVIDIA-only and remains off by default. Existing database/S3 intake and real-certificate staging/input tests passed locally; the new execution/result integration tests and a complete Go-agent acceptance run still need passing evidence. A signed PyTorch image is only a **candidate**, not an operator-approved or GPU-tested release. Production identity, provider enrollment/installer, qualified NVIDIA host, runtime crash/restart exercise, artifact cleanup, availability controls, and live remote GPU execution are still open. See the [verification record](../development/phase-one-verification.md) for demonstrated evidence, not inferred readiness.

### Remaining Phase 1 subphases (exit gates, not dates)

1. **1A — Host qualification:** a read-only provider preflight, then a witnessed Linux amd64 NVIDIA Container Toolkit and restricted-container smoke on the actual provider host. The preflight alone cannot qualify execution. The only currently available physical device is an original Jetson Nano; its [operator-reported host, root-container, and restricted non-root CUDA smokes](../development/jetson-host-survey.md) establish an ARM64 diagnostic baseline. The restricted run verified a kernel result and exact container cleanup, but not the agent's lease-bound runtime, PyTorch, artifact exchange, or remote execution. This does not satisfy the amd64 exit gate or make Jetson schedulable.
2. **1B — Trusted provider:** production certificate issuance and operator authorization, verified hardware evidence, and an explicit, revocable `AVAILABLE` transition. A trusted local manual GPU review record now binds an unchanged report to an operator-supplied evidence digest, but there is no automated attestation, authenticated reviewer, evidence custody, or live host qualification. A GPU report or review must never grant availability by itself.
3. **1C — Requester intake:** the v1 request contract, Python manifest builder, strict Java parser, opt-in OIDC/JWT API, owner-scoped reads, bounded S3 staging, verified snapshot, and queue transition exist. The Next.js requester console now signs in through a server-side OIDC/BFF flow and submits, tracks, stops, and downloads through those APIs. Production identity, GPU-host testing and image approval, ingress/abuse controls, bucket policy, and orphan cleanup remain open. This is not yet a qualified end-to-end run.
4. **1D — Orchestration:** bounded default-off claims, fenced lease/command staging, and mTLS verified-input read are wired. The agent now persists an execution-attempt marker, requests fenced start/running transitions, and renews while Docker runs. An owner stop request immediately blocks renewal/result acceptance; reconciliation revokes the lease and marks the job canceled. The agent aborts Docker when renewal fails. This is cooperative stop, not proof of instantaneous remote termination. Do not enable claim polling for real users until the entire execution and recovery path is qualified.
5. **1E — Execution and evidence:** the default-off Linux amd64 worker invokes a digest-pinned, Cosign-verified image with one GPU, restricted Docker settings, bounded input/output, and cleanup. The agent uploads a bounded result through mTLS; the server validates digest and the current v1 CUDA result schema, performs create-once storage/readback, and fences terminal completion. These changes need full integration and real NVIDIA host evidence. The schema/integrity checks do **not** prove an untrusted provider computed an honest answer. Crash/restart behavior and orphan artifact cleanup remain open.
6. **1F — Usable delivery:** the requester job console is implemented, but provider installer and first-run enrollment, authenticated operator pages, observability/runbooks, and a reproducible one-provider/one-requester end-to-end acceptance run remain open.

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
