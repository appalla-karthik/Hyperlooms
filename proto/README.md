# Agent Protocol Contracts

This directory owns versioned Protobuf contracts between the Spring Boot control plane and Go provider agents.

The Phase 1 contract is `hyperlooms/agent/v1/agent_session.proto`. It defines a bidirectional agent session for hello, heartbeat, issued-lease transport offer/receipt, and opt-in execution-command staging/receipt. The offer contains identifiers, GPU UUID, fencing token, and expiry only. A separate command carries the pinned image digest and verified input metadata, but no object-storage credentials or permission to execute. A default-off server-streaming `ReadInput` RPC returns only the fixed verified `request.json` for a current certificate-bound staged command. `AgentExecutionService` adds fenced start/running/renew/fail transitions and bounded client-streaming result upload. The Java server generates stubs during Gradle builds; Go stubs use pinned plugins in `buf.gen.yaml`. These messages are software contracts, not evidence of a successful remote GPU job.

Protocol rules:

- additive field evolution by default;
- never reuse removed field numbers;
- explicit protocol and agent versions;
- unique command, attempt, lease, and idempotency identifiers;
- bounded payloads—large artifacts move through object storage;
- no secrets in logs or generic metadata maps;
- breaking-change checks against the main-branch baseline when the contract becomes active.

Run `buf lint`, `buf build`, and `buf generate` from this directory after contract changes. Commit regenerated Go stubs. Agent-reported GPU claims stay unverified until the control plane verifies hardware; mTLS identity alone is not enough.
