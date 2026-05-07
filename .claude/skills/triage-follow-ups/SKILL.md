---
name: triage-follow-ups
description: Triage `FOLLOW_UPS.md` end-to-end — read every open entry, run staleness checks, classify each as silently-resolved / superseded / migrate-to-canonical-doc / still-valid (OpenSpec or regular-PR shape) / in-progress, then act per disposition (delete, migrate, hand off to `/next-change`, or surface as regular-PR scope). Keeps `FOLLOW_UPS.md` from rotting; complements `/next-change` (which proposes new capabilities, not deferred-debt cleanup).
---

Triage `FOLLOW_UPS.md` end-to-end: read every open entry, verify each is still valid against current code/specs/docs, surface classifications to the user, then execute deletions / migrations / promotion hand-offs per the user's choices.

## Context

`FOLLOW_UPS.md` at the repo root is a transient working file for findings discovered mid-change that don't belong in the current change's scope. The intro blurb (top of the file) is canonical and prescribes:

- **Hard limit: 30 open entries** — when breached, force a triage sweep before adding more.
- **Delete entries when their action items merge** — `triaged` is NOT a terminal state. Triaged-but-not-deleted entries are how the file rots.
- **Migration paths**: launch-prerequisite tasks → `docs/08-Roadmap-Risk.md` Pre-Launch list; runbook tweaks → `docs/07-Operations.md` Deployment Runbook; other canonical docs as appropriate.
- **Delete the file when empty**; recreate (with the intro blurb preserved verbatim) the next time a finding arises.

This skill enforces that discipline. It is **complementary** to `/next-change`, not a replacement:

- `/next-change` answers: "what new capability should I propose next?" — sources are roadmap + version docs + open spec gaps.
- `/triage-follow-ups` answers: "what tracked debt is ready to close, and how?" — sources are `FOLLOW_UPS.md` entries + their referenced files.

If a triage cycle promotes a follow-up to a real OpenSpec change, this skill produces a vetted scope summary and recommends the user invoke `/next-change` next. It does NOT scaffold the proposal itself — `/next-change`'s reconciliation pass + multi-lens review loop are non-negotiable for OpenSpec changes.

## Steps

### Phase A — Read & classify

1. **Pre-flight check.** If `openspec/changes/` contains an unarchived directory (i.e., a change is mid-flight), surface to the user and ask whether to proceed. Triage during an active change risks spurious classifications — the active change might silently resolve a follow-up but isn't merged yet.

2. **Read `FOLLOW_UPS.md` in full.** Parse entries by `## ` headings. Extract per entry:
   - Name (kebab-case slug)
   - Status (`open` / `triaged` / `in-progress` / `resolved-not-merged`)
   - Discovered during
   - Action items (the unchecked `- [ ]` boxes are the live work)
   - File references (specs / code / docs at fault)

3. **Count current open entries.** If ≥25, flag urgency to user up-front (intro budget is 30; sweep should bring it well below). If <15, note it's healthy and triage can proceed at lower urgency.

4. **Run staleness checks per entry, in parallel where possible.** For each entry, check whether its action items are silently resolved:
   - **"File OpenSpec change `<name>`"** → list `openspec/changes/<name>/` and `openspec/changes/archive/<name>/`. If either exists → `superseded`.
   - **"Update `<spec-file>` § X"** → grep the spec file for the prescribed change. If present → `resolved-silently`.
   - **"Update `<doc>` § Y"** → grep the doc for the change.
   - **"Delete this entry after `<change>` merges"** → check `openspec/changes/archive/<change>/`. If archived → `resolved-silently`.
   - **Status `resolved-not-merged`** → check whether the resolving PR has merged via `gh pr view <pr> --json mergedAt`. If merged → `resolved-silently`; if not → keep as `still-valid`.
   - **Status `triaged`** → per intro guidance, this status is a smell. Investigate residual action items; either they're already addressed elsewhere (delete) or they belong in a canonical doc (migrate). Don't leave triaged entries untouched — that's the rot the intro warns about.
   - **Status `in-progress`** → leave alone; surface to user as "skipping (someone owns this)."

5. **Classify every entry into one of:**
   - `resolved-silently` — action items done in code/specs/docs already
   - `superseded` — covered by an in-progress or merged change
   - `migrate-to-doc` — residual work belongs in a canonical doc, not as a follow-up entry
   - `still-valid-openspec` — real outstanding work, capability+behavior shape (spec-driven)
   - `still-valid-regular-pr` — real outstanding work, but not spec-driven (docs amendment, infra tweak, lint rule)
   - `still-valid-defer` — real but blocked / waiting on external / not ready
   - `in-progress` — leave alone

6. **Surface a triage table to the user.** One row per entry: name, current status, classification, one-line rationale. For `resolved-silently` and `superseded`, show the evidence (file path or PR number). Group by classification for scannability.

### Phase B — Confirm dispositions with user

7. **Use `AskUserQuestion` to batch decisions.** Don't ask per-entry; group by disposition:
   - "Delete these N silently-resolved + superseded entries?" — typically yes; show evidence again at decision time.
   - "Migrate these M entries to <canonical-doc>?" — list each migration target.
   - "These P still-valid OpenSpec-shaped entries: promote one now via `/next-change` hand-off, bundle multiple into a single change, or defer all?"
   - "These Q still-valid regular-PR-shaped entries: bundle into one chore PR, address one-by-one, or defer?"

8. **For ambiguous classifications, surface separately.** If a staleness check is borderline (e.g., the spec was updated but a parallel doc change in the action items is still missing), present the entry alone with options: keep open / split into a smaller residual entry / delete.

### Phase C — Execute deletions and migrations

9. **For `resolved-silently` and `superseded` entries the user approved deletion:**
   - Edit `FOLLOW_UPS.md` to remove each `## <name>` block (heading through trailing `---`).
   - Stage `FOLLOW_UPS.md` only (do NOT `git add -A`).
   - Commit `docs: prune <N> silently-resolved follow-ups from FOLLOW_UPS.md` with body listing entry names + the resolution evidence per entry.

10. **For `migrate-to-doc` entries:**
    - Move the residual work into the canonical doc. Match the doc's existing format — a checklist line under `docs/08-Roadmap-Risk.md` § Pre-Launch, a runbook step under `docs/07-Operations.md`, etc. Don't paste the whole follow-up entry verbatim.
    - Remove the migrated entry from `FOLLOW_UPS.md`.
    - Commit `docs: migrate <name> follow-up to <canonical-doc>` per migration. Bundle if migrating multiple to the same target doc.

11. **After Phase C edits, check `FOLLOW_UPS.md` length.** If zero entries remain → delete the file per intro guidance + add a note in the commit body explaining future findings will recreate it (with the intro blurb).

### Phase D — Promote real-work candidates

12. **For each `still-valid-openspec` entry the user approved for promotion:**
    - Synthesize a scope summary in chat:
      - Proposed change name (kebab-case, derived from the follow-up name or the action items — no `-v<N>` suffix per `openspec/project.md` § Change Delivery Workflow)
      - One-paragraph "why" (paraphrased from the entry's Finding + Impact sections)
      - One-paragraph "what changes" (derived from action items)
      - Sources: `FOLLOW_UPS.md § <entry-name>` + any additional docs referenced
    - Recommend the user invoke `/next-change` next. That skill will independently rediscover + reconfirm the scope. If it disagrees with the promotion, that's useful tension — the user can override or re-prioritize.
    - **Do NOT invoke `openspec-propose` directly here.** `/next-change`'s Phase B reconciliation + Phase D multi-lens review loop are the value-add for OpenSpec changes; this skill must not bypass them.

13. **For `still-valid-regular-pr` entries the user wants bundled:**
    - Synthesize a chore PR scope: one paragraph + bulleted file-list of changes derived from action items.
    - Surface the scope to the user. The skill does NOT itself write the implementation — that's the user's call (separate explicit invocation).

### Phase E — Push, PR, wrap up

14. **Push the deletion / migration commits + open a PR.** Single PR for the entire triage sweep (typical: 1 deletion commit + 0–N migration commits):
    ```bash
    git push -u origin <branch-name>
    gh pr create --title "chore: triage FOLLOW_UPS.md (<YYYY-MM-DD>)" --body "$(cat <<'EOF'
    ## Summary
    Triage sweep of `FOLLOW_UPS.md`. Open count: <before> → <after>.

    ## Deletions
    - `<name>` — <one-line resolution evidence>
    ...

    ## Migrations
    - `<name>` → `docs/<file>` § <section>
    ...

    ## Promoted to /next-change hand-off
    - `<change-name>` (from `<follow-up-name>`)
    ...

    ## Surfaced as regular-PR scope
    - `<bundle-name>` covering: <list>

    ## Test plan
    - [ ] `FOLLOW_UPS.md` open count is now <after> (re-grep `^## ` headings).
    - [ ] No entry referenced by an open PR / active change was deleted.
    - [ ] Migrated entries appear in their target docs with consistent formatting.

    🤖 Generated with [Claude Code](https://claude.com/claude-code)
    EOF
    )"
    ```
    The PR body is the audit trail for why the file shrank.

15. **Report final state to user:**
    - Open count before / after
    - Promotions handed off (list change-name candidates for `/next-change`)
    - Regular-PR work surfaced (list bundles awaiting user action)
    - Any entries flagged `in-progress` or `still-valid-defer` (left alone)
    - If `FOLLOW_UPS.md` was deleted: explicit note + reminder it'll be recreated on next finding.

## Notes

- **This skill never silently rewrites entries.** Edits are deletion or migration only — not "summarize and shrink." If a remaining entry is too verbose, that's a separate cleanup.
- **`triaged` status is a smell.** Per the `FOLLOW_UPS.md` intro: triaged-but-not-deleted entries are how the file rots. Always investigate whether triaged entries should be deleted or migrated — never leave them as-is just because the status reads "triaged."
- **Don't expand scope from triage into implementation.** If a follow-up is `still-valid-regular-pr`, surface the scope and stop. Don't write the implementation in the same session — that's a separate explicit user action.
- **Don't merge follow-ups into OpenSpec scope without `/next-change`.** Phase D explicitly hands off to `/next-change`; do not invoke `openspec-propose` directly. The reconciliation pass and multi-lens review loop are non-optional for any OpenSpec change.
- **Hard-limit enforcement.** If pre-triage open count is ≥30, the file is over budget. Flag this in Phase A, prioritize deletions over promotions in Phase B (deletions free budget; promotions don't), and verify post-triage count is well below 30 before stopping.
- **Branch naming.** `chore/triage-follow-ups-<YYYY-MM-DD>` follows the `<area>/<slug>` convention for non-OpenSpec changes (per `openspec/project.md` § Change Delivery Workflow). Don't use the `change-name`-as-branch convention — that's reserved for OpenSpec changes.
- **Don't `--no-verify` or skip hooks.** Pre-commit hooks should pass on `FOLLOW_UPS.md` edits; if they don't, fix the underlying issue.
- **Engineering judgment over context budget.** Per `CLAUDE.md` § Engineering judgment over context budget: do NOT silently compress the triage list to fit a fading window. If context is tight, surface it explicitly and offer to split into a follow-up session — do not skip entries.
- **Public-repo posture applies.** `FOLLOW_UPS.md` is checked in. Entry deletions / migrations are visible in commit history; ensure no real secrets, customer PII, or speculative commercial strategy land in either the deleted-content evidence or the migration target docs.
