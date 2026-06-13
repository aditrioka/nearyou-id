#!/usr/bin/env bash
# setup_android.sh — provision the Android build toolchain for the nearyou-id
# Claude Code cloud sandbox (headless Linux VM, no KVM/GPU).
#
# Goal: build the :mobile:app APK + instrumented-test APK so they can be
# dispatched to a device farm (Firebase Test Lab / BrowserStack). It deliberately
# does NOT install the `emulator` or `system-images` packages — a headless VM
# without KVM cannot run them.
#
# Idempotent: every step checks for an existing artefact before doing work, so
# re-runs (and environment caching) are cheap. Safe to run at SessionStart.
#
# Honors the task hard constraints: JDK 17, cmdline-tools + platform-tools +
# platforms;android-35 + build-tools;35.0.0 (plus the repo-detected android-36 /
# build-tools;36.0.0 that compileSdk=36 actually needs), env persisted to
# $CLAUDE_ENV_FILE, no hard-coded credentials.
set -euo pipefail

log()  { printf '\033[1;34m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup][warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[setup][err]\033[0m %s\n' "$*" >&2; exit 1; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- Tunables (env-overridable) ---------------------------------------------
JDK_VERSION="${JDK_VERSION:-17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
# cmdline-tools build number (Google keeps the *_latest alias current).
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-11076708}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

# SDK packages. The constraint baseline (35) plus the repo's real compileSdk
# (parsed from the version catalog) so the APK actually compiles. NEVER add
# `emulator` or `system-images` here.
compile_sdk="$(grep -E '^android-compileSdk' "$REPO_ROOT/gradle/libs.versions.toml" \
  | head -1 | sed -E 's/[^0-9]//g')"
compile_sdk="${compile_sdk:-36}"
SDK_PACKAGES=(
  "platform-tools"
  "platforms;android-35"
  "build-tools;35.0.0"
  "platforms;android-${compile_sdk}"
  "build-tools;${compile_sdk}.0.0"
)

# ---------------------------------------------------------------------------
# 1. JDK 17
# ---------------------------------------------------------------------------
JAVA_17_HOME="/usr/lib/jvm/java-${JDK_VERSION}-openjdk-amd64"
if [[ -x "$JAVA_17_HOME/bin/java" ]]; then
  log "JDK ${JDK_VERSION} already present at $JAVA_17_HOME — skipping install."
else
  log "Installing OpenJDK ${JDK_VERSION}..."
  SUDO=""; [[ "$(id -u)" -ne 0 ]] && SUDO="sudo"
  export DEBIAN_FRONTEND=noninteractive
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq "openjdk-${JDK_VERSION}-jdk-headless"
  [[ -x "$JAVA_17_HOME/bin/java" ]] || \
    JAVA_17_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
export JAVA_HOME="$JAVA_17_HOME"
log "JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

# Gradle's compile toolchain is jvmToolchain(21); JDK 21 is pre-installed and
# auto-detected by Gradle in /usr/lib/jvm, so no foojay network download is
# needed. Surface a warning if it ever goes missing.
[[ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]] || \
  warn "JDK 21 (Gradle compile toolchain target) not found in /usr/lib/jvm — toolchain may try a network download."

# ---------------------------------------------------------------------------
# 2. Android cmdline-tools (installed under cmdline-tools/latest)
# ---------------------------------------------------------------------------
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ -x "$SDKMANAGER" ]]; then
  log "cmdline-tools already installed at $ANDROID_HOME/cmdline-tools/latest — skipping download."
else
  log "Downloading Android cmdline-tools ($CMDLINE_TOOLS_ZIP)..."
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/cmdline-tools.zip" \
    || die "cmdline-tools download failed (is dl.google.com in the network allowlist?)"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  [[ -x "$SDKMANAGER" ]] || die "sdkmanager not found after extraction."
  log "cmdline-tools installed."
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

# ---------------------------------------------------------------------------
# 3. Accept licenses + install SDK packages (idempotent: sdkmanager no-ops
#    packages already at the requested revision)
# ---------------------------------------------------------------------------
# `yes` takes SIGPIPE when sdkmanager closes the pipe early, which under
# `set -o pipefail` would fail the pipeline even on success — so check
# sdkmanager's OWN exit status via PIPESTATUS, with pipefail off for the call.
log "Accepting SDK licenses..."
set +o pipefail
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
set -o pipefail

log "Installing SDK packages: ${SDK_PACKAGES[*]}"
set +o pipefail
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${SDK_PACKAGES[@]}" >/dev/null
sdk_rc=${PIPESTATUS[1]}
set -o pipefail
[[ "$sdk_rc" -eq 0 ]] || die "sdkmanager package install failed (exit $sdk_rc)."

# Guard the hard constraint: ensure no emulator/system-image slipped in.
if "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null \
     | grep -Eiq 'system-images|^[[:space:]]*emulator'; then
  warn "An emulator/system-image package is installed — that is explicitly out of scope for this headless env."
fi

# ---------------------------------------------------------------------------
# 4. local.properties (gitignored) so IDE / non-env invocations find the SDK
# ---------------------------------------------------------------------------
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO_ROOT/local.properties"
log "Wrote $REPO_ROOT/local.properties (sdk.dir)."

# ---------------------------------------------------------------------------
# 5. Persist env to $CLAUDE_ENV_FILE
# ---------------------------------------------------------------------------
PATH_ADD="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
persist() {
  local f="$1"
  {
    echo "export JAVA_HOME=\"$JAVA_HOME\""
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export PATH=\"$PATH_ADD:\$PATH\""
  } >> "$f"
}
if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
  # Drop any previous block we wrote so re-runs don't pile up duplicates.
  if [[ -f "$CLAUDE_ENV_FILE" ]]; then
    grep -vE '(JAVA_HOME|ANDROID_HOME|ANDROID_SDK_ROOT)=|cmdline-tools/latest/bin' \
      "$CLAUDE_ENV_FILE" > "$CLAUDE_ENV_FILE.tmp" 2>/dev/null || true
    mv "$CLAUDE_ENV_FILE.tmp" "$CLAUDE_ENV_FILE"
  fi
  persist "$CLAUDE_ENV_FILE"
  log "Persisted env to \$CLAUDE_ENV_FILE ($CLAUDE_ENV_FILE)."
else
  warn "\$CLAUDE_ENV_FILE is unset — persisting to $HOME/.nearyou_android_env instead."
  : > "$HOME/.nearyou_android_env"; persist "$HOME/.nearyou_android_env"
  warn "Add 'source $HOME/.nearyou_android_env' to your shell profile, or export the vars manually."
fi

# Export into the current process too, so a same-shell verify works immediately.
export PATH="$PATH_ADD:$PATH"

log "Done. Verify with: scripts/verify_env.sh"
