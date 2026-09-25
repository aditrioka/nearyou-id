#!/usr/bin/env bash
# session_start.sh: the single SessionStart hook entry (.claude/settings.json).
#
# Local session  → OpenSpec CLI + banner + health checks only. Never provisions
#                  anything on the operator's machine.
# Cloud session  → additionally provisions the per-session state that the
#                  cloud environment snapshot (scripts/setup_cloud_env.sh)
#                  cannot hold (running processes and $CLAUDE_ENV_FILE
#                  exports), so the sandbox behaves like a local session:
#     1. Android env   scripts/setup_android.sh (SDK restored from the
#                      snapshot → only re-persists JAVA_HOME/ANDROID_HOME/PATH)
#     2. dev/.env      scripts/setup_dev_env.sh (throwaway boot secrets)
#     3. Postgres      scripts/setup_backend_db.sh --no-migrate on :5433
#                      (start only; the snapshot cluster is already migrated)
#     4. Redis         redis-server on :6379, the port KotestProjectConfig
#                      probes, same as the local docker compose stack
#     5. dockerd       started detached for the CI-parity recipes (fresh
#                      postgis/redis containers, supabase-parity migrate)
#
# Why ONE sequential script instead of several hook entries: Claude Code runs
# every hook of a matcher group in parallel, so a verify hook would race the
# provisioning it checks, and two writers of $CLAUDE_ENV_FILE would drop
# each other's lines.
#
# Everything is idempotent. stdout becomes Claude's session context, so each
# step prints ONE status line; full logs land in $LOG_DIR.
# Opt out of cloud provisioning: NEARYOU_SKIP_PROVISION=1.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
# shellcheck source=scripts/_testing_context.sh
. "$REPO_ROOT/scripts/_testing_context.sh"

LOG_DIR="${NEARYOU_SESSION_LOG_DIR:-/tmp/nearyou-session}"
mkdir -p "$LOG_DIR"

say() { printf '[session-start] %s\n' "$*"; }
# run_step <name> <cmd...>, run quietly into $LOG_DIR/<name>.log, print one line.
run_step() {
  local name="$1"; shift
  local start=$SECONDS
  if "$@" >"$LOG_DIR/$name.log" 2>&1; then
    say "$name: ok ($((SECONDS - start))s)"
  else
    say "$name: FAILED ($((SECONDS - start))s), see $LOG_DIR/$name.log"
  fi
}

start_redis() {
  if redis-cli -p 6379 ping 2>/dev/null | grep -q PONG; then
    echo "redis already answering on :6379"; return 0
  fi
  command -v redis-server >/dev/null 2>&1 || { echo "redis-server not installed"; return 1; }
  redis-server --port 6379 --bind 127.0.0.1 --daemonize yes \
    --save '' --appendonly no --logfile "$LOG_DIR/redis-server.log" || return 1
  for _ in $(seq 1 20); do
    redis-cli -p 6379 ping 2>/dev/null | grep -q PONG && { echo "redis started on :6379"; return 0; }
    sleep 0.25
  done
  return 1
}

start_dockerd() {
  command -v dockerd >/dev/null 2>&1 || { echo "dockerd not installed"; return 1; }
  if docker info >/dev/null 2>&1; then echo "dockerd already running"; return 0; fi
  # Detached (own session) so it outlives this hook; not awaited, the first
  # `docker` call a few seconds later finds it up.
  setsid nohup dockerd >"$LOG_DIR/dockerd.log" 2>&1 < /dev/null &
  echo "dockerd starting (pid $!)"
}

# --- 1. OpenSpec CLI (every context, unchanged from the old separate hook) ---
run_step openspec bash scripts/setup_openspec.sh

# --- 2. Cloud-only per-session provisioning ----------------------------------
if is_cloud_container && [[ "${NEARYOU_SKIP_PROVISION:-0}" != "1" ]]; then
  if [[ ! -d "${ANDROID_HOME:-$HOME/android-sdk}/platforms" ]]; then
    say "Android SDK not in the environment snapshot, downloading now (~1-2 min)."
    say "Configure the environment setup script to skip this: dev/docs/cloud-environment.md"
  fi
  run_step android bash scripts/setup_android.sh
  run_step dev-env bash scripts/setup_dev_env.sh

  # Start only, never migrate here: a flywayMigrate is a full Gradle
  # configuration (seconds when the snapshot's Gradle cache is warm, ~8 min
  # cold), too slow to block session start on. The snapshot cluster is
  # migrated by setup_cloud_env.sh, the backend tests migrate on start
  # (KotestProjectConfig), and verify_backend_db.sh below says when the
  # schema trails the repo.
  run_step postgres bash scripts/setup_backend_db.sh --no-migrate
  run_step redis start_redis
  run_step dockerd start_dockerd
fi

# --- 3. Banner + health checks (every context) --------------------------------
bash scripts/_testing_context.sh --banner
bash scripts/verify_env.sh || say "Android env incomplete, run scripts/setup_android.sh"
bash scripts/verify_backend_db.sh || say "Backend test DB incomplete, run scripts/setup_backend_db.sh"
exit 0
