# Phase 1 verification record

This is an evidence ledger, not a completion certificate. Phase 1 remains in progress.

## 2026-11-26 bounded claim polling and safe requester cancellation

- Added a default-disabled scheduler worker that invokes at most 16 independent transactional claims per poll. It is forcibly disabled for operator CLI runs. Unit tests cover the default-off gate, explicit registration, empty poll, batch bound, and database failure propagation; a live PostgreSQL test confirms one eligible provider is not double-booked by the worker.
- Added an owner- and scope-gated requester cancellation endpoint for only `CREATED` and `QUEUED` jobs. Cancellation locks the job, writes one `JOB_CANCELED` outbox event, and replays idempotently. HTTP integration tests cover wrong owner/scope, created/queued cancellation, duplicate cancellation, and rejection of an active-state cancellation. Active-lease cancellation remains unavailable until a fenced agent stop protocol exists.
- `test integrationTest bootJar` passed locally against PostgreSQL and S3-compatible MinIO: 43 unit tests and 47 integration tests, zero failures/errors/skips. This does not qualify a real provider, start a container, terminate active work, or complete Phase 1.

## 2026-11-26 opt-in authenticated requester intake increment

- Added a default-disabled OIDC/JWT requester API for idempotent submission, owner-scoped reads, bounded manifest/file uploads, and guarded verified-input queueing. The configured issuer, JWKS endpoint, audience, token expiry, subject, and `jobs:read`/`jobs:write` scopes form the HTTP trust boundary. Tests use synthetic authenticated principals; no real identity provider or browser login has been deployed.
- `test integrationTest bootJar` passed locally against PostgreSQL and S3-compatible MinIO: 38 unit tests and 46 integration tests, zero failures/errors/skips. Intake integration tests cover authentication/scope denial, owner isolation, idempotent replay/conflict, upload size rejection, create-once staging retry, incomplete-input denial, verified queue transition, and queue replay. Claim validation has direct unit tests for issuer, audience, subject, and expiry; real JWKS signature verification against a deployed issuer remains to be exercised.
- This does not execute a queued job. Production ingress limits, abuse controls, identity-provider provisioning, approved runnable recipe, bucket policy/orphan cleanup, and Linux NVIDIA execution qualification remain open.

## 2026-11-26 trusted internal input-staging increment

- Added an internal staging service for a trusted requester subject. It validates job ownership and `CREATED` state, the canonical declared manifest, per-file paths/digests/sizes, and the 8 MiB file/32 MiB total bounds before create-once S3 writes. Identical retries do not overwrite bytes; mismatched existing objects are rejected.
- Control-plane `test integrationTest bootJar` passed locally against PostgreSQL and the S3-compatible bucket. After setting the local OpenSSL path and adding the S3 conflict assertion, a clean integration rerun reported 43 tests, zero failures, zero skips; the unit run reported 33 tests, zero failures/skips. Integration tests cover submit, server-mediated manifest/file staging, snapshot verification, the `QUEUED` transition, and unauthorized, corrupt, conflicting, and canceled-job staging. This is not a browser/API upload flow and does not qualify real GPU execution.
- Public requester authentication, HTTP request limits, production bucket policy, orphan cleanup, and an approved runnable PyTorch recipe remain open.

## 2026-11-25 internal verified-input queue increment

- V8 adds verified-input metadata and database constraints: version-1 jobs cannot enter `QUEUED` or active execution states without a complete matching snapshot record. Existing version-0 internal fixtures are preserved.
- Added an internal `JobInputQueueService` that rechecks trusted requester subject, job state, manifest digest, and approved recipe after S3 staging, then atomically binds snapshot metadata, changes `CREATED` to `QUEUED`, and inserts one outbox event. Its result does not expose the private S3 key. It is not wired to an API or scheduler worker.
- The focused live-PostgreSQL tests passed for queue/replay, wrong owner, corrupted bytes, cancellation during staging, recipe disabling during staging, and direct unverified database queue denial. An additional test queued a job through the real S3-compatible bucket and PostgreSQL in one internal flow. The V8 schema smoke passed and ended with `ROLLBACK`. Full control-plane `test integrationTest bootJar` passed locally: 33 unit tests and 41 integration tests, zero failures/skips, including the S3/PostgreSQL path and separate real-certificate mTLS tests.
- Requester authentication, upload authorization, bucket write-once policy, orphan-snapshot cleanup, an approved runnable PyTorch recipe, and real GPU execution remain open. This internal queue path is not a public workflow.

## 2026-11-25 S3 input-snapshot increment

- Added an internal S3-compatible adapter and bounded staging verifier. It checks the declared manifest digest, limits input to 8 MiB per file and 32 MiB total, hashes staged object bytes, writes a server-generated verified prefix, and reads back/hashes the copy before returning it. It does not alter job state.
- The focused verifier unit tests passed. The S3 integration test passed against the local private SeaweedFS-compatible bucket after its bootstrap, exercising the actual adapter and object round-trip. `bootJar` passed with the updated dependency locks.
- No requester upload grant, authenticated caller, database snapshot binding, queue transition, bucket write-once policy, or orphan cleanup exists yet. A verified prefix returned by this helper is not evidence that a job is ready for execution.

## 2026-11-25 server manifest-contract increment

- Added an internal Java parser for the Python SDK's exact canonical v1 manifest bytes. It bounds the payload to 64 KiB and 256 entries, validates sorted normalized paths and nonnegative signed-64-bit sizes, and rejects duplicate/extra fields and noncanonical JSON. A shared fixed digest vector catches cross-language drift.
- Local `py -m unittest discover -s tests -v` passed: five tests passed; the Windows symlink test was skipped because the host cannot create a symlink. Full control-plane `test` passed: 29 unit tests, zero failures/skips.
- This parser does not read S3, verify object bytes, establish requester identity, or transition a job to `QUEUED`. Server-side quotas and an immutable verified input snapshot are required before that transition is safe.

## 2026-11-25 Python input-manifest increment

- Added a standard-library-only Python helper that produces deterministic v1 JSON manifest bytes and SHA-256 from selected local regular files. It validates relative paths, sorts entries, rejects duplicates and visible symlinks, and bounds entry count and manifest bytes. This is a client-side contract, not verified upload or execution.
- `py -m unittest discover -s tests -v` passed locally: six tests, five passed and one symlink test skipped because this Windows host could not create a symlink. The new Linux Python SDK job passed in [Engineering quality run 45594560395](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45594560395).
- The Java v1 request contract was subsequently verified with the low-memory full control-plane test run below. Server-side manifest verification, artifact staging, and queueing remain unimplemented.

## 2026-11-25 versioned requester-contract increment

- Replaced caller-supplied request hashes in internal job submission with a server-computed, fixed UTF-8 v1 representation of recipe ID and declared input-manifest SHA-256. V7 adds request version and manifest-digest fields while preserving legacy internal rows as version 0. New submissions remain `CREATED`, not schedulable.
- Main and test Java sources compiled. A low-memory JShell run produced the fixed canonical-hash test vector. Initial Gradle attempts ran out of native memory; one retry also used an insufficient 128 MiB Metaspace cap.
- With one Gradle worker and bounded JVM/test heaps, the full control-plane suite then passed against live PostgreSQL: 24 unit tests and 34 integration tests, zero failures/skips, including four real-certificate mTLS tests. The V7 schema smoke passed and ended with `ROLLBACK`. The new integration case rejects replaying a legacy version-0 row as a v1 request.
- No requester authentication, uploaded manifest verification, approved runnable PyTorch recipe, or `CREATED -> QUEUED` transition exists yet.

## 2026-11-25 manual GPU review gate

- Added a trusted local operator inspection/approval path for a certificate-bound provider's reported GPU. A deterministic report fingerprint and operator-supplied digest of externally retained evidence are recorded with reviewer text in `provider_gpu_reviews`; the GPU becomes `VERIFIED`, but the node remains `OFFLINE` and cannot be scheduled. The service does not attest hardware, verify evidence custody, or authenticate the reviewer.
- Control-plane unit tests passed. New live PostgreSQL tests passed for review audit/outbox recording, duplicate denial, unchanged re-report, changed-report invalidation, malformed evidence, stale heartbeat, and revoked-node denial. The V6 schema smoke passed with a final `ROLLBACK`.
- The full control-plane integration suite passed against local PostgreSQL: 33 tests, zero failures/skips, including four real-certificate mTLS tests. An initial full-suite run failed only because a comment in already-applied V6 was changed, causing a Flyway checksum mismatch; the exact applied migration was restored and the rerun passed.
- No physical GPU was approved or tested. Evidence collection/custody, authenticated operator identity, database-level audit immutability, safe `AVAILABLE` transition, and real Linux NVIDIA/Jetson qualification remain open.

## 2026-11-25 Jetson compatibility discovery

- The only presently available physical GPU test device is a Jetson Nano. Added a [read-only host survey](jetson-host-survey.md) to distinguish the exact model and JetPack/L4T stack before designing an ARM64 runtime.
- A `CGO_ENABLED=0 GOOS=linux GOARCH=arm64` provider-agent build passed in the Go 1.27.4 Linux container. The binary was **not** run on the Jetson. No ARM64 GPU discovery, container execution, isolation, image compatibility, or resource accounting was tested.
- The current amd64 discrete-GPU preflight and runtime remain unchanged. The Jetson is offline/unschedulable and cannot close Phase 1A until its separate compatibility path has real host evidence.

## 2026-11-25 host-preflight increment

- Added a read-only `hyperlooms-agent preflight` JSON report for the initial Linux amd64 provider target. It checks platform, strict NVIDIA GPU discovery, and a compatible Docker server response; it does not create or remove containers.
- Go unit tests cover observed prerequisites, unsupported ARM64 without host probing, independent NVIDIA/Docker errors, and unsupported identities. `go vet ./...`, `go test -race -cover ./...`, and a Linux amd64 agent build passed in the Go 1.27.4 container. The Windows CLI run correctly returned exit code `1` with `host_platform` failed and did not claim GPU qualification.
- A Docker server response may refer to a remote daemon. Neither locality nor NVIDIA Container Toolkit injection, restricted execution, cleanup, or a real Linux NVIDIA machine has been demonstrated. Phase 1A remains open until a witnessed host smoke passes.

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

## 2026-11-26 requester SDK intake increment

- Added an installable, dependency-free Python requester client for the existing opt-in OIDC-protected API: submit with caller-stable idempotency key, manifest/file staging, queue, owner-scoped status read, and unleased cancellation. The convenience flow re-checks file hashes before upload and does not re-stage a replay that has left `CREATED`.
- The client requires HTTPS except explicit loopback development, refuses redirects with bearer credentials, caps file and response sizes, and omits token/server error bodies from exceptions. It does not acquire or refresh access tokens.
- Local `py -m unittest discover -s tests -v` passed: 13 tests, 12 passed and one Windows symlink test skipped. A `py -m pip wheel --no-deps` package build passed. New HTTP tests use a local fake server, not a real identity provider or live GPU host. No remote execution claim follows from these tests.

## 2026-11-26 local OIDC and PyTorch recipe candidate

- Added a loopback-only, opt-in Keycloak 26.8.2 Compose profile with an imported realm, public development client, explicit API audience and read/write scopes. The issuer/JWKS discovery endpoint returned the expected URLs. A real token initially exposed a missing `sub` claim; the local realm now includes an explicit subject mapper. The dedicated CI identity smoke creates and removes a temporary user and checks the final token claims; local final-token verification was interrupted when Docker Desktop's engine began returning HTTP 500 during a large PyTorch image pull.
- The Spring requester API still rejects insecure HTTP issuers by default; an explicit flag permits only literal loopback HTTP for local Keycloak. Targeted JWT configuration tests and the full local `test` task passed. No production identity deployment, hosted HTTPS, operator/user lifecycle, or browser login exists.
- Added a bounded, fixed-code PyTorch linear-regression recipe candidate with strict JSON validation, one-visible-CUDA-GPU requirement and digest-pinned official base image. Local parser tests passed; the CPU training test skipped because PyTorch is not installed on this Windows Python. CI now installs the pinned CPU wheel and runs the training test.
- A local Docker image build was attempted but stopped after Docker Desktop became unresponsive. The recipe image has not been built, published, signed, registered as `APPROVED`, or run on a GPU. It cannot be delivered through the current agent transport/runtime. Phase 1 execution remains open.

## 2026-11-26 real issuer-to-requester CI increment

- The [GitHub Actions run](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45800469090) started PostgreSQL, imported the local Keycloak realm, built and started the Spring control plane with requester auth enabled, and passed a real signed JWT through the protected job-read route. The smoke observed `401` without a token and `404` for an authorized read of a nonexistent job. The identity job passed; the full workflow result is recorded by GitHub Actions.
- This integration exposed a real `404 -> 403` error-dispatch problem that mocked MVC tests missed. The security chain now permits only servlet `ERROR` dispatches after the original request authorization; direct unauthenticated API requests remain protected.
- Local Docker Desktop still returned HTTP 500, so this evidence is from CI, not a local container run. It does not establish production IdP readiness, browser login, a published/approved recipe, or GPU execution.

## 2026-11-26 recipe packaging and internal artifact path

- Recovered Docker Desktop's Linux engine after stopping its stuck backend and performing a clean WSL shutdown/start. The PostgreSQL volume was preserved; `docker info` returned server 29.7.2 and the existing PostgreSQL container was healthy. This is an engine recovery, not a GPU runtime test.
- Added a manual main-branch GHCR build/sign/verify workflow for the digest-pinned PyTorch recipe. Its Cosign identity is fixed in the internal Go runtime verifier. Neither a published digest nor a successful signature verification exists until that workflow passes.
- The internal Linux amd64 Docker executor now requires a verified image signature and bounded JSON input before create and stages the input as a read-only bind mount. The first published-image CPU smoke exposed that a container tmpfs output disappears on stop, so the executor now uses one pre-created, file-size-limited result bind mount and reads at most 1 MiB of regular JSON after exit 0. The current agent session cannot invoke this executor, and no artifact is uploaded or accepted by the control plane.
- Provider-agent `go test ./...` passed in a Go 1.27.4 Docker container. Focused runtime tests passed after the final artifact change, including missing/malformed-result rejection and cleanup. A Linux ARM64 agent cross-compile completed locally, but local architecture inspection did not run because the Go container lacks `file`; the new CI job performs that check and uploads an experimental binary.
- A local image build was attempted after engine recovery but stopped when the 8 GB Windows host had too little free memory while other checks ran. There is still no local image/CPU-container or NVIDIA GPU smoke evidence. Jetson GPU compatibility remains unqualified until the actual model, L4T/JetPack version, and host survey are known.
- [Engineering quality run 45812677085](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45812677085) passed all jobs, including the ARM64 cross-build artifact, provider suite, PyTorch CPU smoke, requester identity, and live PostgreSQL/S3 checks.
- [Signed-image run 45812722815](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45812722815) built and published the Linux amd64 recipe, signed its digest with GitHub OIDC, and verified the expected workflow identity. Verified candidate: `ghcr.io/appalla-karthik/hyperlooms-linear-regression-v1@sha256:1d39207d047619cbf918fab61b859c93352b51bc42fce5ee37dd81133ad99f9b`. This still has no real GPU-host smoke, operator approval, or end-to-end agent execution.
- A later published-container smoke exposed the tmpfs-after-stop output loss. The recipe and internal runtime now use one pre-created result file bind mount with a container file-size limit, followed by bounded JSON validation after exit 0. [Engineering quality run 45815574327](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45815574327) passed after the fix, including the live stack.
- [Signed-image and restricted CPU-container run 45815598249](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45815598249) passed both jobs. The fresh runner pulled the signed image by digest, ran the fixed recipe with a read-only root filesystem, no network, dropped capabilities, non-root UID, bounded resources and request/result file mounts, then parsed the result JSON. Current CPU-smoked candidate: `ghcr.io/appalla-karthik/hyperlooms-linear-regression-v1@sha256:eaa0fa2eb14878216ed1c2e4b2444bf9f49fc73adb12474d34da6ef23b02ffb5`. This is not a CUDA/NVIDIA host or agent-session execution test and does not approve the recipe.

## 2026-11-27 internal execution-plan gate

- Added an internal, read-only execution-plan service that requires an active certificate-bound peer, matching node and fencing token, acknowledged unexpired lease, leased attempt/job, approved recipe, verified GPU, and complete version-1 verified-input metadata. It returns an immutable in-memory snapshot; no gRPC response, S3 credential, or agent command consumes it.
- Control-plane unit tests passed with Java 17. The targeted `SchedulerClaimServiceIntegrationTest` passed against the live local PostgreSQL 17 container, including positive plan contents and rejection of an unacknowledged lease, legacy version-0 job, wrong peer/token, revoked credential, disabled recipe, unverified GPU, and expired lease. Docker Desktop was restarted for the database test after its engine was found stopped.
- The first unconstrained Gradle attempt failed in JVM native-memory allocation before test assertions ran. A single-worker rerun with a 384 MiB Gradle heap completed both tasks. This is not a remote GPU execution test.

## 2026-11-27 durable prepared-command gate

- Added Flyway V9/V10 and an internal command-preparation service. It pins the approved image digest, exact GPU UUID, runtime ceiling, and verified input metadata to one acknowledged lease/fencing token, with a transactional `JOB_EXECUTION_COMMAND_PREPARED` outbox event. Direct database updates to prepared commands are rejected.
- The focused `SchedulerClaimServiceIntegrationTest` passed against live local PostgreSQL 17. It covers one command/event after receipt, idempotent retry, exact GPU UUID, rejection before receipt and for legacy/wrong-peer/wrong-token work, rejection after an approved recipe changes, and database immutability. The full control-plane `test integrationTest` suite then passed with PostgreSQL and MinIO healthy; the V2–V10 schema smoke passed inside a rolled-back transaction.
- An initial full-suite run had four S3 connection failures because MinIO was stopped. Once MinIO started, two requester tests encountered stale fixture idempotency keys from that interrupted run. The tests now use per-run keys and clean database records before S3 cleanup; the final full-suite rerun passed.
- No gRPC dispatch or agent consumer uses this row. It provides no artifact permission or remote GPU execution; the dispatcher must revalidate the live lease and trust before any future send.

## 2026-11-27 default-off command-staging gate

- Extended the mTLS agent protocol with an explicit staging capability, a fenced prepared command, an agent staging receipt, and server acknowledgement. The control-plane gate defaults off (`HYPERLOOMS_AGENT_COMMAND_STAGING_ENABLED=false`). The agent validates the exact local confirmed lease, GPU, token, expiry, image digest, and bounded input metadata, then syncs the command into its private journal before sending a staging receipt. Flyway V11 records the receipt idempotently for the bound node and current command.
- The Java gRPC service unit tests passed for gated delivery, staging acknowledgement, and disabled behavior. The targeted live-PostgreSQL command test passed for delivery, wrong IDs/tokens, idempotent staging, and no redelivery after receipt. The full control-plane `test integrationTest` suite passed against local PostgreSQL/S3. Buf format and lint passed; generated Go protocol code was refreshed. Go session tests passed for durable staging, restart, acknowledgement, and rejection of mismatched data.
- A separate real-certificate, live-PostgreSQL integration test now covers staging-capable hello, lease offer/receipt, the fenced prepared command, command staging acknowledgement, the V11 receipt row, and no redelivery. All five tests in `ProviderMtlsIntegrationTest` passed with zero skips or failures. This is a simulated gRPC client, not the Go agent on a GPU host.
- This path does not fetch artifacts, verify or run an image, renew/stop a lease, or upload and verify results. The Nano's CUDA smoke does not change that. Do not enable the scheduler or staging gate for public providers.

## 2026-11-27 internal scoped-input read gate

- Added a server-side reader that requires the active certificate-bound lease, exact staged command and fencing token. It derives keys only from the job's verified snapshot, checks the canonical manifest digest/count/total, allows only a manifest-listed path, verifies file size and SHA-256, and rechecks live authorization after reading. It never hands S3 credentials or arbitrary object keys to the provider.
- The targeted live-PostgreSQL authorization test passed for denial before staging, a valid bounded read, wrong path/command, tampered object bytes, and revoked certificate. The object bytes in this test come from an in-memory test store; the separate S3 integration suite tests snapshot storage. No agent-facing download endpoint or real-host artifact transfer exists yet.
- This does not authorize container execution. Lease expiry or revocation can occur after the final read check, so a later runtime path must recheck fencing and trust independently at start, during renewal, and before result acceptance.

## 2026-11-27 default-off mTLS input-transfer increment

- Added `AgentArtifactService.ReadInput` on the required-client-certificate gRPC listener. The server serves only `request.json` for the current bound peer, unexpired lease, exact staged command and fencing token, using the verified manifest and object bytes. The file is capped at 1 MiB, split into 32 KiB chunks, and live authorization is rechecked before each chunk. Both the server and agent feature gates default to false; no S3 credential is given to the agent.
- The Go agent can opt in to fetch after the command staging acknowledgement, enforce chunk order/length/limits, verify the full SHA-256 and JSON, and log the digest without the content. It discards the bytes and does not run Docker or upload a result. A bad transfer disconnects the session. Unit tests cover tampering, truncation, oversized chunks, expiry, and unconfirmed staging.
- The real-certificate mTLS integration test passed against local PostgreSQL: it staged a command, read the fixed verified input over the separate RPC, and denied a wrong fencing token and revoked certificate. That test uses an in-memory object store for input bytes; the separate S3 integration suite verifies storage behavior. Full control-plane `test integrationTest`, provider `go test ./...` and `go vet ./...`, and Buf lint/build passed locally. The modified revocation assertion also passed in a targeted mTLS suite rerun.
- This is not an end-to-end remote GPU job. Lease renewal/stop, runtime start authorization, result upload/verification, and fenced success completion remain unwired. The available Jetson smoke is diagnostic CUDA evidence only; a qualified Linux amd64 NVIDIA provider and a witnessed remote-job acceptance run are still required.

## 2026-11-27 default-off execution/result and requester-console increment

- Added Flyway V12 evidence fields and a certificate/command/fencing-bound execution RPC. `STARTING`, `RUNNING`, bounded `RENEW`, and `FAILED` transitions require the current acknowledged lease, approved unchanged recipe, verified GPU, and busy node. Owner stop requests block renewal and completion immediately; the expiry reconciler revokes the active lease and leaves the node offline. The Go agent persists a one-shot execution marker before requesting start, calls the restricted Linux amd64 Docker runtime, renews while it runs, cancels on lost authorization/session, and waits for cleanup before reconnecting. All execution gates remain default-off.
- The agent streams at most 1 MiB of result JSON over mTLS. The server enforces chunk order/size, SHA-256 and the current CUDA linear-regression result schema, writes to a server-derived create-once S3 key, verifies readback, and rechecks lease fencing before committing `SUCCEEDED`. Owner result reads verify stored length and digest. This checks integrity and schema, **not** the mathematical honesty of a provider. An interrupted upload may leave an orphan object.
- Added a Next.js requester console and server-side OIDC authorization-code/PKCE bridge. It lists approved recipes and owner jobs, builds one canonical `request.json` manifest, stages/queues a job, requests cancellation, and downloads a completed result. Browser-held cookies are encrypted and HttpOnly; access tokens and S3 credentials are not exposed to client JavaScript. Production identity and public signup are not included.
- Local Java `test integrationTest` passed against PostgreSQL and S3-compatible storage using a 192 MiB test heap and one Gradle worker. The targeted live-PostgreSQL scheduler suite passed after a strict duplicate-result-key rejection test was added. Go `go test ./...` passed in the Go 1.27.4 container; the final focused agent regression was rerun after the last error-handling edit. Web `pnpm check` and `pnpm build` passed. The new `/console` browser smoke passed in installed Chrome. The first Playwright attempt could not launch because its bundled Chromium was not installed locally; this is a test environment issue, not an app assertion.
- These are component and database-backed software tests. A complete real-certificate Go-agent/Keycloak/PostgreSQL/S3 acceptance run and a qualified Linux amd64 NVIDIA PyTorch/CUDA job remain unproven. Do not enable the execution gates for users or mark Phase 1 complete based on this record.
