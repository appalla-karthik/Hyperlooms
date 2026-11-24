# Testing Strategy

## Test layers

### Unit tests

Exercise deterministic scheduling rules, state transitions, ledger calculations, policy decisions, configuration parsing, and protocol validation without infrastructure.

### Component and slice tests

Exercise Spring MVC contracts, persistence adapters, Go host adapters, and dashboard behavior with only the necessary framework surface loaded.

### Integration tests

Use real PostgreSQL and S3-compatible services in isolated containers. Verify Flyway migrations, locking, idempotency, object permissions, and transaction boundaries. GPU-independent mock execution keeps these tests available in CI.

The control plane's `integrationTest` Gradle task requires a running PostgreSQL instance configured through `HYPERLOOMS_DATABASE_*`. CI runs it after the Compose-backed stack and schema smoke checks. Locally, start `docker compose -f infrastructure/compose/compose.yaml up -d --wait postgres`, then run `./gradlew integrationTest` from `services/control-plane` (use `gradlew.bat` in PowerShell). Normal `test` excludes the `integration` tag so unit tests do not silently depend on a database.

The Phase 1 agent-session unit tests cover missing TLS identity, wrong node claims, protocol mismatch, hello ordering, and heartbeat sequence handling. Go tests cover configuration and NVIDIA report parsing. The PostgreSQL integration suite checks heartbeat persistence, offline availability, revoked peers, and staged certificate rotation. On Linux, it also creates temporary CA/server/client certificates and makes a real mTLS gRPC hello/heartbeat call; an unbound certificate is denied. That CI test uses a synthetic GPU report and does **not** qualify real NVIDIA discovery, driver compatibility, or execution. Those require a controlled Linux NVIDIA host.

### Contract tests

Detect incompatible OpenAPI and Protobuf changes. Generated clients must compile against the published contract.

### End-to-end tests

Run the control plane, one mock agent, object storage, and dashboard through job submission, leasing, execution, result upload, cancellation, and retry.

### GPU qualification tests

Run only on controlled NVIDIA hosts. Record driver, toolkit, CUDA, GPU model, sandbox, image digest, and test results as a compatibility matrix.

### Failure and security tests

Exercise duplicate commands, expired leases, stale completions, dropped connections, corrupt artifacts, storage denial, process termination, resource exhaustion, and forbidden network/host access.

## Quality policy

Coverage is supporting evidence, not the objective. Tests must protect invariants and failure behavior. A critical state transition without a negative-path test is incomplete.
