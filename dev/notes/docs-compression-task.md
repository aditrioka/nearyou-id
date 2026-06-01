# Docs Token-Compression Task (lossless)

**Self-sufficient task spec.** A fresh agent with zero prior chat context can execute this end-to-end. Everything needed — scope, token data, the lossless rules, the cross-reference trap, and the per-file procedure — is in this file.

> **Invocation prompt** (what the operator will paste):
> *"Read and execute `dev/notes/docs-compression-task.md`. Make the project's Markdown docs more token-efficient via lossless compression, biggest file first. Read each target file in full (not skim) before editing. Preserve every fact and every cross-reference — lossless. Do `docs/08-Roadmap-Risk.md` first and stop for review before continuing."*

---

## 1. Objective

Reduce the token cost of the project's long-form Markdown documentation **without losing any information**. Process files **biggest-first**. The single biggest doc, [`docs/08-Roadmap-Risk.md`](../../docs/08-Roadmap-Risk.md), is the first target and the reference case for the whole effort.

This is a **docs-only change** → regular PR, **not** OpenSpec (per `CLAUDE.md` § "When NOT to use OpenSpec").

## 2. What "lossless" means here (hard constraint)

"Lossless" = **semantic-lossless**. You MAY:
- Rewrite verbose prose into tighter prose.
- De-duplicate content that is repeated **within the same file**.
- Normalize tables, collapse redundant qualifiers, drop filler words.
- Merge two near-identical lists into one.

You MUST NOT:
- Drop any fact, decision, number, constraint, rule, scenario, edge case, rationale, trigger, or citation.
- Break any cross-reference (see § 4 — this is the easy way to silently lose context).
- Remove a section/heading/item that another file references (renaming breaks inbound `§ section` / `item #N` refs).
- **Move content out to another file and replace it with a pointer** ("cross-doc dedup") UNLESS the operator approves that specific move. The user's words were *"not missing any context from it"* — the default is **within-doc** compression that keeps each file self-contained. Cross-doc dedup is a separate, opt-in lever (§ 7).

> **This is NOT a license to cut scope to save tokens.** Per `CLAUDE.md` § "Engineering judgment over context budget": if a compression would drop information, **don't do it** — completeness beats efficiency. Efficiency is the secondary goal; losslessness is the hard gate.

**Definition of done, per file:** the compressed file conveys 100% of the original's information to a reader, every internal anchor still resolves, every inbound reference still resolves, and the token count is lower. If you can't hit lower-tokens without dropping info, report that and move on — a 0% file is a valid outcome.

## 3. Scope + token inventory

Targets = `.md` files **outside `openspec/`** (the openspec specs + change dossiers are out of scope — do not touch them, except to fix an inbound reference per § 4). 31 files total. **Do NOT touch `.github/workflows/**` (AI edits are hook-blocked).**

**Token method:** Claude tokens can't be counted offline, so measure characters (`wc -m <file>`) and divide by **2.45** (calibrated from two files whose exact token counts are known from the Read tool: `docs/08` = 86,330 chars / 34,980 tok = 2.468; `FOLLOW_UPS` = 82,587 / 33,789 = 2.444 — agree to <1%). `✓` rows below are exact-measured; the rest are `chars ÷ 2.45` estimates (±~10%; pure-prose files run a touch lower). Re-measure before/after each file and put the delta in the PR body.

> Tip: a file over ~25k tokens, when opened with the Read tool, prints its exact token count in the truncation note (`(N tokens, cap 25000)`). Use that for exact before/after on the two biggest files.

### Primary scope — root + `docs/` (14 files, ≈206,000 tok), process top-down

| # | File | Lines | Chars | Est. tokens |
|---|---|---:|---:|---:|
| 1 | [`docs/08-Roadmap-Risk.md`](../../docs/08-Roadmap-Risk.md) | 686 | 86,330 | **34,980 ✓** |
| 2 | [`FOLLOW_UPS.md`](../../FOLLOW_UPS.md) | 731 | 82,587 | **33,789 ✓** |
| 3 | [`docs/05-Implementation.md`](../../docs/05-Implementation.md) | 1,301 | 71,177 | ~29,100 |
| 4 | [`docs/10-Setup-Checklist.md`](../../docs/10-Setup-Checklist.md) | 493 | 51,583 | ~21,100 |
| 5 | [`docs/04-Architecture.md`](../../docs/04-Architecture.md) | 684 | 44,138 | ~18,000 |
| 6 | [`docs/06-Security-Privacy.md`](../../docs/06-Security-Privacy.md) | 481 | 32,817 | ~13,400 |
| 7 | [`docs/02-Product.md`](../../docs/02-Product.md) | 461 | 27,747 | ~11,300 |
| 8 | [`docs/01-Business.md`](../../docs/01-Business.md) | 504 | 26,265 | ~10,700 |
| 9 | [`docs/07-Operations.md`](../../docs/07-Operations.md) | 187 | 17,730 | ~7,200 |
| 10 | [`CLAUDE.md`](../../CLAUDE.md) | 126 | 17,386 | ~7,100 |
| 11 | [`docs/03-UX-Design.md`](../../docs/03-UX-Design.md) | 302 | 16,740 | ~6,800 |
| 12 | [`docs/09-Versions.md`](../../docs/09-Versions.md) | 42 | 14,099 | ~5,800 |
| 13 | [`docs/00-README.md`](../../docs/00-README.md) | 78 | 8,975 | ~3,700 |
| 14 | [`README.md`](../../README.md) | 113 | 7,894 | ~3,200 |

> Note: line count ≠ token count. `docs/09-Versions.md` is only 42 lines but ~5,800 tokens (very long lines / dense version-pin table). Always measure chars, not lines.

### Secondary scope — `dev/` + `.claude/` + module (17 files, ≈71,000 tok), optional / lower-ROI

| File | Chars | Est. tokens | Note |
|---|---:|---:|---|
| `.claude/skills/next-change/SKILL.md` | 21,342 | ~8,700 | ⚠ editing skill docs changes agent behavior — treat carefully |
| `.claude/skills/openspec-apply-change/SKILL.md` | 19,636 | ~8,000 | ⚠ behavior-affecting |
| `dev/notes/mobile-3-smoke-2026-05-29.md` | 16,610 | ~6,800 | transient smoke log — candidate for deletion, not compression (ask) |
| `.claude/commands/opsx/apply.md` | 16,262 | ~6,600 | ⚠ behavior-affecting |
| `.claude/skills/triage-follow-ups/SKILL.md` | 12,993 | ~5,300 | ⚠ behavior-affecting |
| `dev/scripts/import-admin-regions/README.md` | 11,336 | ~4,600 | |
| `.claude/commands/opsx/archive.md` | 11,409 | ~4,700 | ⚠ behavior-affecting |
| `.claude/skills/openspec-archive-change/SKILL.md` | 11,042 | ~4,500 | ⚠ behavior-affecting |
| `.claude/skills/openspec-explore/SKILL.md` | 10,738 | ~4,400 | ⚠ behavior-affecting |
| `.claude/skills/openspec-propose/SKILL.md` | 8,118 | ~3,300 | ⚠ behavior-affecting |
| `dev/docs/google-cloud-oauth-clients.md` | 7,506 | ~3,100 | |
| `.claude/commands/opsx/explore.md` | 6,760 | ~2,800 | ⚠ behavior-affecting |
| `dev/docs/ios-build.md` | 5,854 | ~2,400 | |
| `dev/README.md` | 5,371 | ~2,200 | |
| `.claude/commands/opsx/propose.md` | 4,418 | ~1,800 | ⚠ behavior-affecting |
| `dev/scripts/admin-bootstrap/README.md` | 2,757 | ~1,100 | |
| `backend/ktor/src/main/resources/username/README.md` | 953 | ~390 | too small to bother |

**Do the primary scope first. Do not start secondary scope without operator approval** — `.claude/**` docs steer how future agents behave, and `dev/notes/mobile-3-smoke-*` is a dated working log better deleted than compressed.

## 4. CRITICAL — reference integrity (the lossless trap)

Many files cite docs **by line number** (`<file>.md:NNN`). **Compression shifts line numbers and silently breaks every such inbound reference.** A broken citation = lost context. This is the #1 way to violate "lossless," and it is invisible unless you check for it.

The project also uses **stable references** — `§ Section Name`, `item #N`, `Decision N` — which **survive** compression as long as you keep the heading/item-number intact. **The fix is to prefer stable refs over line refs.**

### Recommended two-phase approach per file

**Phase 0 (prep, do first):** find every inbound line-number ref to the target file and convert it to a stable `§ section` / `item #N` ref. This is itself a lossless improvement (line refs are inherently fragile). Commands:

```bash
# All inbound line-number refs INTO a target (these will break):
grep -rno '<BASENAME>\.md:[0-9]\+' . --include="*.md" | grep -v '/build/'
#   e.g. <BASENAME> = 08-Roadmap-Risk

# All inbound refs of any kind (to verify nothing else points in):
grep -rn '<BASENAME>\.md' . --include="*.md" | grep -v '/build/' | grep -v '^./<relative path to the file itself>'
```

**Phase 1 (compress):** once inbound refs are stable (or updated), compress the body freely.

**Phase 2 (verify):** re-run the Phase-0 grep. Any remaining `<file>.md:NNN` ref must point at the correct new line (open it and confirm), or have been converted. Confirm internal anchors still resolve (every `Decision N`, `§ X`, `item #N` the file references itself, and every inbound `§`/`#N`, still exists).

### Inbound reference map for target #1 (`docs/08-Roadmap-Risk.md`) — already gathered

**LIVE line-number refs (MUST fix — citing files are canonical):**

| Citing file:line | Points at `08:NNN` | What's at that line (anchor to convert to) |
|---|---|---|
| `docs/08-Roadmap-Risk.md:686` | `08:117` | internal self-ref: Decision 33 → Phase 1 §21 `csam_detection_archive` schema bullet |
| `docs/10-Setup-Checklist.md:262` | `08:339` | Pre-Launch § Security review checklist → "Analytics consent suppression tested" |
| `openspec/specs/fcm-push-dispatch/spec.md:411` | `08:51` | Pre-Phase 1 #34 (staging secret slots) |
| `openspec/specs/migration-pipeline/spec.md:349` | `08:486` | Risk Register → "Free user churn… like cap" row |
| `openspec/specs/in-app-notifications/spec.md:22` | `08:486` | same Risk Register row |
| `openspec/specs/admin-schema/spec.md:244` & `:274` | `08:38` | Pre-Phase 1 #28 (scoped `admin_app` role) |

**ARCHIVE line-number refs (lower priority — frozen historical dossiers under `openspec/changes/archive/**`):** ~50 more refs into `08:{84,87,88,90,102,128,133,150,151,152,165,167,169,178,203,242,255,305,317,349,368,390,539,586,...}`. These are snapshots; breaking them is low-harm. **Preferred:** convert opportunistically. **Acceptable:** leave them and note the drift in the PR body. **Do NOT** silently renumber without recording the decision. Regenerate the full list anytime with the Phase-0 grep above (`<BASENAME>` = `08-Roadmap-Risk`).

Stable inbound refs to `docs/08` (these are FINE, just don't delete/rename the targets): `§ Pre-Phase 1 #34`, `§ Phase 3.5`, `§ Pre-Launch`, `§ Development Tools` (CI lint rules), `§ Coding Conventions Spatial rule`, `§ Open Decisions` entries #4/#13/#28, `Phase 1 item 15`, `Phase 2 item 3`. Keep every heading and every numbered item's number intact.

## 5. Where the tokens are in `docs/08` (concrete compression targets)

Read the whole file first, but these are the high-value, low-risk wins found during analysis:

1. **Duplicated CI-lint-rules list.** The ~16 lint rules appear **twice**: Phase 1 §31 (lines ~129–144) and Development Tools § CI lint rules (lines ~388–403), near-verbatim. Collapse to **one** canonical copy + an internal pointer from the other. ⚠ Both copies have inbound line-refs (`08:133` → Phase 1 list; `08:390` → Dev Tools list) — do § 4 Phase 0 first. Keep the `§ Development Tools` copy as canonical (it has more inbound stable refs). ~1,000–1,300 tok.
2. **Resolved Open Decisions with long rationale** (Decisions 4, 12, 13, 32, 33 — each a dense paragraph whose full rationale already lives in the cited archived `design.md`). Compress each to: decision + one-line outcome + the existing pointer to the archive design.md. Keep the trigger-to-revisit lines verbatim (load-bearing). ~1,500–2,000 tok.
3. **Verbose "shipped in X" status parentheticals** in phase items (Phase 1 §15/§16/§24; Phase 2 §2/§3/§9/§16). Tighten prose but **keep** every change-name, V-number, endpoint, flag name, and the canonical-authority citations. ~1,000–1,500 tok.
4. **Risk Register prose** (~95 rows across High/Medium/Acceptable). Tighten mitigation/rationale cells; each row is unique info — do not merge rows. ~1,500–2,500 tok.
5. **Pre-Phase 1 (43 items) + Pre-Launch security checklist (~60 items).** Each item is unique; only light per-item tightening (drop filler, consistent terse voice). Low yield, high care.

Realistic within-doc lossless yield for `docs/08`: **~5,000–7,000 tok (~15–20%)**. Adding operator-approved cross-doc dedup (e.g., Phase 1 §21 schema block → pointer to `docs/05`, the canonical schema home) could add ~2,500–3,000 more, but that reduces file self-containment → § 7.

## 6. Per-file procedure (repeat for each target, top of list down)

1. **Measure:** `wc -m <file>` → record before-tokens (chars ÷ 2.45).
2. **Read the ENTIRE file** (paginate if it exceeds the Read cap — do not skim). You cannot compress losslessly what you haven't fully read.
3. **Map inbound refs** (§ 4 Phase-0 grep). Convert live line-refs → stable `§`/`#N` refs in their citing files.
4. **Identify redundancy + verbosity** (for `docs/08`, see § 5; for others, look for repeated lists, restated facts, over-qualified prose, resolved-decision rationale duplicated in archives).
5. **Compress** within-doc only (cross-doc = opt-in, § 7). Preserve every fact/number/citation/anchor.
6. **Verify losslessness:** diff old→new mentally section-by-section; confirm no fact dropped. Re-run the Phase-0 grep; confirm internal anchors + inbound refs still resolve.
7. **Re-measure** and record after-tokens + % saved.
8. **One PR per file** (or small batches of small files), branch `docs/compress-<short-slug>` (e.g. `docs/compress-roadmap-risk`). PR body must include a before/after token table and an explicit "losslessness statement" listing what was deduped/tightened and confirming zero information dropped + reference-integrity grep clean.
9. **After file #1 (`docs/08`), STOP and request review** before continuing down the list — it's the reference case; get the lossless bar confirmed once, then proceed.

## 7. Cross-doc dedup (opt-in lever, ask first)

Big extra savings exist by removing content duplicated **across** docs and replacing with a pointer to the canonical home — e.g. `docs/08` Phase 1 §21 schema detail (canonical: `docs/05`), and the lint-rules list (canonical: `openspec/project.md` § Coding Conventions, also mirrored in `CLAUDE.md`). This is **higher-leverage but changes file self-containment** and may contradict *"not missing any context from it."* **Do not do this unprompted.** Surface it as a numbered proposal per file and let the operator decide.

## 8. Constraints checklist (project rules the agent must honor)

- Docs-only → **regular PR**, branch `docs/<slug>`, conventional commit `docs: …`. Not OpenSpec.
- **No direct push to `main`** (hook-blocked). PR + squash-merge. `--force-with-lease` OK on the topic branch.
- **Never** edit `.github/workflows/**` (AI edits hook-blocked) — not in scope anyway.
- **Public repo posture** (`CLAUDE.md`): source-available; never inline real secret values/PII. Slot names + project IDs are fine (they already appear in these docs).
- **README autogen:** only relevant if the module list changes — it won't here, so no `dev/scripts/sync-readme.sh` run needed. (If you ever touch the README module block, that's a separate mechanical step.)
- **Pre-push:** docs-only PRs skip the heavy lane (CI `paths-ignore` covers `**/*.md`), so the `./gradlew` suite is not required for these changes. Still run `openspec validate` is **not** applicable (no openspec edits).
- Verify with `git diff` that only intended files changed.

## 9. Suggested order + rough total opportunity

Process primary scope #1→#14 (§ 3). Conservative within-doc lossless yield ≈ **15–20% of ~206k = ~30,000–40,000 tokens** across the 14 root+docs files, before any opt-in cross-doc dedup. Biggest single wins: `docs/08`, `FOLLOW_UPS.md`, `docs/05`, `docs/10`. The two `00-README`/`README` files and `docs/09` are small — do them last or skip if yield is negligible.

`FOLLOW_UPS.md` (#2) note: it's a transient ledger already over its own 30-entry limit (35 open). Best compression there may be **structural** (the multi-paragraph audit-history blurb in the intro, lines ~10, can be collapsed to the latest sweep + a one-line history pointer) and **migrating dormant entries to GitHub Issues** — but that's a content decision; ask before deleting entries (deletion ≠ compression).
