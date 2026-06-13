#!/usr/bin/env bash
# setup_openspec.sh — provision the OpenSpec CLI for the nearyou-id Claude Code
# cloud sandbox.
#
# The OpenSpec CLI (`openspec new|validate|archive|list|status|instructions …`)
# is what the `/next-change`, `/opsx:apply`, and `/opsx:archive` skills drive to
# scaffold proposals, validate spec deltas (`--strict`), and sync + archive
# changes. The canonical package is `@fission-ai/openspec`
# (https://github.com/Fission-AI/OpenSpec) — NOT the public `openspec` npm name,
# which is a v0.0.0 placeholder squat with no bin (that squat is why a plain
# `npx openspec` fails with "could not determine executable to run").
#
# The cloud container is ephemeral (fresh clone + no persisted global npm state
# across reclaims), and the install is small + fast (~4 s), so this script is
# idempotent and safe to run on every SessionStart: it no-ops when the pinned
# version is already present and (re)installs otherwise — self-healing the CLI
# each session. Honors the project conventions: pinned version, env persisted to
# $CLAUDE_ENV_FILE, no hard-coded credentials, anonymous telemetry opted out.
set -euo pipefail

log()  { printf '\033[1;34m[setup-openspec]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup-openspec][warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[setup-openspec][err]\033[0m %s\n' "$*" >&2; exit 1; }

# --- Tunables (env-overridable) ---------------------------------------------
# Pin the CLI version for reproducibility (bump deliberately, like a libs pin).
OPENSPEC_VERSION="${OPENSPEC_VERSION:-1.4.1}"
OPENSPEC_PKG="@fission-ai/openspec@${OPENSPEC_VERSION}"
# OpenSpec engines require Node >= 20.19.0; the sandbox ships Node 22.
MIN_NODE_MAJOR=20

# ---------------------------------------------------------------------------
# 1. Node present + new enough
# ---------------------------------------------------------------------------
command -v node >/dev/null 2>&1 || die "node not found on PATH (OpenSpec needs Node >= 20.19.0)."
command -v npm  >/dev/null 2>&1 || die "npm not found on PATH."
node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if [[ "$node_major" -lt "$MIN_NODE_MAJOR" ]]; then
  die "Node $(node --version) is too old — OpenSpec needs >= 20.19.0."
fi

# ---------------------------------------------------------------------------
# 2. Install @fission-ai/openspec globally (idempotent: skip if already pinned)
# ---------------------------------------------------------------------------
current="$(openspec --version 2>/dev/null || true)"
if [[ "$current" == "$OPENSPEC_VERSION" ]]; then
  log "openspec $OPENSPEC_VERSION already installed — skipping."
else
  [[ -n "$current" ]] && log "openspec present at v$current; (re)installing $OPENSPEC_VERSION." \
                       || log "Installing $OPENSPEC_PKG ..."
  npm install -g "$OPENSPEC_PKG" >/dev/null \
    || die "npm install -g $OPENSPEC_PKG failed (is registry.npmjs.org in the network allowlist?)."
fi

# ---------------------------------------------------------------------------
# 3. Ensure the npm global bin is on PATH (it usually is — node/npx live there)
# ---------------------------------------------------------------------------
NPM_BIN="$(npm config get prefix 2>/dev/null)/bin"
case ":$PATH:" in
  *":$NPM_BIN:"*) : ;;                 # already on PATH
  *) export PATH="$NPM_BIN:$PATH" ;;
esac

# ---------------------------------------------------------------------------
# 4. Persist env to $CLAUDE_ENV_FILE: PATH (defensive) + telemetry opt-out.
#    Anonymous-usage telemetry is opted out so an automated sandbox makes no
#    surprise outbound calls (OPENSPEC_TELEMETRY=0). The block is fenced by
#    sentinel markers so re-runs replace it wholesale (no duplicate-line pile-up
#    and no risk of touching another script's PATH line, e.g. the Android block).
# ---------------------------------------------------------------------------
BEGIN_MARK="# >>> openspec-cli (managed by scripts/setup_openspec.sh) >>>"
END_MARK="# <<< openspec-cli <<<"
persist() {
  local f="$1"
  {
    echo "$BEGIN_MARK"
    echo "export OPENSPEC_TELEMETRY=0"
    echo "export PATH=\"$NPM_BIN:\$PATH\""
    echo "$END_MARK"
  } >> "$f"
}
if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
  # Drop any previous fenced block we wrote so re-runs don't pile up duplicates.
  if [[ -f "$CLAUDE_ENV_FILE" ]]; then
    awk -v b="$BEGIN_MARK" -v e="$END_MARK" '
      $0==b {skip=1; next}
      $0==e {skip=0; next}
      !skip {print}
    ' "$CLAUDE_ENV_FILE" > "$CLAUDE_ENV_FILE.tmp" 2>/dev/null || true
    mv "$CLAUDE_ENV_FILE.tmp" "$CLAUDE_ENV_FILE"
  fi
  persist "$CLAUDE_ENV_FILE"
  log "Persisted OPENSPEC_TELEMETRY=0 + PATH to \$CLAUDE_ENV_FILE."
else
  warn "\$CLAUDE_ENV_FILE is unset — set OPENSPEC_TELEMETRY=0 in your shell profile if you want to opt out of telemetry."
fi
export OPENSPEC_TELEMETRY=0

# ---------------------------------------------------------------------------
# 5. Verify
# ---------------------------------------------------------------------------
ver="$(openspec --version 2>/dev/null || true)"
[[ -n "$ver" ]] || die "openspec not runnable after install (PATH: $NPM_BIN)."
printf '\033[1;32m  ok \033[0m openspec %s (%s)\n' "$ver" "$(command -v openspec)"
log "Done. The /next-change, /opsx:apply, /opsx:archive skills can now use the CLI."
