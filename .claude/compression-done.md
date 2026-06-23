# OpenSpec skill compression — handoff

Density pass on the pre-#401 `.claude/skills/**/SKILL.md` files. Filler/hedging/redundant-example cuts only — no behavior, trigger, branch, step/phase label, command, or cross-reference dropped. Every PR #401 wiring line was preserved **verbatim** (byte-checked); off-limits files (`openspec-preflight/SKILL.md`, `docs/12`, `docs/13`) untouched. Each shell-running skill gained a `## Safety` section; each `description` gained a "when NOT to use" boundary clause.

## Final file list + line counts

| File | Orig | Final | Δ |
|---|---|---|---|
| `.claude/skills/next-change/SKILL.md` | 260 | 210 | −50 (−19%) |
| `.claude/skills/openspec-apply-change/SKILL.md` | 323 | 165 | −158 (−49%) |
| `.claude/skills/openspec-archive-change/SKILL.md` | 182 | 82 | −100 (−55%) |
| `.claude/skills/openspec-explore/SKILL.md` | 290 | 63 | −227 (−78%) |
| `.claude/skills/openspec-propose/SKILL.md` | 142 | 55 | −87 (−61%) |
| `.claude/skills/verify-loop/SKILL.md` | 176 | 180 | +4 (+2%) |
| `.claude/skills/triage-follow-ups/SKILL.md` | 205 | 178 | −27 (−13%) |
| `.claude/skills/audit-burndown/SKILL.md` | 55 | 59 | +4 (+7%) |
| `.claude/skills/mobile-ui-foundation/SKILL.md` | 89 | 89 | 0 |
| **Total** | **1722** | **881** | **−841 (−49%)** |

(Line counts are post-review; `next-change` and `triage-follow-ups` each include one restored `adi-at-buku` disambiguator from the review pass.)

## Notes for the MEMORY.md audit cross-check

- **Three files did not shrink** (`verify-loop`, `audit-burndown`, `mobile-ui-foundation`). They were already near-irreducible density — a Known-blockers list, a backlog menu table, and a UI fundamentals checklist respectively — and each *grew* slightly only because the required `## Safety` section was added. Prose was still tightened; line count understates the density gain. These are the "genuinely needs to stay long" cases.
- **Highest cuts** (`openspec-explore` −78%, `openspec-propose` −61%, `openspec-archive-change` −55%, `openspec-apply-change` −49%) were the most over-explained upstream/generic skills — big illustrative ASCII diagrams, restated section intros, multi-paragraph rationale, redundant gate tables.
- **Step/phase labels preserved** (memory and sibling skills reference them): `next-change` A.0–E.1 incl. B.5; `openspec-apply-change` steps 1–9 incl. 7.5 / 8.1–8.5 (memory `feedback_qodo_review_trigger` cites "step 8"); `openspec-archive-change` step 5 (memory `feedback_opsx_archive_tbd_purpose_gate_can_silently_fail`); `triage-follow-ups` A.0 / C.9 / E.15 / E.16; `verify-loop` §A–§D.
- **One stale cross-reference corrected** (not a loss): `mobile-ui-foundation` had `FOLLOW_UPS.md mobile-localization-language-switching`; `FOLLOW_UPS.md` was retired 2026-06-09, so it now reads `follow-up mobile-localization-language-switching`.
- Verified by 3 independent sub-agent review passes (split across the 9 files) — all returned clean.

This file is a transient handoff artifact for the follow-on MEMORY.md audit; safe to delete once that audit completes.
