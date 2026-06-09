---
name: triage-follow-ups
description: Triage the open `follow-up` GitHub issues end-to-end — list every open issue, run staleness checks, classify each as silently-resolved / superseded / migrate-to-canonical-doc / still-valid (OpenSpec or regular-PR shape) / in-progress, then act per disposition (close, migrate, hand off to `/next-change`, or surface as regular-PR scope). Keeps the `follow-up` issue backlog accurate; complements `/next-change` (which proposes new capabilities, not deferred-debt cleanup).
---

Triage the open `follow-up` GitHub issues end-to-end: list every open issue, verify each is still valid against current code/specs/docs, surface classifications to the user, then execute closes / doc-migrations / promotion hand-offs per the user's choices.

## Context

Deferred-work tracking lives in **GitHub Issues labeled `follow-up`** (filter: `gh issue list --label follow-up --state open`). The legacy root `FOLLOW_UPS.md` file was **retired 2026-06-09** ([PR #206](https://github.com/aditrioka/nearyou-id/pull/206)) — its 38-entry backlog was dispositioned: 33 migrated to issues #173–#205, 2 to `docs/08-Roadmap-Risk.md` § Open Decisions, 3 closed as accept-the-gap. New findings are filed as `gh issue create --label follow-up` (+ an area label: `mobile` / `backend` / `observability` / `admin` / `deferred`), never appended to a file. If you find a stray reference to `FOLLOW_UPS.md` in code/specs/docs, it is a stale pre-retirement artifact — reconcile it to the owning issue (or note it; archived changes under `openspec/changes/archive/**` are immutable history and stay as-is).

The discipline this skill enforces: **keep the open `follow-up` backlog accurate** — close issues whose work silently shipped, migrate issues whose residual work belongs in a canonical doc, and promote ready OpenSpec-shaped work. Unlike the old file, GitHub Issues don't "rot" by accumulating into an unreadable blob — but they DO rot by staying *open* after the work is silently resolved by intervening changes. That stale-open drift is the primary thing this skill catches.

It is **complementary** to `/next-change`, not a replacement:

- `/next-change` answers: "what new capability should I propose next?" — sources are roadmap + version docs + open spec gaps.
- `/triage-follow-ups` answers: "what tracked debt is ready to close, and how?" — sources are the open `follow-up` issues + their referenced files.

If a triage cycle promotes a follow-up to a real OpenSpec change, this skill produces a vetted scope summary and recommends the user invoke `/next-change` next. It does NOT scaffold the proposal itself — `/next-change`'s reconciliation pass + multi-lens review loop are non-negotiable for OpenSpec changes.

## Steps

### Phase A — Read & classify

1. **Pre-flight checks.**
   - **Active change:** if `openspec/changes/` contains an unarchived directory (a change is mid-flight), surface to the user and ask whether to proceed. Triage during an active change risks spurious classifications — the active change might silently resolve a follow-up but isn't merged yet.
   - **Concurrent triage:** `gh pr list --state open` — if a triage PR (doc-migrations) is already open, reconcile against it / wait, so two sweeps don't collide. Issue closes are direct (no PR), but doc-migration edits race like any file edit.
   - **gh account:** confirm the active `gh` account has write access to this repo (`gh auth status`; nearyou-id needs `aditrioka` — `gh auth switch --user aditrioka` if wrong). Closing/labeling issues with the wrong account 403s.

2. **List the open `follow-up` issues in full:**
   ```bash
   gh issue list --label follow-up --state open --limit 200 \
     --json number,title,labels,body,assignees,createdAt
   ```
   Per issue, extract: number, title (the kebab slug), area labels, the body's **action items** (the live work) + **Finding / Impact** sections, and any file references (specs / code / docs at fault).

3. **Count the open `follow-up` issues** (the JSON length above). There is no hard file-cap anymore, but a large open backlog is still a drawdown signal: if ≥40, flag urgency up-front and prioritize closes/migrations over promotions; if <15, note it's healthy and triage at lower urgency. The goal is accuracy (no stale-open issues), not a magic number.

4. **Run staleness checks per issue, in parallel where possible** (parallel code-reading sub-agents are the right tool when the backlog is large — give each a cluster of issues + file:line/spec/archive evidence requirements). For each issue, check whether its action items silently shipped:
   - **"File OpenSpec change `<name>`"** → list `openspec/changes/<name>/` and `openspec/changes/archive/<name>/`. If either exists → `superseded`.
   - **"Update `<spec-file>` § X"** / **"Update `<doc>` § Y"** → grep the spec/doc for the prescribed change. If present → `resolved-silently`.
   - **"… once `<change>` merges"** → check `openspec/changes/archive/<change>/`. If archived → `resolved-silently`.
   - **Trigger-gated** ("rule of three", "when signal X fires", "when SDK Y ships") → verify whether the trigger has fired (e.g., a 3rd call site now exists, an upstream release landed). If fired → `still-valid` and likely promotable; if not → `still-valid-defer`.
   - **A linked/closing PR** → `gh pr view <pr> --json mergedAt`. If merged → `resolved-silently`; else keep `still-valid`.
   - **Assignee present OR a linked open PR** → `in-progress`; leave alone.

5. **Classify every issue into one of:**
   - `resolved-silently` — action items already done in code/specs/docs → **close**
   - `superseded` — covered by a merged/in-flight change → **close**
   - `migrate-to-doc` — residual work belongs in a canonical doc (roadmap decision / runbook step), not an open issue → **migrate + close**
   - `still-valid-openspec` — real outstanding work, capability+behavior shape (spec-driven) → keep open; promote-candidate
   - `still-valid-regular-pr` — real outstanding work, not spec-driven (docs amendment, infra tweak, lint rule) → keep open; regular-PR candidate
   - `still-valid-defer` — real but blocked / waiting on external / trigger not fired → keep open
   - `in-progress` — assigned or has an open PR → leave alone

6. **Surface a triage table to the user.** One row per issue: `#N`, slug, area label, classification, one-line rationale. For `resolved-silently` and `superseded`, show the evidence (file:line / archived-change path / PR number). Group by classification for scannability.

### Phase B — Confirm dispositions with user

7. **Use `AskUserQuestion` to batch decisions.** Don't ask per-issue; group by disposition:
   - "Close these N silently-resolved + superseded issues?" — typically yes; show evidence again at decision time.
   - "Migrate these M issues to `<canonical-doc>` (then close)?" — list each migration target.
   - "These P still-valid OpenSpec-shaped issues: promote one now via `/next-change` hand-off, bundle multiple, or defer all?"
   - "These Q still-valid regular-PR-shaped issues: bundle into one chore PR, address one-by-one, or defer?"

8. **For ambiguous classifications, surface separately.** If a staleness check is borderline (e.g., the spec was updated but a parallel doc change in the action items is still missing), present the issue alone with options: keep open / split residual into a new issue (`gh issue create --label follow-up --label <area>`, then close the original with a "residual tracked in #<new>" comment) / close.

### Phase C — Execute closes and migrations

9. **For `resolved-silently` and `superseded` issues the user approved:** close with an evidence comment.
   ```bash
   gh issue close <N> --reason completed \
     --comment "Resolved by <evidence: file:line / change / PR #>. (triage sweep <YYYY-MM-DD>)"
   ```
   Use `--reason completed` for resolved/superseded/migrated; reserve `--reason "not planned"` for an explicit accept-the-gap drop the user signed off on (and say why in the comment).

10. **For `migrate-to-doc` issues the user approved:**
    - Move the residual work into the canonical doc (launch-prerequisite → `docs/08-Roadmap-Risk.md` § Pre-Launch or § Open Decisions; runbook tweak → `docs/07-Operations.md` Deployment Runbook; etc.). Match the doc's existing format — a checklist line / a numbered Open Decision — and add a one-line provenance note (`migrated from follow-up #N`). Don't paste the whole issue body verbatim.
    - These doc edits go on a branch + PR (no direct push to `main`).
    - Close the issue with `--comment "Migrated to docs/<file> § <section> (PR #<X>). (triage sweep <date>)"`. Closing immediately is fine — the doc PR is the durable home.

11. **No file to delete or "empty-and-recreate" anymore.** The deferred-work surface is the issue list; closing issues IS the cleanup. (Historical note: the old skill deleted `FOLLOW_UPS.md` when it hit zero entries — that machinery is retired along with the file.)

### Phase D — Promote real-work candidates

12. **For each `still-valid-openspec` issue the user approved for promotion:**
    - Synthesize a scope summary in chat:
      - Proposed change name (kebab-case, derived from the issue slug or action items — no `-v<N>` suffix per `openspec/project.md` § Change Delivery Workflow)
      - One-paragraph "why" (paraphrased from the issue's Finding + Impact)
      - One-paragraph "what changes" (derived from action items)
      - Sources: `follow-up issue #N` + any additional docs referenced
    - Recommend the user invoke `/next-change` next. That skill independently rediscovers + reconfirms the scope; if it disagrees with the promotion, that's useful tension — the user can override or re-prioritize.
    - Optionally add a `promoted` label (`gh issue edit <N> --add-label promoted`) so the issue isn't re-surfaced as fresh next sweep. Leave it **open** until the change ships, then it closes via the change's PR (`Closes #N`).
    - **Do NOT invoke `openspec-propose` directly here.** `/next-change`'s own reconciliation pass + multi-lens review loop (its phases, not this skill's) are the value-add for OpenSpec changes; this skill must not bypass them.

13. **For `still-valid-regular-pr` issues the user wants bundled:**
    - Synthesize a chore PR scope: one paragraph + bulleted file-list of changes derived from action items, referencing the issue numbers it would close.
    - Surface the scope to the user. The skill does NOT itself write the implementation — that's the user's call (separate explicit invocation).

### Phase E — Push migration PR, wrap up

14. **If any `migrate-to-doc` (or accept-gap doc edits) produced file changes,** push them on a `chore/triage-follow-ups-<YYYY-MM-DD>` branch and open ONE PR for the sweep's doc edits:
    ```bash
    git push -u origin chore/triage-follow-ups-<YYYY-MM-DD>
    gh pr create --title "chore: triage follow-up issues (<YYYY-MM-DD>)" --body "$(cat <<'EOF'
    ## Summary
    Triage sweep of the open `follow-up` GitHub issues. Open count: <before> → <after>.

    ## Closed (silently-resolved / superseded)
    - #<N> `<slug>` — <one-line resolution evidence>
    ...

    ## Migrated to canonical docs (then closed)
    - #<N> `<slug>` → `docs/<file>` § <section>
    ...

    ## Promoted to /next-change hand-off (left open, labeled `promoted`)
    - `<change-name>` (from #<N> `<slug>`)
    ...

    ## Surfaced as regular-PR scope
    - `<bundle-name>` covering: #<N>, #<M>, …

    ## Test plan
    - [ ] `gh issue list --label follow-up --state open` count is now <after>.
    - [ ] No issue with an assignee or open PR was closed.
    - [ ] Migrated issues appear in their target docs with consistent formatting + a `migrated from #N` provenance note.

    🤖 Generated with [Claude Code](https://claude.com/claude-code)
    EOF
    )"
    ```
    If the sweep produced ONLY issue closes (no doc edits), there is no PR — the closes are the audit trail. Still report per step 15.

15. **Report final state to user:**
    - Open `follow-up` count before / after (`gh issue list --label follow-up --state open | wc -l`).
    - Issues closed (with evidence) and migrated (with doc + PR).
    - Promotions handed off (list change-name candidates for `/next-change`).
    - Regular-PR work surfaced (list bundles awaiting user action).
    - Any issues left `in-progress` or `still-valid-defer` (left alone, with the gating trigger).

## Notes

- **This skill never silently rewrites issue bodies.** Actions are close, label, or migrate — not "summarize and shrink." A verbose issue body is fine; readability isn't the concern (unlike the old single-file blob).
- **Stale-open is the new rot.** An issue left open after its work silently shipped is the failure mode this skill exists to catch — always run the Phase A staleness checks against current code/specs/archive, don't trust the issue's age or title.
- **Don't expand scope from triage into implementation.** If a follow-up is `still-valid-regular-pr`, surface the scope and stop. Don't write the implementation in the same session — that's a separate explicit user action.
- **Don't merge follow-ups into OpenSpec scope without `/next-change`.** Phase D explicitly hands off to `/next-change`; do not invoke `openspec-propose` directly. The reconciliation pass and multi-lens review loop are non-optional for any OpenSpec change.
- **Drawdown discipline.** When the open backlog is large, prioritize closes/migrations over promotions (closes shrink the backlog; promotions don't) and verify the post-triage open count is meaningfully lower before stopping.
- **Branch naming.** Doc-migration PRs use `chore/triage-follow-ups-<YYYY-MM-DD>` (the `<area>/<slug>` convention for non-OpenSpec changes, per `openspec/project.md` § Change Delivery Workflow). Don't use the `change-name`-as-branch convention — that's reserved for OpenSpec changes.
- **Don't `--no-verify` or skip hooks** on any doc-migration commit.
- **Engineering judgment over context budget.** Per `CLAUDE.md` § Engineering judgment over context budget: do NOT silently compress the triage list to fit a fading window. If context is tight, surface it explicitly and offer to split into a follow-up session — do not skip issues.
- **Public-repo posture applies.** Issues + their close/migration comments are public (source-available repo). Ensure no real secrets, customer PII, or speculative commercial strategy land in close comments or migration-target docs.
