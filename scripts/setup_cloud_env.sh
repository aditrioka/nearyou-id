#!/usr/bin/env bash
# setup_cloud_env.sh: build the nearyou-id Claude Code cloud ENVIRONMENT.
#
# Called by the environment's "Setup script" (claude.ai/code → environment →
# Edit; the paste-in wrapper is in dev/docs/cloud-environment.md). It runs as
# root once per environment build; Anthropic then snapshots the filesystem and
# every later session starts from that snapshot. So this script installs the
# SLOW, on-disk things, and scripts/session_start.sh (SessionStart hook) does
# the fast per-session part (start services, persist env).
#
# What lands in the snapshot:
#   - apt: PostGIS for the local Postgres, oathtool (admin-panel TOTP),
#     JDK 17 (the Android Gradle daemon JDK), redis-server
#   - Android SDK (scripts/setup_android.sh → ~/android-sdk)
#   - Google Cloud SDK (Firebase Test Lab dispatch → ~/google-cloud-sdk)
#   - Docker images the CI-parity recipes use (postgis, redis, flyway)
#   - OpenSpec CLI (scripts/setup_openspec.sh)
#   - a migrated Postgres cluster (/var/tmp/nearyou_pgdata) + a warm Gradle
#     dependency cache (~/.gradle), time-boxed by WARM_TIMEOUT
#
# Budget: the environment setup script should finish in ~5 minutes, so the
# independent downloads run in parallel and the Gradle step is time-boxed;
# whatever it downloaded before the cutoff stays cached. Processes do not
# survive the snapshot, so everything started here is stopped again.
#
# Always exits 0: a failed step only means session_start.sh does that work per
# session instead; it must never make session start fail.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

LOG_DIR="${NEARYOU_SETUP_LOG_DIR:-/var/tmp/nearyou-setup}"
WARM_TIMEOUT="${WARM_TIMEOUT:-210}"
DOCKER_IMAGES=(postgis/postgis:16-3.4 redis:7-alpine flyway/flyway:10)
mkdir -p "$LOG_DIR"

log() { printf '\033[1;35m[cloud-env]\033[0m %s\n' "$*"; }
T0=$SECONDS

# ---------------------------------------------------------------------------
# 1. apt, serial and first: setup_android.sh / setup_backend_db.sh would
#    otherwise apt-install JDK 17 / PostGIS in parallel and fight over the
#    dpkg lock.
# ---------------------------------------------------------------------------
apt_step() {
  export DEBIAN_FRONTEND=noninteractive
  local pgmajor pkgs
  pgmajor="$(ls /usr/lib/postgresql 2>/dev/null | sort -V | tail -1)"
  pkgs=(oathtool openjdk-17-jdk-headless redis-server)
  if [[ -n "$pgmajor" ]]; then
    pkgs+=("postgresql-$pgmajor-postgis-3")
  else
    pkgs+=(postgresql postgresql-contrib postgis)
  fi
  apt-get update -q && apt-get install -y -q "${pkgs[@]}"
}
log "apt: installing PostGIS, oathtool, JDK 17, redis-server ..."
if apt_step >"$LOG_DIR/apt.log" 2>&1; then
  log "apt: ok ($((SECONDS - T0))s)"
else
  log "apt: FAILED, see $LOG_DIR/apt.log (continuing)"
fi

# ---------------------------------------------------------------------------
# 2. Independent downloads, in parallel.
# ---------------------------------------------------------------------------
android_step() { bash scripts/setup_android.sh; }

gcloud_step() {
  # shellcheck source=scripts/_gcloud_lib.sh
  . scripts/_gcloud_lib.sh
  ensure_gcloud
  local rc=$?
  rm -f /tmp/gcloud.tgz
  return "$rc"
}

docker_step() {
  command -v dockerd >/dev/null 2>&1 || { echo "dockerd not installed, skipping image pre-pull"; return 0; }
  local started=0 img rc=0
  if ! docker info >/dev/null 2>&1; then
    dockerd >"$LOG_DIR/dockerd.log" 2>&1 &
    started=1
    for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
  fi
  for img in "${DOCKER_IMAGES[@]}"; do
    docker pull -q "$img" || rc=1
  done
  if [[ "$started" -eq 1 ]]; then
    kill "$(cat /var/run/docker.pid 2>/dev/null)" 2>/dev/null || true
    for _ in $(seq 1 15); do docker info >/dev/null 2>&1 || break; sleep 1; done
  fi
  return "$rc"
}

openspec_step() { bash scripts/setup_openspec.sh; }

# Starts Postgres, migrates (which is what warms the Gradle cache), then stops
# Postgres and the Gradle daemon so the snapshot holds a clean cluster.
backend_step() {
  local rc=0
  timeout "$WARM_TIMEOUT" bash scripts/setup_backend_db.sh || rc=$?
  local pgbin
  pgbin="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)"
  if [[ -n "$pgbin" && -s /var/tmp/nearyou_pgdata/PG_VERSION ]]; then
    sudo -u postgres "$pgbin/pg_ctl" -D /var/tmp/nearyou_pgdata -m fast stop || true
  fi
  ./gradlew --stop >/dev/null 2>&1 || true
  if [[ "$rc" -eq 124 ]]; then
    # Not a failure: the partial cache is kept, session_start.sh finishes the migrate.
    echo "Gradle warm-up hit WARM_TIMEOUT=${WARM_TIMEOUT}s, partial cache kept."
    return 0
  fi
  return "$rc"
}

declare -A PIDS=()
for step in android gcloud docker openspec backend; do
  "${step}_step" >"$LOG_DIR/$step.log" 2>&1 &
  PIDS[$step]=$!
done

FAILED=()
for step in android gcloud docker openspec backend; do
  if wait "${PIDS[$step]}"; then
    log "$step: ok"
  else
    log "$step: FAILED, see $LOG_DIR/$step.log"
    FAILED+=("$step")
  fi
done

log "Done in $((SECONDS - T0))s.${FAILED[*]:+ Failed steps (session_start.sh retries per session): ${FAILED[*]}}"
exit 0
