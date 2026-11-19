# Engineering Standards

## General

- Store and exchange timestamps in UTC with an explicit offset.
- Treat identifiers as opaque values; clients must not infer business meaning from them.
- Use explicit bounded values for timeouts, retries, payloads, logs, and concurrency.
- Make external mutations idempotent and persist their idempotency keys.
- Prefer an actionable error over silent fallback.
- Keep planned features behind documentation or disabled flags, never mock production claims.

## Java control plane

- Organize code by business module, not a global controller/service/repository layer.
- Keep domain decisions out of web controllers and persistence adapters.
- Use constructor injection and immutable values.
- Validate requests at the boundary and enforce invariants inside the owning module.
- Use transactions around complete business state changes, not remote network calls.
- Do not expose JPA entities in HTTP contracts.

## Go provider agent

- Pass `context.Context` through blocking or cancellable operations.
- Bound goroutines, buffers, and retries.
- Return errors with operational context while avoiding credentials and artifact content.
- Keep host/runtime adapters behind narrow interfaces so mock and GPU paths can be tested separately.
- Support graceful drain before process termination.

## HTTP APIs

- Version public paths under `/api/v1`.
- Publish OpenAPI from the implemented contract.
- Use nouns for resources and explicit action endpoints only when an operation is not ordinary resource state.
- Return structured errors with type, title, status, detail, instance, trace identifier, timestamp, and field violations where applicable.
- Breaking changes require a versioning and migration decision.

## Protobuf APIs

- Use additive changes by default.
- Reserve removed field numbers and names.
- Never send large artifact bytes through ordinary control messages.
- Include explicit command, attempt, lease, and protocol identifiers.
- Reject unsupported protocol versions clearly.

## PostgreSQL

- Schema changes use numbered, forward-only Flyway migrations.
- Never edit an applied migration; add a corrective migration.
- Use lowercase `snake_case` names and database constraints for invariants that must survive concurrency.
- Store authoritative transitions and audit evidence in the same transaction where possible.
- Add indexes from measured access paths and verify them with query plans.

## Configuration and secrets

- Non-secret defaults may live in versioned configuration.
- Secrets enter through environment-specific secret stores or environment injection.
- Applications fail clearly when required production configuration is absent.
- Logs, metrics, traces, error responses, and CI output must not contain credentials or user artifacts.

## Logging

- Emit structured logs with stable event names and identifiers.
- Include job, attempt, node, and trace identifiers when relevant.
- Do not log full prompts, datasets, model weights, signed URLs, bearer tokens, or private keys.
- Log state transitions once at the owning component.
