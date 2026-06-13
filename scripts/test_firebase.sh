#!/usr/bin/env bash
# test_firebase.sh — build the :mobile:app debug + androidTest APKs and run the
# instrumented test suite on Firebase Test Lab (real devices in Google's farm —
# no local emulator). For "just launch + crawl my app" (no test code), use
# scripts/run_on_device.sh instead (Robo run).
#
# Required env (no credentials hard-coded): see scripts/_gcloud_lib.sh
#   GCP_SA_KEY_JSON | GOOGLE_APPLICATION_CREDENTIALS , FIREBASE_PROJECT_ID
# Optional:
#   FLAVOR (default staging) · FTL_DEVICE
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_build_apks.sh"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_gcloud_lib.sh"

FTL_DEVICE="${FTL_DEVICE:-model=shiba,version=34,locale=en_US,orientation=portrait}"
trap gcloud_cleanup_key EXIT

# 1. Build the APKs.
build_apks   # exports APP_APK + TEST_APK

# 2. gcloud ready + authenticated.
ensure_gcloud
gcloud_auth

# 3. Dispatch the instrumentation run.
echo "[firebase] dispatching instrumentation run on $FTL_DEVICE (project=$FIREBASE_PROJECT_ID)..."
set +e
gcloud firebase test android run \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "$FTL_DEVICE" \
  --timeout 15m \
  --quiet
rc=$?
set -e

echo
if [[ $rc -eq 0 ]]; then
  echo "[firebase] RESULT: PASS — all instrumented tests passed on Test Lab."
else
  echo "[firebase] RESULT: FAIL (gcloud exit $rc) — see the Test Lab results URL above."
fi
exit $rc
