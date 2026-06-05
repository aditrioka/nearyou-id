#!/usr/bin/env bash
set -euo pipefail

# maestro-run.sh — run a Maestro flow with full artifact capture for AI + human validation.
#
# Captures into mobile/app/maestro/artifacts/<flow>-<timestamp>/:
#   • per-step + on-failure screenshots, commands JSON, AI report   (--test-output-dir)
#   • maestro.log debug output                                      (--debug-output)
#   • report.xml (JUnit)                                            (--format JUNIT --output)
#   • recording.mp4 screen recording (optional, --record)          (maestro record --local)
# A human reviews the screenshots/mp4; an AI agent reads maestro.log + report.xml to explain pass/fail.
#
# Usage:
#   dev/scripts/maestro-run.sh <flow.yaml|dir> --app-id <applicationId> [--device <id>] [--record]
#
# Flows declare `appId: ${APP_ID}` — pass the flavor's applicationId via --app-id:
#   --app-id id.nearyou.app.dev      (emulator + local backend on 10.0.2.2:8080)
#   --app-id id.nearyou.app.staging  (physical device + api-staging.nearyou.id)

FLOW="${1:?usage: maestro-run.sh <flow.yaml|dir> --app-id <id> [--device <id>] [--record]}"
shift || true

APP_ID="${APP_ID:-}"
RECORD=0
DEVICE_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --app-id) APP_ID="${2:?--app-id needs a value}"; shift 2 ;;
    --device) DEVICE_ARGS+=(--device "${2:?--device needs a value}"); shift 2 ;;
    --record) RECORD=1; shift ;;
    *) echo "maestro-run.sh: unknown arg '$1'" >&2; exit 2 ;;
  esac
done

: "${APP_ID:?--app-id is required (e.g. id.nearyou.app.staging) or export APP_ID}"
command -v maestro >/dev/null || { echo "maestro not on PATH — install: curl -fsSL https://get.maestro.mobile.dev | bash" >&2; exit 127; }

ROOT="$(git rev-parse --show-toplevel)"
TS="$(date +%Y%m%d-%H%M%S)"
NAME="$(basename "${FLOW%.*}")"
OUT="$ROOT/mobile/app/maestro/artifacts/${NAME}-${TS}"
mkdir -p "$OUT/debug"

echo "▶ flow:      $FLOW"
echo "▶ appId:     $APP_ID"
echo "▶ artifacts: $OUT"
echo ""

set +e
maestro ${DEVICE_ARGS[@]+"${DEVICE_ARGS[@]}"} test "$FLOW" \
  -e "APP_ID=$APP_ID" \
  --format JUNIT --output "$OUT/report.xml" \
  --test-output-dir "$OUT" \
  --debug-output "$OUT/debug"
STATUS=$?
set -e

if [ "$RECORD" -eq 1 ]; then
  echo "▶ rendering mp4 (maestro record --local)…"
  maestro ${DEVICE_ARGS[@]+"${DEVICE_ARGS[@]}"} record --local "$FLOW" "$OUT/recording.mp4" -e "APP_ID=$APP_ID" \
    || echo "  (record failed — non-fatal)"
fi

echo ""
echo "── artifacts for human review ($OUT) ──"
ls -1R "$OUT" | sed 's/^/  /'
echo "──"
if [ "$STATUS" -eq 0 ]; then
  echo "✅ flow passed"
else
  echo "❌ flow failed (status $STATUS) — eyeball the screenshots + $OUT/debug/maestro.log"
fi
exit "$STATUS"
