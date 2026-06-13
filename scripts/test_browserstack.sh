#!/usr/bin/env bash
# test_browserstack.sh — build the :mobile:app debug + androidTest APKs and run
# the instrumented (Espresso) suite on BrowserStack App Automate's real-device
# cloud. Uses the REST API via curl — no local emulator, no extra CLI.
#
# Required env (no credentials are hard-coded):
#   BROWSERSTACK_USERNAME
#   BROWSERSTACK_ACCESS_KEY
#
# Optional env:
#   FLAVOR        product flavor to build (default: staging)
#   BS_DEVICES    JSON array of devices (default: a single recent Pixel)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_build_apks.sh"

: "${BROWSERSTACK_USERNAME:?set BROWSERSTACK_USERNAME}"
: "${BROWSERSTACK_ACCESS_KEY:?set BROWSERSTACK_ACCESS_KEY}"
AUTH="${BROWSERSTACK_USERNAME}:${BROWSERSTACK_ACCESS_KEY}"
BS_DEVICES="${BS_DEVICES:-[\"Google Pixel 8-14.0\"]}"
API="https://api-cloud.browserstack.com/app-automate/espresso"

json_field() { sed -E "s/.*\"$1\"[: ]*\"?([^\",}]+)\"?.*/\1/"; }

# --- 1. Build the APKs -------------------------------------------------------
build_apks   # sets APP_APK + TEST_APK

# --- 2. Upload app APK -------------------------------------------------------
echo "[browserstack] uploading app APK..."
app_resp="$(curl -sf -u "$AUTH" -X POST "$API/v2/app" -F "file=@${APP_APK}")" \
  || { echo "[browserstack] app upload failed (allowlist api-cloud.browserstack.com)"; exit 1; }
APP_URL="$(echo "$app_resp" | grep -o '"app_url"[^,]*' | json_field app_url)"
[[ -n "$APP_URL" ]] || { echo "[browserstack] no app_url in response: $app_resp"; exit 1; }
echo "[browserstack] app_url=$APP_URL"

# --- 3. Upload androidTest (test-suite) APK ---------------------------------
echo "[browserstack] uploading test-suite APK..."
test_resp="$(curl -sf -u "$AUTH" -X POST "$API/v2/test-suite" -F "file=@${TEST_APK}")" \
  || { echo "[browserstack] test-suite upload failed"; exit 1; }
TEST_URL="$(echo "$test_resp" | grep -o '"test_suite_url"[^,]*' | json_field test_suite_url)"
[[ -n "$TEST_URL" ]] || { echo "[browserstack] no test_suite_url in response: $test_resp"; exit 1; }
echo "[browserstack] test_suite_url=$TEST_URL"

# --- 4. Trigger the build ----------------------------------------------------
echo "[browserstack] triggering Espresso build on devices $BS_DEVICES..."
run_resp="$(curl -sf -u "$AUTH" -X POST "$API/v2/build" \
  -H 'Content-Type: application/json' \
  -d "{\"app\":\"$APP_URL\",\"testSuite\":\"$TEST_URL\",\"devices\":$BS_DEVICES,\"project\":\"nearyou-id\"}")" \
  || { echo "[browserstack] build trigger failed"; exit 1; }
BUILD_ID="$(echo "$run_resp" | grep -o '"build_id"[^,]*' | json_field build_id)"
[[ -n "$BUILD_ID" ]] || { echo "[browserstack] no build_id in response: $run_resp"; exit 1; }
echo "[browserstack] build_id=$BUILD_ID — dashboard: https://app-automate.browserstack.com/builds/$BUILD_ID"

# --- 5. Poll until the build finishes ---------------------------------------
echo "[browserstack] polling build status..."
status="running"; deadline=$(( $(date +%s) + 1200 ))
while [[ "$status" == "running" || "$status" == "queued" ]]; do
  [[ "$(date +%s)" -gt "$deadline" ]] && { echo "[browserstack] timeout waiting for build."; exit 1; }
  sleep 20
  poll="$(curl -sf -u "$AUTH" "$API/v2/builds/$BUILD_ID")" || continue
  status="$(echo "$poll" | grep -o '"status"[^,]*' | head -1 | json_field status)"
  echo "[browserstack]   status=$status"
done

# --- 6. Summarise ------------------------------------------------------------
echo
case "$status" in
  passed) echo "[browserstack] RESULT: PASS — all instrumented tests passed."; exit 0 ;;
  *)      echo "[browserstack] RESULT: FAIL (status=$status) — see dashboard above."; exit 1 ;;
esac
