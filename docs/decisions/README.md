# Architecture Decision Records

ADRs capture decisions that are expensive to rediscover or reverse. A changed decision receives a new ADR that supersedes the old one; accepted history is not silently rewritten.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-technology-stack.md) | Spring Boot control plane, Go agent, Next.js dashboard, Python workloads | Accepted |
| [002](ADR-002-modular-monolith.md) | Begin with a modular monolith control plane | Accepted |
| [003](ADR-003-rest-openapi-grpc.md) | REST/OpenAPI for browsers, gRPC/Protobuf for agents | Accepted |
| [004](ADR-004-postgresql-job-queue.md) | PostgreSQL is authoritative and hosts the initial durable queue | Accepted |
| [005](ADR-005-linux-nvidia-first.md) | Linux NVIDIA exclusive-GPU providers first | Accepted |
