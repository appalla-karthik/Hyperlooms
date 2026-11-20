# Local Infrastructure

This Compose project starts only Phase 0 stateful dependencies:

- PostgreSQL, the future authoritative business-state store;
- SeaweedFS (official MinIO-compatible adapter), local S3-compatible artifact storage;
- a one-shot bootstrap container that creates a private artifact bucket and checks an S3 object round-trip.

```powershell
docker compose -f infrastructure/compose/compose.yaml config
docker compose -f infrastructure/compose/compose.yaml up -d
docker compose -f infrastructure/compose/compose.yaml ps
docker compose -f infrastructure/compose/compose.yaml down
```

Named volumes intentionally preserve local state across ordinary `down` operations. Removing volumes is destructive and is not part of the normal workflow.

The SeaweedFS volume is deliberately distinct from the old MinIO volume. Existing MinIO objects require an S3-level migration; do not mount the old volume into SeaweedFS. Port 9001 now serves the SeaweedFS Admin UI, not the MinIO console.

Default credentials are local-development values only. Override them through an uncommitted `.env` file before using a shared environment.
