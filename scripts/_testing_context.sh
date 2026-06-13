#!/usr/bin/env bash
# _testing_context.sh — single source of truth for "where do mobile tests run?".
#
# Sourced by scripts/test_android.sh and the SessionStart hook so the routing
# decision lives in ONE place (no drift). Run directly with `--banner` to print
# the session banner.
#
#   testing_context   -> echoes "cloud" or "local"
#   adb_device_count  -> echoes the number of attached adb devices
#
# Detection precedence (capability-first, so it is robust even if Claude Code's
# env flags are ambiguous):
#   1. FORCE_TESTING_CONTEXT=cloud|local — explicit override, always wins.
#   2. An adb device/emulator is attached  -> "local" (run on it). This is the
#      ACTUAL capability, and it stays correct under Remote Control
#      (`claude --remote-control`), which keeps execution on your local machine.
#   3. No device + a cloud-CONTAINER marker is present -> "cloud". These markers
#      are injected by the Claude-Code-on-the-web environment runner that
#      provisions the container; they have NO local equivalent.
#   4. Otherwise -> "local" (a local session with no device/emulator up yet).
#
# We deliberately do NOT key on CLAUDE_CODE_REMOTE or IS_SANDBOX: per
# https://code.claude.com/docs/en/remote-control Remote Control runs locally, and
# those flags can plausibly be set by `claude --remote-control` / `claude --sandbox`
# on a LOCAL machine — using them would misroute a local session to the farm.
# Cloud markers that the local remote-control path cannot have:
#   CLAUDE_CODE_CONTAINER_ID, CLAUDE_CODE_REMOTE_ENVIRONMENT_TYPE.

adb_device_count() {
  local adb_bin
  adb_bin="$(command -v adb 2>/dev/null || echo "${ANDROID_HOME:-}/platform-tools/adb")"
  [ -x "$adb_bin" ] || { echo 0; return; }
  "$adb_bin" devices 2>/dev/null | grep -cE '\sdevice$' || echo 0
}

testing_context() {
  if [ -n "${FORCE_TESTING_CONTEXT:-}" ]; then echo "$FORCE_TESTING_CONTEXT"; return; fi
  [ "$(adb_device_count)" -gt 0 ] 2>/dev/null && { echo "local"; return; }
  if [ -n "${CLAUDE_CODE_CONTAINER_ID:-}" ] || [ -n "${CLAUDE_CODE_REMOTE_ENVIRONMENT_TYPE:-}" ]; then
    echo "cloud"; return
  fi
  echo "local"
}

testing_context_banner() {
  local ctx; ctx="$(testing_context)"
  if [ "$ctx" = "cloud" ]; then
    printf '\033[1;36m[testing-context]\033[0m CLOUD sandbox — mobile tests route to Firebase Test Lab.\n'
    printf '    instrumented: scripts/test_android.sh   ·   see-it-run (Robo): scripts/run_on_device.sh\n'
  else
    local n; n="$(adb_device_count)"
    printf '\033[1;36m[testing-context]\033[0m LOCAL — mobile tests run on emulator/connected device (adb devices: %s).\n' "$n"
    printf '    one command, auto-routes: scripts/test_android.sh   (override with FARM= / FORCE_TESTING_CONTEXT=)\n'
  fi
}

# Allow `bash scripts/_testing_context.sh --banner`
if [ "${1:-}" = "--banner" ]; then testing_context_banner; fi
