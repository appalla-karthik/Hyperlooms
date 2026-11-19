# ADR-004: PostgreSQL-backed Initial Queue

- Status: Accepted
- Date: 2026-11-20

## Decision

Store authoritative job state, attempts, leases, idempotency records, ledger entries, and transactional outbox events in PostgreSQL. Use transactional row locking and `SKIP LOCKED` for the first scheduler queue.

## Rationale

The first scheduler requires transactional consistency more than extreme throughput. PostgreSQL avoids a second authoritative state system and supports safe concurrent claims. A transactional outbox closes the gap between database commits and asynchronous delivery.

## Consequences

Queue queries require careful indexes, short transactions, and monitoring. NATS JetStream may later distribute events, but it will not replace the business source of truth without a separate ADR.
