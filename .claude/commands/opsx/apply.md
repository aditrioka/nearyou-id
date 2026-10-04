---
name: "OPSX: Apply"
description: Implement tasks from an OpenSpec change (Experimental)
category: Workflow
tags: [workflow, artifacts, experimental]
---

**Thin wrapper — do not add steps here.** The nearyou-id workflow for this command lives in the **`openspec-apply-change`** skill (`.claude/skills/openspec-apply-change/SKILL.md`), which carries the project gates: Preflight human-required re-check, docs/11 Definition of Done (incl. the verify-loop screenshot gate for UI changes), docs/12 cross-layer cohesion, docs/13 test matrix, pre-archive staging branch deploy + smoke, qodo `/review`, PR title/body refresh at every phase boundary. This file existed as a full copy of the upstream OpenSpec steps and drifted (last substantive edit 2026-06-09, before the #401/#402 hardening — audit 2026-10-03 § 10 / issue #539); the skill is the single source of truth.

Invoke the `openspec-apply-change` skill via the Skill tool, passing these arguments through unchanged: `$ARGUMENTS`

Do not follow any other copy of these steps, and do not fall back to the generic upstream OpenSpec instructions if the skill is unavailable — stop and report instead.
