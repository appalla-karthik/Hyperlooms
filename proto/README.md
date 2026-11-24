# Agent Protocol Contracts

This directory owns versioned Protobuf contracts between the Spring Boot control plane and Go provider agents.

The first Phase 1 contract is `hyperlooms/agent/v1/agent_session.proto`. It defines a bidirectional agent session for hello and heartbeat only. The Java server generates its stubs during Gradle builds; the Go stubs are generated with the pinned plugins in `buf.gen.yaml`. Job assignment/completion messages will be added when the lease runtime is implemented.

Protocol rules:

- additive field evolution by default;
- never reuse removed field numbers;
- explicit protocol and agent versions;
- unique command, attempt, lease, and idempotency identifiers;
- bounded payloads—large artifacts move through object storage;
- no secrets in logs or generic metadata maps;
- breaking-change checks against the main-branch baseline when the contract becomes active.

Run `buf lint`, `buf build`, and `buf generate` from this directory after contract changes. Commit regenerated Go stubs. Agent-reported GPU claims stay unverified until the control plane verifies hardware; mTLS identity alone is not enough.
