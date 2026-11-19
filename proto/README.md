# Agent Protocol Contracts

This directory will own versioned Protobuf contracts between the Spring Boot control plane and Go provider agents.

Phase 0 intentionally does not invent job messages before the Phase 1 state machine is finalized. Protocol rules are already fixed:

- additive field evolution by default;
- never reuse removed field numbers;
- explicit protocol and agent versions;
- unique command, attempt, lease, and idempotency identifiers;
- bounded payloads—large artifacts move through object storage;
- no secrets in logs or generic metadata maps;
- backward-compatibility checks in CI once the first schema exists.
