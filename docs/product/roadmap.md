# Roadmap

Roadmap entries are outcome gates, not date promises.

## Phase 0 — Engineering foundation

- approved product and architecture baseline;
- modular monorepo scaffolding;
- local PostgreSQL and S3-compatible storage;
- compilable Spring Boot, Go, and Next.js foundations;
- OpenAPI and health endpoints;
- CI quality gates and security documentation.

Exit criterion: a new developer can understand the system, start dependencies, build each component, and distinguish implemented behavior from planned behavior.

## Phase 1 — Single-node vertical slice

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
