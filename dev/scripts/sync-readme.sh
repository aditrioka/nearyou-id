#!/usr/bin/env bash
# Auto-syncs the "What's in this repo" block of `README.md` from
# `settings.gradle.kts` + `dev/module-descriptions.txt`.
#
# Why mechanical: README is reader-facing and lives at the top of the tree.
# Module list drifts every time a new `:infra:*` / `:core:*` / `:shared:*` module
# is added — that's the highest-frequency drift point. Soft "remember to update"
# rules accumulate as documentation debt; sentinel-marker autogen keeps the
# block honest with one-line cost per module add.
#
# Usage:
#   dev/scripts/sync-readme.sh --check    # exit 1 if README block is stale (CI)
#   dev/scripts/sync-readme.sh --write    # regenerate the block in-place (local)
#
# Sentinel markers in README.md (HTML comments — invisible on github.com):
#   <!-- AUTOGEN:modules:start -->
#   ...generated content...
#   <!-- AUTOGEN:modules:end -->
#
# Module ordering in the block: grouped by layer for readability
# (mobile → backend → core → shared → infra → lint → other), alphabetical
# within group. NOT the raw `settings.gradle.kts` order.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
settings="$repo_root/settings.gradle.kts"
descriptions="$repo_root/dev/module-descriptions.txt"
readme="$repo_root/README.md"

START_MARKER='<!-- AUTOGEN:modules:start -->'
END_MARKER='<!-- AUTOGEN:modules:end -->'

mode="${1:-}"
case "$mode" in
  --check|--write) ;;
  *) echo "usage: $0 --check|--write" >&2; exit 2 ;;
esac

# 1. Extract module paths from settings.gradle.kts. The `|| [[ -n "$line" ]]`
#    catches the last line when the file lacks a trailing newline (common).
modules=()
while IFS= read -r line || [[ -n "$line" ]]; do
  m=$(printf '%s\n' "$line" | sed -nE 's/.*include\("(:[^"]+)"\).*/\1/p')
  [[ -n "$m" ]] && modules+=("$m")
done < "$settings"

if [[ ${#modules[@]} -eq 0 ]]; then
  echo "ERROR: no modules found in $settings" >&2
  exit 1
fi

# 2. Load description map (parallel arrays — portable across bash versions).
desc_keys=()
desc_vals=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  if [[ "$line" != *' | '* ]]; then
    echo "ERROR: malformed line in $descriptions (missing ' | ' separator):" >&2
    echo "  $line" >&2
    exit 1
  fi
  key="${line%% | *}"
  val="${line#* | }"
  desc_keys+=("$key")
  desc_vals+=("$val")
done < "$descriptions"

lookup_desc() {
  local target="$1"
  local i
  for i in "${!desc_keys[@]}"; do
    if [[ "${desc_keys[$i]}" == "$target" ]]; then
      printf '%s' "${desc_vals[$i]}"
      return 0
    fi
  done
  return 1
}

# 3. Logical group order. Lower number = listed earlier.
group_for() {
  case "$1" in
    :mobile:*)  echo 1 ;;
    :backend:*) echo 2 ;;
    :core:*)    echo 3 ;;
    :shared:*)  echo 4 ;;
    :infra:*)   echo 5 ;;
    :lint:*)    echo 6 ;;
    *)          echo 99 ;;
  esac
}

# 4. Sort + check every module has a description before generating any output.
sorted_modules=()
missing=()
while IFS= read -r m; do
  sorted_modules+=("$m")
  if ! lookup_desc "$m" >/dev/null; then
    missing+=("$m")
  fi
done < <(
  for m in "${modules[@]}"; do
    printf '%s|%s\n' "$(group_for "$m")" "$m"
  done | sort | cut -d'|' -f2-
)

if [[ ${#missing[@]} -gt 0 ]]; then
  echo "ERROR: missing descriptions in $descriptions for:" >&2
  for m in "${missing[@]}"; do echo "  $m" >&2; done
  echo "Add a line per module:    <module-path> | <one-line description>" >&2
  exit 1
fi

# 5. Generate the block content.
generated_inner=""
for m in "${sorted_modules[@]}"; do
  desc=$(lookup_desc "$m")
  generated_inner+="- \`$m\` — $desc"$'\n'
done
generated_block="${START_MARKER}"$'\n'"${generated_inner}${END_MARKER}"

# 6. Extract current block from README between sentinels (inclusive).
if ! grep -qF "$START_MARKER" "$readme" || ! grep -qF "$END_MARKER" "$readme"; then
  cat <<EOF >&2
ERROR: sentinel markers not found in $readme.

Add these lines where the module list should go:
  $START_MARKER
  ...existing module list (will be replaced on first --write)...
  $END_MARKER
EOF
  exit 1
fi

current_block=$(awk -v start="$START_MARKER" -v end="$END_MARKER" '
  $0 == start { in_block = 1 }
  in_block { print }
  $0 == end { in_block = 0 }
' "$readme")

# 7. --check or --write.
if [[ "$mode" == "--check" ]]; then
  if [[ "$current_block" == "$generated_block" ]]; then
    echo "README modules block is in sync."
    exit 0
  fi
  echo "ERROR: README modules block is out of sync with settings.gradle.kts." >&2
  echo "Run dev/scripts/sync-readme.sh --write to regenerate." >&2
  echo "--- diff (current → generated) ---" >&2
  diff <(printf '%s\n' "$current_block") <(printf '%s\n' "$generated_block") >&2 || true
  exit 1
fi

# --write: replace the block in-place. The generated block contains newlines, so
# pass it via ENVIRON instead of `-v` (awk's `-v` rejects newline-containing values).
tmp=$(mktemp)
export GENERATED_BLOCK="$generated_block"
awk -v start="$START_MARKER" -v end="$END_MARKER" '
  $0 == start { print ENVIRON["GENERATED_BLOCK"]; in_block = 1; next }
  $0 == end   { in_block = 0; next }
  !in_block   { print }
' "$readme" > "$tmp"
mv "$tmp" "$readme"
unset GENERATED_BLOCK
echo "README modules block regenerated."
