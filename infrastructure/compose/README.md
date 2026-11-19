# Local Infrastructure

This Compose project starts only Phase 0 stateful dependencies:

- PostgreSQL, the future authoritative business-state store;
- MinIO, local S3-compatible artifact storage;
- a one-shot bootstrap container that creates a private artifact bucket.

```powershell
docker compose -f infrastructure/compose/compose.yaml config
docker compose -f infrastructure/compose/compose.yaml up -d
docker compose -f infrastructure/compose/compose.yaml ps
docker compose -f infrastructure/compose/compose.yaml down
```

Named volumes intentionally preserve local state across ordinary `down` operations. Removing volumes is destructive and is not part of the normal workflow.

Default credentials are local-development values only. Override them through an uncommitted `.env` file before using a shared environment.
