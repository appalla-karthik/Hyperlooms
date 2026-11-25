# Provider identity operations (Phase 1)

This is an operator-only onboarding path, not public self-service registration. The operator command runs with database credentials on a trusted machine. It starts **no HTTP or gRPC listener**. Do not give database credentials or CA signing authority to a provider. Keep the CA private key outside the control plane and this repository.

## Trust boundary

The control plane trusts the CA configured by `HYPERLOOMS_AGENT_GRPC_CLIENT_CA`. The operator command validates each client certificate against that CA, requires a client-auth leaf with digital-signature key usage, and checks the provider URI SAN and expiry before binding its exact fingerprint. The live gRPC listener independently requires a client certificate signed by its CA. A signed certificate alone does not activate a node: its fingerprint must also be bound in PostgreSQL. A reported GPU is still not verified or schedulable.

The current verifier accepts a leaf or leaf-first PEM chain anchored at one configured CA. Automated CA trust-root rotation, revocation lists/OCSP, operator SSO/RBAC, and private-key custody are not implemented. Use a dedicated, audited PKI and operator access controls before a public network.

## Trusted operator sequence

Build the control plane with `./gradlew bootJar` from `services/control-plane`. Set the database environment variables and `HYPERLOOMS_AGENT_GRPC_CLIENT_CA` on the trusted operator machine. The examples below use the produced JAR path; use `gradlew.bat` on Windows.

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator issue-invite OWNER_SUBJECT 30
```

The command prints `INVITE_ID`, expiry, and a one-time `INVITE_TOKEN`. Transfer the token through a protected channel. Never paste it into an issue, log, or shell command line. Redemption reads the token from standard input and prints a pending `NODE_ID`:

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator redeem-invite
```

The provider generates its private key locally and submits a CSR with a URI SAN of `urn:hyperlooms:provider:<NODE_ID>`. An operator-approved CA issues a short-lived **client-auth** leaf certificate with digital-signature key usage, matching URI SAN, and a chain to the configured trust anchor. Issue the server certificate separately with a DNS SAN matching the agent's configured gRPC hostname and server-auth usage. The CA key must never be placed in the control-plane container or provider agent.

After reviewing ownership and the CA-issued certificate, bind the public certificate (not the private key):

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator bind-certificate NODE_ID client-chain.pem
```

The node remains `PENDING/OFFLINE` until that exact certificate completes mTLS and its first valid hello. The agent needs `HYPERLOOMS_AGENT_GRPC_ADDRESS`, `HYPERLOOMS_AGENT_NODE_ID`, `HYPERLOOMS_AGENT_SERVER_CA`, `HYPERLOOMS_AGENT_CLIENT_CERT`, and `HYPERLOOMS_AGENT_CLIENT_KEY`. Protect the client key with host-level permissions. No GPU is made available merely by connecting.

## Manual hardware review (not host attestation)

Only a trusted operator with independently witnessed host evidence should use the following commands. First inspect the current GPU report:

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator inspect-gpu NODE_ID GPU_UUID
```

Compare the model, UUID, VRAM, driver, and CUDA report with evidence obtained independently from the host. Retain that evidence in an access-controlled store and calculate the SHA-256 of its exact bytes. The current system neither collects nor validates that evidence; entering an arbitrary digest would be a false approval. Record the reviewer identity and approve only the inspected report fingerprint:

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator approve-gpu NODE_ID GPU_UUID REPORT_SHA256 EVIDENCE_SHA256 REVIEWER_SUBJECT
```

The transaction refuses stale, changed, missing, already-reviewed, or revoked reports. It inserts a historical review row and an outbox event. A changed or missing subsequent GPU report clears the current approval while preserving its review history. The node stays `OFFLINE`; this command cannot expose GPU capacity to scheduling. `REVIEWER_SUBJECT` is supplied by the command caller and is **not authenticated by this application**; production use requires operator SSO/RBAC, restricted machine access, database-level audit immutability, and audited evidence custody. Do not approve the Jetson Nano or any other physical device until the relevant host qualification actually passes.

## Rotation and emergency revocation

Issue a new client certificate for the same node ID with a new private key, then stage it:

```text
java -jar build/libs/hyperlooms-control-plane-0.1.0-SNAPSHOT.jar operator stage-rotation NODE_ID new-client-chain.pem
```

The old active certificate remains valid while the replacement is staged. The first successful authenticated hello from the replacement atomically revokes the old certificate, activates the new one, and keeps the node `OFFLINE`. Staging another replacement revokes the unused staged certificate. If the old key is compromised, **do not wait for rotation**: run `operator revoke-node NODE_ID`. This revokes all bound certificates and rejects future sessions; restoring the provider currently requires a new enrollment. An unused invite can be invalidated with `operator revoke-invite INVITE_ID`.

## Qualification

CI's Linux/PostgreSQL integration suite is configured to create ephemeral test CA/server/client certificates, perform an actual mTLS gRPC hello and heartbeat, verify database state and rotation, and check that an unbound certificate or missing client certificate is denied. A green CI run is still required to mark this qualification passed. The private test keys are created in a temporary directory and are never committed.

For the **separate Linux NVIDIA host qualification**, use an enrolled node and its bound certificate, then:

1. Confirm `nvidia-smi` reports the installed driver, CUDA version, GPU UUID and memory. The agent requires `nvidia-smi` on `PATH` and currently supports 1–16 reported GPUs.
2. On the control plane, enable the agent gRPC listener and configure the server certificate, private key and client CA as documented in `.env.example`. Its server DNS SAN must match the hostname used by the agent. Reach the gRPC port from the provider host; do not expose the operator database credentials there.
3. On the provider host, set `HYPERLOOMS_AGENT_GRPC_ADDRESS=host:port`, `HYPERLOOMS_AGENT_NODE_ID`, `HYPERLOOMS_AGENT_SERVER_CA`, `HYPERLOOMS_AGENT_CLIENT_CERT`, and `HYPERLOOMS_AGENT_CLIENT_KEY` to the enrolled identity's files. Run `go run ./cmd/hyperlooms-agent` from `agents/provider` (or the built Linux binary). Inspect the structured log for a successful hello and continuing heartbeat; query `http://127.0.0.1:9091/health/ready` for readiness.
4. From a trusted database session, verify `provider_nodes.enrollment_state = 'VERIFIED'`, `last_seen_at` advances, and `provider_gpus` has the same GPU UUID/model/VRAM as `nvidia-smi`. The GPU must remain `REPORTED`, and the node `OFFLINE`: connection does **not** approve hardware for scheduling.
5. Interrupt the gRPC connection while leaving the agent running and confirm readiness drops, then restore connectivity and confirm it recovers. For a rotation rehearsal, stage a fresh cert, reconnect with it, then confirm the old certificate fails while the new one works. Never use a production CA private key for a test.

This NVIDIA qualification cannot be marked passed until it runs on an actual compatible Linux GPU host. It does not yet cover a container workload or GPU isolation.
