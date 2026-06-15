#!/usr/bin/env bash
#
# check-dockerfile-module-copies.sh — guard against settings.gradle.kts ↔ Dockerfile drift.
#
# WHY THIS EXISTS
# ---------------
# The backend Docker image is built with
#   ./gradlew :backend:ktor:installDist -PincludeMobile=false
# Gradle still EVALUATES every `include(":...")` in settings.gradle.kts during
# the settings phase and fails hard if a module's project directory does not
# exist in the build context:
#
#   Configuring project ':infra:supabase-realtime' without an existing directory
#   is not allowed. The configured projectDirectory
#   '/workspace/infra/supabase-realtime' does not exist ...
#
# The Dockerfile copies a HAND-MAINTAINED list of module directories (not
# `COPY .`), so any non-mobile-gated `include(":x:y")` whose `x/y/build.gradle.kts`
# is not COPY'd makes that directory absent in the builder → settings evaluation
# fails → EVERY staging/prod deploy breaks. This is invisible to PR CI (the
# lint/test lanes build with mobile included, outside Docker) — only the
# post-merge `deploy-staging` run fails, silently, after merge.
# Precedent: PR #247 added `:infra:supabase-realtime` (a mobile-only KMP module)
# as a non-gated include() and broke all deploys on 2026-06-13.
#
# WHAT THIS CHECKS
# ----------------
# For every `include(":...")` in settings.gradle.kts that is NOT inside the
# `if (includeMobile.toBoolean()) { ... }` gate, assert the Dockerfile has a
# matching `COPY <path>/build.gradle.kts ...` line — that COPY is what makes the
# module's project directory exist in the builder, which is the exact thing whose
# absence triggers the "without an existing directory" failure. Mobile-gated
# modules are excluded from the backend Docker build by design and are NOT checked.
#
# SCOPE / LIMITATIONS
# -------------------
# This catches the "directory does not exist" class precisely. It deliberately
# does NOT verify `src` copies: a module can legitimately ship only its
# build.gradle.kts to the builder (e.g. :lint:detekt-rules, copied to satisfy
# settings evaluation but never compiled into the runtime jar), and deciding
# whether a module's `src` is needed at runtime requires full dependency-graph
# resolution. A stronger (heavier) guard is to actually build the builder stage in
# CI: `docker build --target builder .` — see the PR that introduced this script.
#
# USAGE
#   dev/scripts/check-dockerfile-module-copies.sh
# Exit codes: 0 = in sync; 1 = drift detected; 2 = could not parse settings (the
# gate-detection below is out of date — fix the script).
#
# Written for bash 3.2 (macOS default) — no mapfile / associative arrays.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SETTINGS="$ROOT/settings.gradle.kts"
DOCKERFILE="$ROOT/Dockerfile"

for f in "$SETTINGS" "$DOCKERFILE"; do
  if [ ! -f "$f" ]; then
    echo "error: required file not found: $f" >&2
    exit 2
  fi
done

# Emit one "GATED <:module>" or "NONGATED <:module>" line per include().
# State machine: while inside the `if (includeMobile.toBoolean()) { ... }` block
# (tracked by brace depth) includes are GATED; everything else is NONGATED.
classify() {
  awk '
    # Strip line comments first so commented-out / example include()s (and any
    # braces inside comments) never affect parsing.
    { sub(/\/\/.*/, "") }
    /if[ \t]*\([ \t]*includeMobile\.toBoolean\(\)[ \t]*\)[ \t]*\{/ { inGate = 1; brace = 1; next }
    inGate == 1 {
      t = $0; o = 0; while (sub(/\{/, "", t)) o++
      u = $0; c = 0; while (sub(/\}/, "", u)) c++
      brace += o - c
      if ($0 ~ /include\("[ \t]*:/) {
        m = $0; sub(/.*include\("[ \t]*/, "", m); sub(/".*/, "", m); print "GATED " m
      }
      if (brace <= 0) inGate = 0
      next
    }
    /include\("[ \t]*:/ {
      m = $0; sub(/.*include\("[ \t]*/, "", m); sub(/".*/, "", m); print "NONGATED " m
    }
  ' "$SETTINGS"
}

seen_mobile=0
gated_count=0
nongated_count=0
missing=""

while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  kind="${entry%% *}"
  mod="${entry#* }"
  if [ "$kind" = "GATED" ]; then
    gated_count=$((gated_count + 1))
    [ "$mod" = ":mobile:app" ] && seen_mobile=1
    continue
  fi
  nongated_count=$((nongated_count + 1))
  # :infra:supabase -> infra/supabase
  path="${mod#:}"
  path="$(printf '%s' "$path" | tr ':' '/')"
  if ! grep -qE "^COPY[[:space:]]+${path}/build\.gradle\.kts([[:space:]]|\$)" "$DOCKERFILE"; then
    missing="${missing}  - ${mod}  (expected: COPY ${path}/build.gradle.kts ... in Dockerfile)\n"
  fi
done < <(classify)

# Sanity: if we couldn't find :mobile:app inside the gate, our gate-detection is
# stale (someone reformatted settings.gradle.kts). Fail loud rather than silently
# treating every include as non-gated (which would false-flag the mobile modules).
if [ "$seen_mobile" != "1" ]; then
  echo "error: could not locate ':mobile:app' inside the includeMobile gate in" >&2
  echo "       settings.gradle.kts. The gate-detection in this script is likely" >&2
  echo "       out of date — update classify() to match the new gate syntax." >&2
  exit 2
fi

if [ -n "$missing" ]; then
  {
    echo "FAIL: settings.gradle.kts ↔ Dockerfile drift detected."
    echo
    echo "These non-mobile-gated modules are include()d in settings.gradle.kts but"
    echo "their build.gradle.kts is NOT COPY'd into the Dockerfile builder stage."
    echo "Gradle settings evaluation will fail in the Docker build"
    echo "(./gradlew :backend:ktor:installDist -PincludeMobile=false) and every"
    echo "staging/prod deploy will break — while PR CI stays green:"
    echo
    printf "%b" "$missing"
    echo
    echo "Fix ONE of:"
    echo "  (a) Backend depends on it: add to the Dockerfile builder stage both"
    echo "        COPY <path>/build.gradle.kts <path>/build.gradle.kts"
    echo "        COPY <path>/src           <path>/src"
    echo "  (b) Module is mobile-only: move its include() inside the"
    echo "      'if (includeMobile.toBoolean())' block in settings.gradle.kts."
  } >&2
  exit 1
fi

echo "ok: all ${nongated_count} non-mobile-gated module include()s have a matching" \
     "Dockerfile build.gradle.kts COPY (${gated_count} mobile-gated modules skipped)."
