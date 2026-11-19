# ADR-003: API Protocols

- Status: Accepted
- Date: 2026-11-20

## Decision

Use REST/JSON for browser and external control-plane APIs, publish an OpenAPI contract, use Server-Sent Events for initial one-way browser updates, and use Protobuf/bidirectional gRPC over mutual TLS for provider agents.

## Rationale

REST is easy to consume and debug from browsers and external integrations. OpenAPI supplies a language-neutral contract, interactive documentation, client generation, and compatibility analysis. Agents need typed, ordered, long-lived, bidirectional streams for commands, heartbeats, status, and logs.

## Consequences

REST and Protobuf contracts are versioned public surfaces. Breaking changes require compatibility planning. OpenAPI is not an alternative transport; it documents the REST transport.
