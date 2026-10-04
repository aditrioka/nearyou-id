#!/usr/bin/env bash
# Guard: every /opsx:* slash command stays a THIN WRAPPER over its openspec-* skill.
#
# The command files under .claude/commands/opsx/ once carried a full copy of the
# upstream OpenSpec steps and drifted from the project workflow (no Preflight,
# no docs/11 DoD, no docs/12 cohesion — audit 2026-10-03 § 10, issue #539).
# The skills are the single source of truth; this check fails if a command file
# stops delegating or grows real steps again. Pure bash, no JDK. Runs in the CI
# lint lane; run locally before editing anything under .claude/commands/opsx/.
set -euo pipefail
cd "$(dirname "$0")/../.."

declare -A SKILL=(
  [apply]=openspec-apply-change
  [archive]=openspec-archive-change
  [propose]=openspec-propose
  [explore]=openspec-explore
)
fail=0
for cmd in "${!SKILL[@]}"; do
  f=".claude/commands/opsx/${cmd}.md"
  skill="${SKILL[$cmd]}"
  if [[ ! -f "$f" ]]; then echo "FAIL: $f missing"; fail=1; continue; fi
  if [[ ! -f ".claude/skills/${skill}/SKILL.md" ]]; then echo "FAIL: skill ${skill} missing for $f"; fail=1; continue; fi
  if ! grep -q "Invoke the \`${skill}\` skill" "$f"; then
    echo "FAIL: $f must delegate with: Invoke the \`${skill}\` skill"; fail=1
  fi
  # Body (after frontmatter) must stay short — a wrapper, not a workflow copy.
  body_lines=$(awk 'BEGIN{fm=0} /^---$/{fm++; next} fm>=2' "$f" | grep -c . || true)
  if (( body_lines > 12 )); then
    echo "FAIL: $f body has ${body_lines} non-empty lines (>12) — it is growing steps again; put them in .claude/skills/${skill}/SKILL.md"; fail=1
  fi
done
if (( fail )); then exit 1; fi
echo "ok: 4 /opsx:* commands delegate to their openspec-* skills"
