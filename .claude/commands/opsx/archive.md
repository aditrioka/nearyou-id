---
name: "OPSX: Archive"
description: Archive a completed change in the experimental workflow
category: Workflow
tags: [workflow, archive, experimental]
---

**Thin wrapper — do not add steps here.** The nearyou-id workflow for this command lives in the **`openspec-archive-change`** skill (`.claude/skills/openspec-archive-change/SKILL.md`), which carries the project gates: Definition-of-Done + TBD-Purpose gate, delta-spec sync, move under `archive/`, archive commit pushed to the existing PR, PR body refresh. This file existed as a full copy of the upstream OpenSpec steps and drifted (last substantive edit 2026-06-09, before the #401/#402 hardening — audit 2026-10-03 § 10 / issue #539); the skill is the single source of truth.

Invoke the `openspec-archive-change` skill via the Skill tool, passing these arguments through unchanged: `$ARGUMENTS`

Do not follow any other copy of these steps, and do not fall back to the generic upstream OpenSpec instructions if the skill is unavailable — stop and report instead.
