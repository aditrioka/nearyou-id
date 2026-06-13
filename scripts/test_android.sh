#!/usr/bin/env bash
# test_android.sh — THE one command to run the :mobile:app instrumented test suite.
# Call this instead of `gradlew connected…` / `gcloud firebase test` directly — it
# routes to the right target automatically so neither you nor the agent has to
# decide. The decision lives in scripts/_testing_context.sh (single source of
# truth), keyed capability-first (robust under Remote Control — see that file):
#
#   - LOCAL + device/emulator attached  -> Gradle `connectedAndroidTest` on it.
#   - LOCAL + no device                 -> stop with guidance (start an emulator
#                                          via the verify-loop skill, attach a
#                                          device, or FARM=firebase to use the farm).
#   - CLOUD sandbox (no device)         -> dispatch to Firebase Test Lab.
#
# Overrides: FARM=local|firebase|browserstack  or  FORCE_TESTING_CONTEXT=cloud|local.
# Flavor via FLAVOR (default staging).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Pick up persisted Android env if the shell doesn't have it yet.
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for f in "${CLAUDE_ENV_FILE:-}" "$HOME/.nearyou_android_env"; do
    [[ -n "$f" && -f "$f" ]] && { set -a; . "$f"; set +a; break; }
  done
fi
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_testing_context.sh"

FLAVOR="${FLAVOR:-staging}"
variant_cap="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}"
device_count="$(adb_device_count)"

# Resolve the farm: explicit FARM wins; otherwise derive from the testing context.
FARM="${FARM:-}"
if [[ -z "$FARM" ]]; then
  if [[ "$(testing_context)" == "cloud" ]]; then
    FARM="firebase"
  elif [[ "$device_count" -gt 0 ]]; then
    FARM="local"
  else
    FARM="local-no-device"
  fi
fi

echo "[test] flavor=$FLAVOR · context=$(testing_context) · adb devices=$device_count · routing -> $FARM"
case "$FARM" in
  local)
    [[ "$device_count" -gt 0 ]] || { echo "[test] FARM=local but no adb device attached."; exit 1; }
    exec ./gradlew ":mobile:app:connected${variant_cap}DebugAndroidTest" --stacktrace
    ;;
  local-no-device)
    cat >&2 <<'MSG'
[test] Local session with no emulator/device attached.
       Start one and re-run, e.g.:
         - boot an emulator (see the verify-loop skill §B), or
         - plug in a device + `adb devices`, then re-run this script, or
         - dispatch to the device farm instead: FARM=firebase scripts/test_android.sh
MSG
    exit 1
    ;;
  firebase)
    exec "$REPO_ROOT/scripts/test_firebase.sh"
    ;;
  browserstack)
    exec "$REPO_ROOT/scripts/test_browserstack.sh"
    ;;
  *)
    echo "[test] unknown FARM='$FARM' (use local|firebase|browserstack)"; exit 1
    ;;
esac
