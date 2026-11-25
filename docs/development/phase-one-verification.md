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

## 2026-11-25 transport-offer increment

- Pinned Buf 1.50.0 formatted, linted, built, and regenerated the versioned Protobuf contract and Go stubs.
- Full control-plane `test integrationTest` passed against PostgreSQL. The claim/expiry/receipt suite reports ten tests, and the real-certificate mTLS suite reports four tests, all with zero failures or skips. The new mTLS test sends an issued-lease offer after heartbeat, receives a transport receipt, and verifies the database `ACKNOWLEDGED` state.
- Provider-agent `go test ./...` and `go vet ./...` passed with Go 1.27.4. Agent tests cover offer identity/GPU/token/expiry validation, receipt emission, and response correlation. These were transport tests only; they did not run a GPU workload or qualify a Linux NVIDIA host. Local receipt persistence was added in the next increment.

## 2026-11-25 local receipt journal increment

- The agent now opens a private persistent state directory before its mTLS session, atomically writes and syncs an offered lease before emitting a receipt, and persists receipt confirmation. Startup rejects corrupt or foreign-node state. An unexpired conflicting lease blocks another receipt.
- Provider-agent `go test ./...` and `go vet ./...` passed with Go 1.27.4 on Windows. Tests cover restart/duplicate receipt, confirmation persistence, conflicting and expired offers, invalid journal state, and no receipt when the journal write fails.
- Windows tests do not verify Linux directory-sync semantics or service-user permissions. No Linux NVIDIA host qualification or execution test was performed. This journal is not a workload runtime or recovery worker.

## 2026-11-25 opt-in lease-expiry polling increment

- Added a bounded control-plane poller, disabled by default, that invokes up to 64 independent `LeaseExpiryService` transactions per tick. The operator CLI forces the poller off even if its process environment enables it.
- Control-plane `test` and full `integrationTest` passed with Java 17 against the local healthy PostgreSQL container. The new unit tests cover default-disabled/explicit-enabled registration, empty polls, the batch bound, and database failure propagation. The new live-database test drains two expired leases, verifies both jobs remain `RETRY_WAIT`, both nodes are `OFFLINE`, and exactly one expiry outbox event exists per job. Existing concurrent-expiry tests also passed.
- The scheduled timer itself was not exercised against a Linux deployment. Polling does not start, stop, renew, or retry a GPU workload; production activation still requires remote cancellation and fencing guarantees.

## 2026-11-25 fenced execution-state increment

- Added internal-only start/running/failure transactions guarded by the authenticated provider identity, active certificate, matching node and fencing token, acknowledged unexpired lease, busy provider, and matching job/attempt states. Failure quarantines the node; no success transition exists before artifact verification.
- Control-plane `test integrationTest` passed with Java 17 against the local PostgreSQL container. The new live database tests cover unacknowledged denial, idempotent state progress and outbox events, explicit failure, wrong peer/token, revoked certificate, expired lease, and rejection of a late report after expiry reconciliation. These tests use certificate identity doubles; the existing separate real-certificate mTLS test still covers transport only.
- No agent RPC invokes these transactions. No container, GPU workload, result artifact, or Linux NVIDIA host was tested. The system remains non-executable.

## 2026-11-25 internal container-runtime foundation

- Added an unconnected Linux amd64 Docker runtime planner/executor. It only accepts digest-pinned images and canonical NVIDIA GPU UUIDs, uses restrictive container settings, enforces a lease/runtime deadline, and attempts explicit cleanup after create/start/wait.
- Provider-agent `go test ./...` and `go vet ./...` passed with Go 1.27.4 on Windows. Fake-Docker tests cover command restrictions, nonzero workload exit, invalid specs rejected before Docker, cleanup after start failure, cleanup-error reporting, cancellation cleanup, and bounded CLI output.
- No real Docker GPU container was run. No image signature verification, artifact staging, logs, crash-orphan recovery, or hostile-image isolation test exists. The agent session does not invoke this package. This is not yet a working remote GPU job.
- A local `GOOS=linux GOARCH=amd64` cross-build was attempted but could not complete because the temporary Windows Go distribution lacked `pkg/include/textflag.h`; this is a local toolchain limitation, not a passing Linux build result.
