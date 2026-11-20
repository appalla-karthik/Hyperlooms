#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_root/infrastructure/compose/compose.yaml")

"${compose[@]}" exec -T postgres psql \
  -v ON_ERROR_STOP=1 \
  -U "${HYPERLOOMS_POSTGRES_USER:-hyperlooms}" \
  -d "${HYPERLOOMS_POSTGRES_DB:-hyperlooms}" \
  <"$repo_root/scripts/verify-phase-one-schema.sql"

echo "Phase 1 schema smoke passed: V2/V3 migrations, idempotency, exclusive GPU lease, fencing, and outbox."
