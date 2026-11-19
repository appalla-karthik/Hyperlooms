# Job Lifecycle

## Planned state model

```text
CREATED -> UPLOADING -> QUEUED -> LEASED -> STARTING -> RUNNING
                                                    |
                                                    v
                              SUCCEEDED <- UPLOADING_RESULTS

Recoverable paths: LEASE_EXPIRED/NODE_LOST -> RETRY_WAIT -> QUEUED
Terminal paths: FAILED, CANCELED, EXPIRED
```

The exact machine-readable state model will be introduced with Phase 1 migrations and tests.

## Submission

The consumer selects an approved recipe, declares requirements, and uploads inputs directly to artifact storage through scoped upload authorization. The control plane validates ownership, compatibility, quotas, and future credit reservation before queueing the job.

## Scheduling

Eligibility filtering excludes nodes that are offline, busy, incompatible, outside the requested circle, below resource requirements, disallowed by provider policy, or below a trust threshold. Scoring may then consider predicted duration, reliability, transfer cost, current load, locality, and credit price.

## Leasing

The scheduler creates an attempt and issues a time-bounded lease. The agent acknowledges it and renews while running. Lease expiry permits reassignment but does not prove the old process stopped, so completion requires a valid fencing token.

## Execution

The agent verifies recipe identity and policy, downloads digest-verified inputs, starts the constrained sandbox, streams bounded logs and metrics, uploads results, and submits a completion manifest. The control plane validates the current attempt before final state and accounting changes.

## Delivery semantics

Remote delivery is at least once. Duplicate commands and callbacks are expected. APIs use idempotency keys, unique database constraints, immutable attempt records, and fenced completion to make repeated delivery safe.
