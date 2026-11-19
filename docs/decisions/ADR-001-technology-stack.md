# ADR-001: Technology Stack

- Status: Accepted
- Date: 2026-11-20

## Decision

Use Next.js/TypeScript for the dashboard, Java/Spring Boot for the control plane, Go for the provider agent, and Python/PyTorch inside workload images. Use PostgreSQL as the authoritative database and S3-compatible object storage for artifacts.

## Rationale

Spring Boot provides mature transactional, security, validation, database, operational, and testing foundations for business-heavy control-plane behavior. Go produces a compact, low-overhead provider binary with strong networking and concurrency support. Python remains confined to the ML ecosystem where it is the natural interoperability choice. TypeScript provides typed browser development.

## Consequences

The repository is polyglot and requires explicit boundaries, generated contracts, and per-language quality gates. Business rules must not be duplicated across components.
