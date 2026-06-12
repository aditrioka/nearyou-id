#!/usr/bin/env bash
# mockup-measure.sh — generate the per-frame "measurement annex" for a dev/mockups board.
#
# Emits machine-readable redlining for ONE frame of a mockup board: per-element
# bounding boxes (frame-relative), padding/gap/radius/typography, colors resolved
# back to the board's CSS custom-property token NAMES, and animation parameters
# (the part a static screenshot can't show). Output is JSON on stdout.
#
# Usage:
#   dev/scripts/mockup-measure.sh <board.html> --list
#   dev/scripts/mockup-measure.sh <board.html> <frame-no> [-o out.json]
#
# Examples:
#   dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html --list
#   dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html 7
#   dev/scripts/mockup-measure.sh dev/mockups/nearyou-admin-mockup.html 4b -o /tmp/f4b.json
#
# Output is generated ON DEMAND from the board HTML — never commit it (it would
# drift the moment the board changes). Units: mobile boards are 1px == 1dp intent
# (docs/11 §2.8); admin board is desktop CSS px under the frame-4b responsive
# contract (docs/11 §3.6).
#
# Requires Chrome/Chromium (override binary via MOCKUP_CHROME) + python3.
set -euo pipefail

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

[ $# -ge 2 ] || usage
BOARD=$1
TARGET=$2
OUT=""
if [ "${3:-}" = "-o" ]; then OUT=${4:?'-o requires a path'}; fi
[ -f "$BOARD" ] || { echo "board not found: $BOARD" >&2; exit 1; }
[ "$TARGET" = "--list" ] && TARGET="list"

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JS="$SCRIPT_DIR/mockup-measure.js"
[ -f "$JS" ] || { echo "missing companion script: $JS" >&2; exit 1; }

# Chrome binary: env override → macOS default → PATH (Linux/CI)
CHROME="${MOCKUP_CHROME:-}"
if [ -z "$CHROME" ]; then
  for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "$(command -v google-chrome || true)" \
           "$(command -v chromium || true)" \
           "$(command -v chromium-browser || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && CHROME=$c && break
  done
fi
[ -n "$CHROME" ] || { echo "Chrome/Chromium not found; set MOCKUP_CHROME" >&2; exit 1; }

TMPDIR_M=$(mktemp -d /tmp/mockup-measure.XXXXXX)
trap 'rm -rf "$TMPDIR_M"' EXIT
TMPHTML="$TMPDIR_M/$(basename "$BOARD")"
DUMP="$TMPDIR_M/dump.html"

# Inject the target + measurement script into a copy of the board.
python3 - "$BOARD" "$TMPHTML" "$TARGET" "$JS" <<'PY'
import pathlib, sys
board, out, target, jsfile = sys.argv[1:5]
html = pathlib.Path(board).read_text(encoding="utf-8")
js = pathlib.Path(jsfile).read_text(encoding="utf-8")
inject = f"<script>window.__MEASURE_TARGET={target!r};</script>\n<script>{js}</script>"
if "</body>" in html:
    html = html.replace("</body>", inject + "\n</body>", 1)
else:
    html += inject
pathlib.Path(out).write_text(html, encoding="utf-8")
PY

"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --window-size=1920,1200 --virtual-time-budget=10000 \
  --dump-dom "file://$TMPHTML" > "$DUMP" 2>/dev/null \
  || { echo "headless Chrome render failed" >&2; exit 1; }

# Extract + validate + pretty-print the JSON payload.
RESULT=$(python3 - "$DUMP" <<'PY'
import html as H, json, pathlib, re, sys
dump = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
m = re.search(r'<pre id="__measure_out">(.*?)</pre>', dump, re.S)
if not m:
    sys.exit("measure output not found in rendered DOM (JS error before emit?)")
obj = json.loads(H.unescape(m.group(1)))
if "error" in obj:
    sys.exit(f"measure script error: {obj['error']}\n{obj.get('stack') or ''}")
print(json.dumps(obj, indent=1, ensure_ascii=False))
PY
)

if [ -n "$OUT" ]; then
  printf '%s\n' "$RESULT" > "$OUT"
  echo "wrote $OUT" >&2
else
  printf '%s\n' "$RESULT"
fi
