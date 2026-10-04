---
name: "OPSX: Propose"
description: Propose a new change - create it and generate all artifacts in one step
category: Workflow
tags: [workflow, artifacts, experimental]
---

**Thin wrapper — do not add steps here.** The nearyou-id workflow for this command lives in the **`openspec-propose`** skill (`.claude/skills/openspec-propose/SKILL.md`), which carries the project gates: docs/11 Pattern-Registry + DoD read at proposal time, canonical-docs reconciliation, `openspec validate --strict`; for picking WHAT to propose use `/next-change`, which also runs `openspec-preflight` at Phase B.5. This file existed as a full copy of the upstream OpenSpec steps and drifted (last substantive edit 2026-06-09, before the #401/#402 hardening — audit 2026-10-03 § 10 / issue #539); the skill is the single source of truth.

Invoke the `openspec-propose` skill via the Skill tool, passing these arguments through unchanged: `$ARGUMENTS`

Do not follow any other copy of these steps, and do not fall back to the generic upstream OpenSpec instructions if the skill is unavailable — stop and report instead.
