#!/usr/bin/env bash
# run_on_device.sh — "run my change on a REAL device, from anywhere."
#
# The vibe-code-from-your-phone loop: the cloud sandbox can't show a screen, so
# this builds the staging-debug APK and launches it on a real physical device in
# Firebase Test Lab via a **Robo run** (Google's automatic UI crawler — no test
# code needed). It then downloads the screenshots + video Robo captured into a
# local folder so the agent can send them straight back to you.
#
# This script is invoked both locally / from the cloud sandbox AND by the CI
# workflow .github/workflows/device-run.yml (which provides credentials via the
# google-github-actions/auth step → GOOGLE_APPLICATION_CREDENTIALS).
#
# Usage:
#   scripts/run_on_device.sh                 # Robo-crawl on the default device
#   DEVICE='model=shiba,version=34' scripts/run_on_device.sh
#
# Required env (no credentials hard-coded): see scripts/_gcloud_lib.sh
#   GCP_SA_KEY_JSON | GOOGLE_APPLICATION_CREDENTIALS , FIREBASE_PROJECT_ID
# Optional:
#   FLAVOR (default staging) · DEVICE · ROBO_TIMEOUT (default 4m) · OUT_DIR
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_build_apks.sh"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_gcloud_lib.sh"

DEVICE="${DEVICE:-model=shiba,version=34,locale=en_US,orientation=portrait}"
ROBO_TIMEOUT="${ROBO_TIMEOUT:-4m}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dev/device-runs/$STAMP}"
RESULTS_DIR="robo-$STAMP"

trap gcloud_cleanup_key EXIT

# 1. Build the app APK (Robo needs only the app APK, not the test APK).
build_apks   # exports APP_APK + TEST_APK

# 2. gcloud ready + authenticated.
ensure_gcloud
gcloud_auth

# 3. Robo run on a real device.
echo "[device] launching Robo run on $DEVICE (project=$FIREBASE_PROJECT_ID)..."
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/gcloud.log"
set +e
gcloud firebase test android run \
  --type robo \
  --app "$APP_APK" \
  --device "$DEVICE" \
  --timeout "$ROBO_TIMEOUT" \
  --results-dir "$RESULTS_DIR" \
  2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
set -e

# 4. Decide the verdict from the Test Lab OUTCOME table, NOT gcloud's exit code:
#    `gcloud firebase test` can exit non-zero even when the Robo run PASSED
#    (observed on a clean Passed run). Trust the printed OUTCOME row instead.
outcome="Unknown"
if grep -qw "Passed" "$LOG"; then
  outcome="Passed"; rc=0
elif grep -qwE "Failed|Inconclusive|Skipped|Error" "$LOG"; then
  outcome="$(grep -owE "Failed|Inconclusive|Skipped|Error" "$LOG" | head -1)"; rc=1
fi

# 5. Pull screenshots + video locally so the agent can surface them.
pull_testlab_artifacts "$LOG" "$OUT_DIR"

echo
echo "[device] outcome=$outcome (gcloud raw exit overridden by OUTCOME table) · artifacts: $OUT_DIR"
shopt -s globstar nullglob 2>/dev/null || true
pngs=("$OUT_DIR"/**/*.png "$OUT_DIR"/*.png); vids=("$OUT_DIR"/**/*.mp4 "$OUT_DIR"/*.mp4)
[[ ${#pngs[@]} -gt 0 ]] && { echo "[device] screenshots:"; printf '    %s\n' "${pngs[@]}"; }
[[ ${#vids[@]} -gt 0 ]] && { echo "[device] video:";       printf '    %s\n' "${vids[@]}"; }
[[ $rc -eq 0 ]] && echo "[device] RESULT: app launched + crawled OK on a real device (OUTCOME: $outcome)." \
               || echo "[device] RESULT: run reported issues (OUTCOME: $outcome) — check $LOG / the console link above."
exit $rc
