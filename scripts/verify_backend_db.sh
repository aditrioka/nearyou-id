#!/usr/bin/env bash
# verify_backend_db.sh — assert the local backend test DB is up + migrated.
# Lightweight (no gradle): for the SessionStart hook + a fast pre-gate check.
# Exits non-zero with guidance when Postgres is unreachable or unmigrated, so a
# session can self-heal by running scripts/setup_backend_db.sh.
set -uo pipefail

# Pick up persisted DB env if the current shell hasn't sourced it.
if [[ -z "${DB_URL:-}" ]]; then
  for f in "${CLAUDE_ENV_FILE:-}" "$HOME/.nearyou_backend_db_env"; do
    [[ -n "$f" && -f "$f" ]] && { set -a; . "$f"; set +a; break; }
  done
fi

PGPORT="${PGPORT:-5433}"
PGDATABASE="${PGDATABASE:-nearyou_dev}"
# Derive host/port from DB_URL when present (jdbc:postgresql://host:port/db).
if [[ -n "${DB_URL:-}" && "$DB_URL" =~ :([0-9]+)/([A-Za-z0-9_]+) ]]; then
  PGPORT="${BASH_REMATCH[1]}"; PGDATABASE="${BASH_REMATCH[2]}"
fi

ok()  { printf '\033[1;32m  ok \033[0m %s\n' "$*"; }
bad() { printf '\033[1;31m FAIL\033[0m %s\n' "$*"; FAILED=1; }
note() { printf '\033[1;33m note\033[0m %s\n' "$*"; }
FAILED=0

echo "== Backend test DB verification =="

if command -v pg_isready >/dev/null 2>&1 && pg_isready -h localhost -p "$PGPORT" >/dev/null 2>&1; then
  ok "postgres accepting on localhost:$PGPORT"
else
  bad "postgres not reachable on localhost:$PGPORT"
fi

if [[ "$FAILED" -eq 0 ]]; then
  applied="$(psql -h localhost -p "$PGPORT" -U postgres -d "$PGDATABASE" -tAc \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success" 2>/dev/null || echo 0)"
  if [[ "${applied:-0}" -gt 0 ]]; then
    ok "schema migrated ($applied Flyway migrations applied)"
    # Informational, not a failure: the backend tests migrate on start
    # (KotestProjectConfig), and a snapshot-restored cluster can trail main.
    repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    latest_on_disk="$(ls "$repo_root/backend/ktor/src/main/resources/db/migration" 2>/dev/null \
      | sed -nE 's/^V([0-9]+)__.*/\1/p' | sort -n | tail -1)"
    latest_applied="$(psql -h localhost -p "$PGPORT" -U postgres -d "$PGDATABASE" -tAc \
      "SELECT MAX(version::int) FROM flyway_schema_history WHERE success" 2>/dev/null || true)"
    if [[ -n "$latest_on_disk" && -n "$latest_applied" && "$latest_applied" -lt "$latest_on_disk" ]]; then
      note "schema at V$latest_applied, repo has V$latest_on_disk: run scripts/setup_backend_db.sh (tests also self-migrate)"
    fi
  else
    bad "schema not migrated (run scripts/setup_backend_db.sh)"
  fi
fi

# Redis: informational only. Tests probe :6379 and fall back to a NoOp limiter
# when it is down; CI runs a real one (REDIS_URL=redis://localhost:6379).
if command -v redis-cli >/dev/null 2>&1 && redis-cli -p 6379 ping 2>/dev/null | grep -q PONG; then
  ok "redis answering on localhost:6379"
else
  note "redis not answering on localhost:6379 (tests fall back to NoOp; CI uses a real Redis)"
fi

if [[ "$FAILED" -ne 0 ]]; then
  echo "Backend DB INCOMPLETE — run: scripts/setup_backend_db.sh"
  exit 1
fi
echo "Backend DB OK — gate: ./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test"
