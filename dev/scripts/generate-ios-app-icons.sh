#!/usr/bin/env bash
#
# Generate the 3 iOS Asset Catalog 1024×1024 variants for the modern
# universal-idiom AppIcon.appiconset (per shared-resources-moko-bootstrap
# design.md Decision 8 + the existing Contents.json from Mobile #1's scaffold).
#
# Does NOT regress to the legacy 17-PNG multi-size pattern (which would drop
# dark/tinted variant support).
#
# Sources:
#   $1 (default): logo_brand_dark.svg  (white glyph on #1E4FD6 — default + dark)
#   $2 (default): logo_brand_light.svg (blue glyph on white — tinted variant)
#
# Destination:
#   $3 (default): iosApp/iosApp/Assets.xcassets/AppIcon.appiconset
#
# Dependencies (one of):
#   - librsvg     → brew install librsvg     (preferred)
#   - poppler     → brew install poppler     (pdftocairo fallback)
#
# The script hard-fails if neither rasterizer is available + verifies the
# source SVG declares viewBox="0 0 108 108" (the rasterization assumption).

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

SRC_DEFAULT="${1:-$REPO_ROOT/shared/resources/src/commonMain/moko-resources/images/logo_brand_dark.svg}"
SRC_LIGHT="${2:-$REPO_ROOT/shared/resources/src/commonMain/moko-resources/images/logo_brand_light.svg}"
DEST="${3:-$REPO_ROOT/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset}"

# --- Detect rasterizer ---
CONVERTER=""
if command -v rsvg-convert &>/dev/null; then
    CONVERTER="rsvg-convert"
elif command -v pdftocairo &>/dev/null; then
    CONVERTER="pdftocairo"
else
    echo "ERROR: no rasterizer found." >&2
    echo "  Install one of: brew install librsvg   (preferred)" >&2
    echo "                  brew install poppler   (pdftocairo fallback)" >&2
    exit 1
fi

# --- Verify viewBox assumption ---
for src in "$SRC_DEFAULT" "$SRC_LIGHT"; do
    if [ ! -f "$src" ]; then
        echo "ERROR: source SVG not found: $src" >&2
        exit 1
    fi
    if ! grep -q 'viewBox="0 0 108 108"' "$src"; then
        echo "ERROR: $src does not declare viewBox='0 0 108 108' — rasterization math assumes 108-unit canvas." >&2
        exit 1
    fi
done

mkdir -p "$DEST"

# --- Render function ---
render() {
    local src="$1" out="$2"
    if [ "$CONVERTER" = "rsvg-convert" ]; then
        rsvg-convert -w 1024 -h 1024 "$src" -o "$DEST/$out"
    else
        # pdftocairo emits "$base-1.png" — rename to expected output.
        # Resolution math: pdftocairo's -r is in DPI; for 1024px output on
        # a 108-point viewBox, r = 1024 * 72 / 108 ≈ 683 DPI.
        local base="${out%.png}"
        pdftocairo -png -r $((1024 * 72 / 108)) "$src" "$DEST/$base"
        if [ -f "$DEST/${base}-1.png" ]; then
            mv "$DEST/${base}-1.png" "$DEST/$out"
        else
            echo "ERROR: pdftocairo did not produce expected output: $DEST/${base}-1.png" >&2
            exit 1
        fi
    fi
    # Hard-fail check (replaces the silent `|| true` from the original draft).
    if [ ! -f "$DEST/$out" ]; then
        echo "ERROR: $DEST/$out was not generated" >&2
        exit 1
    fi
}

# --- Generate the 3 variants ---
# 1. Default — white glyph on brand blue (#1E4FD6). High contrast for typical
#    home-screen wallpapers.
render "$SRC_DEFAULT" "app-icon-1024.png"

# 2. Dark — same as default for v1 (high contrast on dark home screens).
#    A future hand-tuned dark variant may differ; track as
#    `mobile-dark-icon-tuning` follow-up if visual review wants distinct.
render "$SRC_DEFAULT" "app-icon-1024-dark.png"

# 3. Tinted — uses the blue-on-white variant (higher monochrome-tint
#    compatibility) so iOS 18+ Tinted icons render legibly when the system
#    applies a single-color tint.
render "$SRC_LIGHT" "app-icon-1024-tinted.png"

echo "OK: generated 3 PNGs in $DEST (default, dark, tinted variants for the modern universal-idiom AppIcon)"
