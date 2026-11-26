#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_root/infrastructure/compose/compose.yaml")
log_file="${RUNNER_TEMP:-/tmp}/hyperlooms-phase-zero-control-plane.log"
status_file="${RUNNER_TEMP:-/tmp}/hyperlooms-phase-zero-status.json"
health_file="${RUNNER_TEMP:-/tmp}/hyperlooms-phase-zero-health.json"
openapi_file="${RUNNER_TEMP:-/tmp}/hyperlooms-phase-zero-openapi.json"
control_pid=""

cleanup() {
  if [[ -n "$control_pid" ]]; then
    kill "$control_pid" 2>/dev/null || true
    wait "$control_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

"${compose[@]}" up -d --wait --wait-timeout 180 postgres object-storage
"${compose[@]}" run --rm artifact-bootstrap
anonymous_bucket_status="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "http://localhost:${HYPERLOOMS_S3_API_PORT:-9000}/${HYPERLOOMS_ARTIFACT_BUCKET:-hyperlooms-artifacts}")"
[[ "$anonymous_bucket_status" == "403" ]] || { echo "Artifact bucket permits unexpected anonymous access: HTTP $anonymous_bucket_status" >&2; exit 1; }

pushd "$repo_root/services/control-plane" >/dev/null
bash ./gradlew --no-daemon bootRun >"$log_file" 2>&1 &
control_pid=$!
popd >/dev/null

ready=false
for ((attempt = 1; attempt <= 90; attempt++)); do
  if curl -fsS --max-time 5 -o "$status_file" http://localhost:8080/api/v1/system/status 2>/dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "$control_pid" 2>/dev/null; then
    break
  fi
  sleep 2
done

if [[ "$ready" != true ]]; then
  echo "Control plane did not become ready; recent startup output:" >&2
  tail -n 80 "$log_file" >&2
  exit 1
fi

grep -Eq '"phase"[[:space:]]*:[[:space:]]*"FOUNDATION"' "$status_file"
echo "Checking actuator health after status readiness."
health_ready=false
for ((attempt = 1; attempt <= 30; attempt++)); do
  if curl -fsS --max-time 10 -o "$health_file" http://localhost:8080/actuator/health 2>/dev/null &&
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$health_file"; then
    health_ready=true
    break
  fi
  sleep 2
done
if [[ "$health_ready" != true ]]; then
  echo "Control-plane health did not become UP after status was ready; response and recent startup output:" >&2
  if [[ -f "$health_file" ]]; then cat "$health_file" >&2; fi
  tail -n 80 "$log_file" >&2
  exit 1
fi
echo "Checking OpenAPI document."
curl -fsS --max-time 10 -o "$openapi_file" http://localhost:8080/v3/api-docs
grep -Fq '/api/v1/system/status' "$openapi_file"

db_user="${HYPERLOOMS_POSTGRES_USER:-hyperlooms}"
db_name="${HYPERLOOMS_POSTGRES_DB:-hyperlooms}"
migration_count="$("${compose[@]}" exec -T postgres psql -U "$db_user" -d "$db_name" -Atq -c "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true")"
schema_phase="$("${compose[@]}" exec -T postgres psql -U "$db_user" -d "$db_name" -Atq -c "SELECT metadata_value FROM platform_metadata WHERE metadata_key = 'schema_phase'")"

[[ "$migration_count" == "1" ]] || { echo "Flyway V1 was not applied successfully" >&2; exit 1; }
[[ "$schema_phase" == "foundation" ]] || { echo "Foundation metadata is missing" >&2; exit 1; }

echo "Phase 0 stack smoke passed: PostgreSQL/Flyway, private S3 object round-trip, status, health, and OpenAPI."
