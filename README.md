# HYPER_LOOMS

Community-owned, verifiable GPU compute for AI workloads.

> **Development status:** Phase 0 is complete; Phase 1 is in progress. The execution schema, opt-in OIDC/JWT requester API, and certificate-authenticated provider transport exist. Local PostgreSQL/S3 intake and real-certificate mTLS offer/receipt/command-staging flows have passed tests. An approved workload image is still not registered for public use. Default-off gates can deliver a fenced, prepared command over gRPC and transfer its verified `request.json` over a separate scoped mTLS read. The agent durably stages the command and, when opted in, verifies and discards the bounded input bytes. These steps **do not run the command**. Scheduler and expiry pollers remain disabled by default. The restricted Docker runtime is not connected to the agent session. Production PKI, supported-host qualification, artifact-verified completion, and live remote GPU execution remain pending. The repository does not yet execute Hyperlooms jobs on a remote GPU.

HYPER_LOOMS is being built as a distributed compute network in which independently owned provider machines contribute opt-in GPU capacity and consumers submit supported AI workloads without owning equivalent hardware. The initial architecture decentralizes compute ownership while retaining a centrally operated, auditable control plane.

## Product direction

The first usable product will focus on trusted **Compute Circles**—universities, AI clubs, open-source teams, research groups, and friends pooling compatible GPUs. Providers will eventually earn reciprocal compute credits for useful, measured work, and each node will build a factual Compute Passport from reliability and execution history.

The first supported execution model is intentionally narrow:

- Linux NVIDIA provider nodes;
- approved, immutable AI workload images;
- one exclusive GPU lease per job;
- batch inference or fine-tuning workloads;
- centrally coordinated scheduling and accounting;
- object-storage-based input and result transfer.

Arbitrary containers, public cash payouts, blockchain tokens, worldwide synchronous training, Windows providers, GPU time slicing, and game streaming are not part of the first release.

## Architecture at a glance

```text
Next.js dashboard
      |
      | REST/JSON described by OpenAPI
      v
Spring Boot control plane ---- PostgreSQL
      |                         source of truth
      |
      | bidirectional gRPC over mTLS (Phase 1)
      v
Go provider agent ----------- S3-compatible artifact storage
      |
      | verified execution policy
      v
Isolated Python/PyTorch workload container -> selected NVIDIA GPU
```

The browser-facing API uses REST. OpenAPI is the machine-readable contract for that REST API, enabling interactive documentation, typed client generation, and compatibility checks. Provider agents use gRPC because they require typed, long-lived, bidirectional communication.

## Technology baseline

| Area | Decision |
|---|---|
| Dashboard | Next.js, TypeScript, Tailwind CSS |
| Control plane | Java, Spring Boot, modular monolith |
| Provider agent | Go |
| AI workloads | Python and PyTorch in approved OCI images |
| Browser API | REST/JSON with an OpenAPI contract |
| Agent protocol | Protocol Buffers and bidirectional gRPC over mTLS |
| Primary database | PostgreSQL |
| Initial durable queue | PostgreSQL leases, `SKIP LOCKED`, transactional outbox |
| Artifact storage | S3-compatible storage; SeaweedFS for local development |
| Execution | containerd/Docker with NVIDIA Container Toolkit |
| Stronger sandbox | gVisor only where its GPU/driver matrix is compatible |
| Image verification | Cosign/Sigstore |
| Observability | Structured logs, OpenTelemetry, Prometheus, Grafana |
| Local environment | Docker Compose |

## Repository layout

```text
apps/web/                    Next.js operator and user dashboard
services/control-plane/      Spring Boot control plane
agents/provider/             Go provider agent
workloads/python-sdk/        Input manifest and requester intake SDK
workloads/recipes/           Bounded PyTorch recipe candidate; image approval pending
proto/                       Shared versioned gRPC contracts
infrastructure/              Local compose, identity, monitoring, and certificate assets
docs/                        Product, architecture, security, and ADRs
.github/workflows/           CI quality gates
```

## Phase 0 quick start

Prerequisites:

- Java 17+
- Node.js 22+
- pnpm
- Go 1.27+ (or Docker for an isolated Go build)
- Docker Desktop/Engine with Compose

Copy `.env.example` to `.env` only when overriding local defaults. Do not commit `.env`.

```powershell
# Start PostgreSQL and S3-compatible storage
docker compose -f infrastructure/compose/compose.yaml up -d

# Start the control plane
cd services/control-plane
./gradlew bootRun

# Start the dashboard in another terminal
cd apps/web
pnpm install --frozen-lockfile
pnpm dev

# Run provider-agent health mode in another terminal (no network session by default)
cd agents/provider
go run ./cmd/hyperlooms-agent
```

Local endpoints:

- Dashboard: `http://localhost:3000`
- Control-plane status: `http://localhost:8080/api/v1/system/status`
- Health: `http://localhost:8080/actuator/health`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- API explorer: `http://localhost:8080/swagger-ui.html`
- SeaweedFS Admin UI: `http://localhost:9001`
- Provider-agent health: `http://127.0.0.1:9091/health/live`

The Phase 1 agent gRPC listener is disabled by default. Enabling it requires an operator-issued CA, server certificate/key, a bound provider client certificate, and the corresponding `.env.example` settings. The agent also needs a Linux NVIDIA host with `nvidia-smi`; it does not execute jobs yet. Build the control-plane Docker image from the repository root so Gradle can access the shared protocol: `docker build -f services/control-plane/Dockerfile .`.

## Documentation

- [Product vision](docs/product/vision.md)
- [Scope and non-goals](docs/product/product-scope.md)
- [Roadmap](docs/product/roadmap.md)
- [System overview](docs/architecture/system-overview.md)
- [Job lifecycle](docs/architecture/job-lifecycle.md)
- [Execution-core invariants](docs/architecture/execution-core.md)
- [Threat model](docs/security/threat-model.md)
- [Provider identity operations](docs/security/provider-identity-operations.md)
- [Architecture decisions](docs/decisions/README.md)
- [Engineering standards](docs/development/engineering-standards.md)
- [Testing strategy](docs/development/testing-strategy.md)
- [Phase 0 verification record](docs/development/phase-zero-verification.md)
- [Contribution guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

## Engineering principles

- Persist every authoritative state transition.
- Treat retries and duplicate delivery as normal distributed-system behavior.
- Use leases and fencing tokens for remote execution ownership.
- Make externally visible mutations idempotent.
- Never describe planned functionality as implemented.
- Prefer a modular monolith until a measured boundary justifies another service.
- Deny workload capabilities by default and add them deliberately.
- Optimize for an observable failure over a silent one.

## License

No open-source license has been selected yet. Until the owner chooses one, all rights are reserved. Adding a license is a product and business decision, not an automatic scaffolding step.
