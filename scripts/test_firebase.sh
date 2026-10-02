#!/usr/bin/env bash
# test_firebase.sh — build the :mobile:app debug + androidTest APKs and run the
# instrumented test suite on Firebase Test Lab (real devices in Google's farm —
# no local emulator). For "just launch + crawl my app" (no test code), use
# scripts/run_on_device.sh instead (Robo run).
#
# Required env (no credentials hard-coded): see scripts/_gcloud_lib.sh
#   GCP_SA_KEY_JSON | GOOGLE_APPLICATION_CREDENTIALS , FIREBASE_PROJECT_ID
# Optional:
#   FLAVOR (default staging) · FTL_DEVICE · OUT_DIR (default dev/device-runs/<ts>)
#
# Screenshots the tests write to the app's external files dir (screenshots/)
# are pulled off the device and downloaded into OUT_DIR with the run's logs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_build_apks.sh"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_gcloud_lib.sh"

FTL_DEVICE="${FTL_DEVICE:-model=shiba,version=34,locale=en_US,orientation=portrait}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dev/device-runs/$STAMP}"
RESULTS_DIR="instrumentation-$STAMP"
# The app package the instrumentation targets: production has no applicationIdSuffix.
APP_ID="id.nearyou.app"
[[ "${FLAVOR:-staging}" == "production" ]] || APP_ID="$APP_ID.${FLAVOR:-staging}"
trap gcloud_cleanup_key EXIT

# 1. gcloud ready + authenticated first: the build needs the shared
#    staging-debug keystore from Secret Manager (fetch_staging_debug_keystore).
ensure_gcloud
gcloud_auth
fetch_staging_debug_keystore

# 2. Build the APKs.
build_apks   # exports APP_APK + TEST_APK

# 3. Stage the APKs in the project's Test Lab results bucket with `gcloud storage
#    cp` and pass gs:// paths: the uploader built into `gcloud firebase test`
#    stalls on some links (half the runs from a local Mac hung on its resumable
#    PUT). Falls back to that uploader when the bucket lookup or copy fails.
bucket="$(curl -fsS -X POST -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://toolresults.googleapis.com/toolresults/v1beta3/projects/${FIREBASE_PROJECT_ID}:initializeSettings" \
  | sed -n 's/.*"defaultBucket": *"\([^"]*\)".*/\1/p' || true)"
if [[ -n "$bucket" ]] && gcloud storage cp "$APP_APK" "$TEST_APK" "gs://$bucket/$RESULTS_DIR/"; then
  APP_APK="gs://$bucket/$RESULTS_DIR/$(basename "$APP_APK")"
  TEST_APK="gs://$bucket/$RESULTS_DIR/$(basename "$TEST_APK")"
fi

# 4. Dispatch the instrumentation run.
echo "[firebase] dispatching instrumentation run on $FTL_DEVICE (project=$FIREBASE_PROJECT_ID)..."
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/gcloud.log"
set +e
gcloud firebase test android run \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "$FTL_DEVICE" \
  --timeout 15m \
  --results-dir "$RESULTS_DIR" \
  --directories-to-pull "/sdcard/Android/data/$APP_ID/files/screenshots" \
  --quiet \
  2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
set -e

# 5. Pull the logs, video and pulled screenshots locally (best-effort).
pull_testlab_artifacts "$LOG" "$OUT_DIR"

echo
find "$OUT_DIR" -name '*.png' | sed 's/^/[firebase] screenshot: /'
if [[ $rc -eq 0 ]]; then
  echo "[firebase] RESULT: PASS — all instrumented tests passed on Test Lab. Artifacts: $OUT_DIR"
else
  echo "[firebase] RESULT: FAIL (gcloud exit $rc) — see the Test Lab results URL above / $LOG."
fi
exit $rc
