#!/usr/bin/env bash
# test_android.sh — one command, same everywhere: run the :mobile:app instrumented
# test suite on whatever device is available.
#
# Parity wrapper that keeps local-device and cloud-sandbox dev in sync:
#   - LOCAL with a device/emulator attached (adb sees it)  -> run directly on it
#     via Gradle `connectedAndroidTest` (fast inner loop).
#   - CLOUD sandbox (no device) or no adb device attached   -> dispatch to the
#     device farm (Firebase Test Lab by default; BrowserStack if FARM=browserstack).
#
# Override the auto-detection with FARM=local|firebase|browserstack.
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

FLAVOR="${FLAVOR:-staging}"
variant_cap="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}"

# Auto-detect a connected device unless FARM is forced.
adb_bin="$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")"
device_count=0
if [[ -x "$adb_bin" ]]; then
  device_count="$("$adb_bin" devices 2>/dev/null | grep -cE '\sdevice$' || true)"
fi

FARM="${FARM:-}"
if [[ -z "$FARM" ]]; then
  if [[ "$device_count" -gt 0 ]]; then FARM="local"; else FARM="firebase"; fi
fi

echo "[test] flavor=$FLAVOR · adb devices=$device_count · routing -> $FARM"
case "$FARM" in
  local)
    [[ "$device_count" -gt 0 ]] || { echo "[test] FARM=local but no adb device attached."; exit 1; }
    exec ./gradlew ":mobile:app:connected${variant_cap}DebugAndroidTest" --stacktrace
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
