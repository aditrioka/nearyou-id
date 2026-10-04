---
name: "OPSX: Explore"
description: "Enter explore mode - think through ideas, investigate problems, clarify requirements"
category: Workflow
tags: [workflow, explore, experimental, thinking]
---

**Thin wrapper — do not add steps here.** The nearyou-id workflow for this command lives in the **`openspec-explore`** skill (`.claude/skills/openspec-explore/SKILL.md`), which carries the project gates: explore-mode rules: think/visualize freely, never write application code; hand off to `/opsx:propose` or `/next-change` when a change crystallises. This file existed as a full copy of the upstream OpenSpec steps and drifted (last substantive edit 2026-06-09, before the #401/#402 hardening — audit 2026-10-03 § 10 / issue #539); the skill is the single source of truth.

Invoke the `openspec-explore` skill via the Skill tool, passing these arguments through unchanged: `$ARGUMENTS`

Do not follow any other copy of these steps, and do not fall back to the generic upstream OpenSpec instructions if the skill is unavailable — stop and report instead.
