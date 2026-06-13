#!/usr/bin/env bash
# verify_env.sh — assert the Android build environment is healthy.
# Exits non-zero (and prints what is missing) if any required tool is absent,
# so a SessionStart hook / CI step can fail fast.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pick up persisted env if the current shell did not source it yet.
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for f in "${CLAUDE_ENV_FILE:-}" "$HOME/.nearyou_android_env"; do
    [[ -n "$f" && -f "$f" ]] && { set -a; . "$f"; set +a; break; }
  done
fi

ok()   { printf '\033[1;32m  ok \033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31m FAIL\033[0m %s\n' "$*"; FAILED=1; }
FAILED=0

echo "== Android env verification =="

# 1. JDK
if command -v java >/dev/null 2>&1; then
  ver="$(java -version 2>&1 | head -1)"
  ok "java: $ver"
  [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] \
    && ok "JAVA_HOME=$JAVA_HOME" \
    || bad "JAVA_HOME not set or invalid (got '${JAVA_HOME:-unset}')"
else
  bad "java not found on PATH"
fi

# 2. Android SDK + sdkmanager
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
  ok "ANDROID_HOME=$ANDROID_HOME"
else
  bad "ANDROID_HOME not set or directory missing (got '${ANDROID_HOME:-unset}')"
fi
SDKMANAGER="${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager"
if [[ -x "$SDKMANAGER" ]]; then
  ok "sdkmanager: $("$SDKMANAGER" --version 2>/dev/null | head -1)"
else
  bad "sdkmanager not found at $SDKMANAGER"
fi

# 3. adb (platform-tools)
if command -v adb >/dev/null 2>&1; then
  ok "adb: $(adb --version 2>/dev/null | head -1)"
elif [[ -x "${ANDROID_HOME:-}/platform-tools/adb" ]]; then
  ok "adb: $("${ANDROID_HOME}/platform-tools/adb" --version 2>/dev/null | head -1) (not on PATH)"
else
  bad "adb not found (platform-tools missing?)"
fi

# 4. Required SDK platform / build-tools present
compile_sdk="$(grep -E '^android-compileSdk' "$REPO_ROOT/gradle/libs.versions.toml" 2>/dev/null | head -1 | sed -E 's/[^0-9]//g')"
compile_sdk="${compile_sdk:-36}"
for p in "platforms/android-${compile_sdk}" "build-tools/${compile_sdk}.0.0"; do
  if [[ -d "${ANDROID_HOME:-}/$p" ]]; then ok "SDK component: $p"; else bad "SDK component missing: $p"; fi
done

# 5. Gradle wrapper
if [[ -x "$REPO_ROOT/gradlew" ]]; then
  ok "gradlew present + executable"
else
  bad "gradlew missing or not executable at $REPO_ROOT/gradlew"
fi

# Constraint guard: emulator/system-images must NOT be installed.
if [[ -d "${ANDROID_HOME:-}/emulator" || -d "${ANDROID_HOME:-}/system-images" ]]; then
  printf '\033[1;33m warn\033[0m emulator/system-images present — out of scope for this headless env\n'
fi

echo
if [[ "$FAILED" -ne 0 ]]; then
  echo "Environment INCOMPLETE — run: scripts/setup_android.sh"
  exit 1
fi
echo "Environment OK."
