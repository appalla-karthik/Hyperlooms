# System Overview

## Architectural style

HYPER_LOOMS begins as a modular monolith control plane plus independently deployed provider agents and workload containers. Compute ownership is decentralized; scheduling, identity, policy, and accounting are centrally coordinated and auditable.

```text
Consumer browser
      |
      | HTTPS REST/JSON (OpenAPI)
      v
Spring Boot control plane ---------------- PostgreSQL
      |                                      jobs, leases, ledger, audit
      |
      +------------------------------- S3-compatible storage
      |                                  immutable artifacts
      |
      | mTLS bidirectional gRPC
      v
Go provider agent
      |
      | constrained runtime API
      v
Sandboxed approved workload image -------- NVIDIA GPU
```

## Core invariants

1. PostgreSQL is authoritative for business state.
2. A job has one or more immutable attempts; attempt history is not overwritten.
3. Only the current valid lease/fencing token may finalize an attempt.
4. Credit mutations are append-only ledger entries with idempotency keys.
5. Artifacts are addressed and verified by cryptographic digest.
6. Provider connections originate outbound; contributor machines expose no public control port.
7. Workload capabilities are denied by default.
8. Planned behavior is not presented as implemented behavior.

## Scaling approach

The control plane remains a modular monolith until a measured constraint or security boundary justifies extraction. Stateless API instances can scale horizontally while PostgreSQL retains authoritative state. Event delivery may later use NATS JetStream, but consumers must be rebuildable from durable database state or transactional outbox events.
