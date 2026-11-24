# Phase 1 verification record

This is an evidence ledger, not a completion certificate. Phase 1 remains in progress.

## 2026-11-24 local Windows/Docker run

- Docker Engine 29.7.2 was available. Only the repository's PostgreSQL 17 container was started; no pre-existing containers were running.
- The control-plane JAR started against that database. Flyway applied migrations V1–V5, including staged certificate rotation, and `/actuator/health` returned `UP`.
- `scripts/verify-phase-one-schema.sql` passed against live PostgreSQL. Its transaction ended with `ROLLBACK`; it did not retain smoke-test records.
- Provider agent `go test ./...` and `go vet ./...` passed. The embedded local console returned HTTP 200 and `/api/status` returned current state. On this Windows machine, `nvidia-smi` was unavailable, so GPU discovery correctly reported a diagnostic rather than fabricated hardware.
- The targeted real-certificate mTLS JUnit integration test did **not** complete. Two attempts exhausted available host native memory while launching Java test processes with Docker running. Neither attempt reached a TLS assertion; this is not a passing result or a demonstrated TLS defect. CI/Linux or a host with sufficient memory must run it to completion.
- No Linux NVIDIA host was available. Real GPU reporting, systemd service behavior, and workload execution remain unqualified.

Do not mark the provider network or Phase 1 as production-ready based on these checks.
