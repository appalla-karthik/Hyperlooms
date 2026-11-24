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

## 2026-11-25 local Windows/Docker run

- Added the internal `SchedulerClaimService` transaction. It is not scheduled or exposed as an API and does not send agent commands.
- Control-plane `test` and provider-agent `go test -p 1 ./...` passed. The console JavaScript syntax check passed.
- Targeted `SchedulerClaimServiceIntegrationTest` passed against the local PostgreSQL container. Its fixtures were removed after each test. It covered a 2 GiB GPU matching a smaller recipe, inadequate VRAM, an unverified GPU, stale heartbeat, revoked certificate, and concurrent claims of a single node.
- The full control-plane `integrationTest` suite passed against PostgreSQL with `HYPERLOOMS_TEST_OPENSSL` set to Git for Windows OpenSSL. The XML result for `ProviderMtlsIntegrationTest` reports three tests, zero skips, zero failures: a bound certificate hello/heartbeat reached the database, an unbound certificate was denied, and a missing client certificate was rejected.
- This does not qualify a real GPU, verify an operator's hardware evidence, deliver a lease to an agent, recover an expired lease, or complete a workload. A Linux NVIDIA host test is still required.

## 2026-11-25 lease-expiry increment

- Added an internal expiry transaction; it has no polling worker and does not send an agent stop command or automatically retry a job.
- Targeted live PostgreSQL tests passed for unexpired-versus-expired leases, timeout state transitions, conservative node-offline behavior, concurrent expiry workers, and cleanup of a stray lease on a canceled job.
- Full control-plane `test integrationTest` completed successfully against PostgreSQL. The scheduler/expiry integration suite reports six tests with zero failures or skips; the real-certificate mTLS suite reports three tests with zero failures or skips.
- An expired database lease does not prove that remote GPU execution has stopped. The future agent completion path must reject expired or mismatched fencing tokens before any result is accepted.

## 2026-11-25 lease-receipt increment

- Added an internal lease receipt transaction bound to the currently active certificate identity, node, fencing token, unexpired lease, active attempt/job, and busy node. This has no gRPC caller or agent-side command yet.
- Targeted live PostgreSQL tests passed for idempotent receipt and one outbox event, wrong node/token, revoked certificate, expired lease, and concurrent duplicate receipts. The certificate identities in these targeted tests are test doubles; the separate real-certificate mTLS suite exercises transport authentication.
- Full control-plane `test integrationTest` passed against PostgreSQL. The combined claim/expiry/receipt suite reports nine tests and the real-certificate mTLS suite three tests, all with zero failures or skips.
