# Agent Protocol Contracts

This directory will own versioned Protobuf contracts between the Spring Boot control plane and Go provider agents.

The first Phase 1 contract is `hyperlooms/agent/v1/agent_session.proto`. It defines a bidirectional agent session for hello and heartbeat only. It is linted and built in CI, but neither the Java server nor Go agent is wired to it yet. Job assignment/completion messages will be added when the lease runtime is implemented.

Protocol rules:

- additive field evolution by default;
- never reuse removed field numbers;
- explicit protocol and agent versions;
- unique command, attempt, lease, and idempotency identifiers;
- bounded payloads—large artifacts move through object storage;
- no secrets in logs or generic metadata maps;
- breaking-change checks against the main-branch baseline when the contract becomes active.

Run `buf lint` and `buf build` from this directory. Agent-reported GPU claims stay unverified until the control plane checks them and binds the node ID to an mTLS identity.
