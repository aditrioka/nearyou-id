#!/usr/bin/env bash
# _build_apks.sh — shared helper sourced by test_firebase.sh / test_browserstack.sh.
# Builds the app + androidTest APKs for the chosen flavor and exports their paths.
#
# Exports: APP_APK, TEST_APK
# Honors:  FLAVOR (default: staging — real api-staging backend, side-by-side install)

build_apks() {
  local repo_root flavor variant variant_cap
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

  # Pick up persisted Android env if not already in the shell.
  if [[ -z "${ANDROID_HOME:-}" ]]; then
    for f in "${CLAUDE_ENV_FILE:-}" "$HOME/.nearyou_android_env"; do
      [[ -n "$f" && -f "$f" ]] && { set -a; . "$f"; set +a; break; }
    done
  fi

  flavor="${FLAVOR:-staging}"
  # Capitalise first letter for the Gradle task name (staging -> Staging).
  variant_cap="$(tr '[:lower:]' '[:upper:]' <<<"${flavor:0:1}")${flavor:1}"

  echo "[build] assembling ${flavor}Debug app + androidTest APKs..."
  ( cd "$repo_root" && ./gradlew \
      ":mobile:app:assemble${variant_cap}Debug" \
      ":mobile:app:assemble${variant_cap}DebugAndroidTest" \
      --stacktrace ) || { echo "[build] ERROR: gradle assemble failed."; exit 1; }

  local apk_dir="$repo_root/mobile/app/build/outputs/apk"
  APP_APK="$(ls "$apk_dir/$flavor/debug/"*.apk 2>/dev/null | head -1)"
  TEST_APK="$(ls "$apk_dir/androidTest/$flavor/debug/"*.apk 2>/dev/null | head -1)"

  [[ -f "${APP_APK:-}" ]]  || { echo "[build] ERROR: app APK not found under $apk_dir/$flavor/debug/"; exit 1; }
  [[ -f "${TEST_APK:-}" ]] || { echo "[build] ERROR: androidTest APK not found under $apk_dir/androidTest/$flavor/debug/"; exit 1; }
  export APP_APK TEST_APK
  echo "[build] app APK : $APP_APK"
  echo "[build] test APK: $TEST_APK"
}
