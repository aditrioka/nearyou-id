#!/usr/bin/env bash
# setup_backend_db.sh — provision a local PostgreSQL for the Ktor backend test
# gate inside the Claude Code web/cloud sandbox (or any Docker-less Linux box).
#
# Why this exists (the backend twin of scripts/setup_android.sh):
#   The `:backend:ktor:test` suites are JDBC integration tests that connect to a
#   real Postgres via DB_URL/DB_USER/DB_PASSWORD (see any *TestSupport.kt) — they
#   do NOT use Testcontainers, so they need a reachable Postgres, NOT Docker. The
#   cloud sandbox ships postgresql-16 as a plain package, so we run it as an
#   ordinary process (no Docker/KVM needed), create the dev DB + the PostGIS /
#   pg_trgm / pgcrypto extensions the migrations require, apply Flyway, and
#   persist DB_URL/DB_USER/DB_PASSWORD to $CLAUDE_ENV_FILE so the gate
#   (`./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`)
#   just works.
#
# Idempotent: safe to re-run. Skips initdb if the cluster exists, skips start if
# already accepting, uses IF NOT EXISTS for the DB/extensions, and Flyway skips
# already-applied migrations.
#
# Usage:  bash scripts/setup_backend_db.sh [--no-migrate]
#   --no-migrate   provision + start Postgres but skip the (slow, gradle-driven)
#                  Flyway migrate step. The DB env is still persisted.
#
# Tunables (env): PGPORT (default 5433, the repo's DB_URL default), PGDATA
#   (default /tmp/nearyou_pgdata), PGDATABASE (default nearyou_dev).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PGPORT="${PGPORT:-5433}"
PGDATA="${PGDATA:-/tmp/nearyou_pgdata}"
PGDATABASE="${PGDATABASE:-nearyou_dev}"
DO_MIGRATE=1
[[ "${1:-}" == "--no-migrate" ]] && DO_MIGRATE=0

log()  { printf '\033[1;36m[backend-db]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[backend-db]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[backend-db]\033[0m %s\n' "$*" >&2; exit 1; }

# 1. Locate the server binaries (the package installs them off-PATH under
#    /usr/lib/postgresql/<major>/bin). Install postgresql + PostGIS if absent.
PGBIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1 || true)"
if [[ -z "$PGBIN" || ! -x "$PGBIN/initdb" ]]; then
  log "PostgreSQL server not found — installing postgresql + postgis via apt."
  export DEBIAN_FRONTEND=noninteractive
  sudo apt-get update -q >/dev/null 2>&1 || warn "apt-get update reported issues (continuing)."
  sudo apt-get install -y -q postgresql postgresql-contrib >/dev/null 2>&1 \
    || die "Failed to install postgresql. Check network / apt."
  PGBIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1 || true)"
  [[ -x "$PGBIN/initdb" ]] || die "initdb still not found after install."
fi
PGMAJOR="$(basename "$(dirname "$PGBIN")")"
log "Using PostgreSQL $PGMAJOR ($PGBIN)."

# PostGIS (geography/ST_DWithin) + pg_trgm (search) are required by the
# migrations; install the version-matched PostGIS package if its control file
# is absent.
if [[ ! -f "/usr/share/postgresql/$PGMAJOR/extension/postgis.control" ]]; then
  log "Installing postgresql-$PGMAJOR-postgis-3 (migrations need PostGIS)."
  export DEBIAN_FRONTEND=noninteractive
  sudo apt-get update -q >/dev/null 2>&1 || true
  sudo apt-get install -y -q "postgresql-$PGMAJOR-postgis-3" >/dev/null 2>&1 \
    || warn "PostGIS install reported issues — migrate may fail if it is truly missing."
fi

# 2. initdb needs a non-root owner; ensure a `postgres` OS user owns PGDATA.
if [[ "$(id -u)" -eq 0 ]]; then
  id postgres >/dev/null 2>&1 || useradd -m postgres 2>/dev/null || true
  PGRUN=(sudo -u postgres)
else
  PGRUN=()
fi

if [[ ! -s "$PGDATA/PG_VERSION" ]]; then
  log "Initializing a fresh cluster at $PGDATA (trust auth, local-only)."
  rm -rf "$PGDATA"; mkdir -p "$PGDATA"
  [[ "$(id -u)" -eq 0 ]] && chown -R postgres "$PGDATA"
  "${PGRUN[@]}" "$PGBIN/initdb" -D "$PGDATA" -U postgres --auth=trust >/tmp/nearyou_initdb.log 2>&1 \
    || { tail -8 /tmp/nearyou_initdb.log >&2; die "initdb failed."; }
else
  log "Cluster already initialized at $PGDATA — reusing."
fi

# 3. Start (idempotent). The cluster listens on localhost only.
if "$PGBIN/pg_isready" -h localhost -p "$PGPORT" >/dev/null 2>&1; then
  log "Postgres already accepting on localhost:$PGPORT."
else
  log "Starting Postgres on localhost:$PGPORT."
  "${PGRUN[@]}" "$PGBIN/pg_ctl" -D "$PGDATA" \
    -o "-p $PGPORT -c listen_addresses=localhost" \
    -l /tmp/nearyou_pglog.log start >/dev/null 2>&1 \
    || { tail -8 /tmp/nearyou_pglog.log >&2; die "pg_ctl start failed."; }
  for _ in $(seq 1 20); do
    "$PGBIN/pg_isready" -h localhost -p "$PGPORT" >/dev/null 2>&1 && break
    sleep 0.5
  done
  "$PGBIN/pg_isready" -h localhost -p "$PGPORT" >/dev/null 2>&1 || die "Postgres did not become ready."
fi

# 4. Create the DB + the required extensions (idempotent).
psql -h localhost -p "$PGPORT" -U postgres -tc \
  "SELECT 1 FROM pg_database WHERE datname = '$PGDATABASE'" | grep -q 1 \
  || psql -h localhost -p "$PGPORT" -U postgres -c "CREATE DATABASE $PGDATABASE" >/dev/null
psql -h localhost -p "$PGPORT" -U postgres -d "$PGDATABASE" -c \
  "CREATE EXTENSION IF NOT EXISTS postgis; CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null \
  || die "Failed to create required extensions."
log "Database '$PGDATABASE' ready with postgis + pg_trgm + pgcrypto."

# 5. Persist the DB env to $CLAUDE_ENV_FILE so the gate + flyway pick it up.
DB_URL_VALUE="jdbc:postgresql://localhost:$PGPORT/$PGDATABASE"
persist() {
  local f="$1"
  {
    echo "export DB_URL=\"$DB_URL_VALUE\""
    echo "export DB_USER=\"postgres\""
    echo "export DB_PASSWORD=\"\""
  } >> "$f"
}
if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
  if [[ -f "$CLAUDE_ENV_FILE" ]]; then
    grep -vE '^export (DB_URL|DB_USER|DB_PASSWORD)=' "$CLAUDE_ENV_FILE" > "$CLAUDE_ENV_FILE.tmp" 2>/dev/null || true
    mv "$CLAUDE_ENV_FILE.tmp" "$CLAUDE_ENV_FILE"
  fi
  persist "$CLAUDE_ENV_FILE"
  log "Persisted DB_URL/DB_USER/DB_PASSWORD to \$CLAUDE_ENV_FILE."
else
  warn "\$CLAUDE_ENV_FILE unset — persisting to $HOME/.nearyou_backend_db_env instead."
  : > "$HOME/.nearyou_backend_db_env"; persist "$HOME/.nearyou_backend_db_env"
fi
export DB_URL="$DB_URL_VALUE" DB_USER="postgres" DB_PASSWORD=""

# 6. Apply Flyway migrations (unless --no-migrate). Idempotent: Flyway skips
#    already-applied versions. Slow on a cold gradle cache (first dep download).
if [[ "$DO_MIGRATE" -eq 1 ]]; then
  log "Applying Flyway migrations (./gradlew :backend:ktor:flywayMigrate) …"
  ./gradlew :backend:ktor:processResources :backend:ktor:flywayMigrate \
    --no-configuration-cache --console=plain >/tmp/nearyou_flyway.log 2>&1 \
    && log "Migrations applied." \
    || { tail -20 /tmp/nearyou_flyway.log >&2; die "flywayMigrate failed (see /tmp/nearyou_flyway.log)."; }
else
  log "Skipping migrate (--no-migrate). Run flywayMigrate before the test gate."
fi

log "Backend DB ready. Run the gate with:"
log "  ./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test"
