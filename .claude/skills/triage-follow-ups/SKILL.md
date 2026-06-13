---
name: triage-follow-ups
description: Triage the open `follow-up` GitHub issues end-to-end — list every open issue, run staleness checks, classify each as silently-resolved / superseded / migrate-to-canonical-doc / still-valid (OpenSpec or regular-PR shape) / in-progress, then act per disposition (close, migrate, hand off to `/next-change`, or surface as regular-PR scope). Keeps the `follow-up` issue backlog accurate; complements `/next-change` (which proposes new capabilities, not deferred-debt cleanup). Safe to run as multiple concurrent sweeps — each claims a disjoint set of issues via a transient `triaging` label + a per-session migration branch (mirrors `/next-change`'s parallel-session coordination).
---

Triage the open `follow-up` GitHub issues end-to-end: list every open issue, verify each is still valid against current code/specs/docs, surface classifications to the user, then execute closes / doc-migrations / promotion hand-offs per the user's choices. **Multiple sweeps may run concurrently** (each in its own git worktree) — they partition the backlog by claiming issues with a transient `triaging` label rather than serializing.

## Context

Deferred-work tracking lives in **GitHub Issues labeled `follow-up`** (filter: `gh issue list --label follow-up --state open`). The legacy root `FOLLOW_UPS.md` file was **retired 2026-06-09** ([PR #206](https://github.com/aditrioka/nearyou-id/pull/206)) — its 38-entry backlog was dispositioned: 33 migrated to issues #173–#205, 2 to `docs/08-Roadmap-Risk.md` § Open Decisions, 3 closed as accept-the-gap. New findings are filed as `gh issue create --label follow-up` (+ an area label: `mobile` / `backend` / `observability` / `admin` / `deferred`), never appended to a file. If you find a stray reference to `FOLLOW_UPS.md` in code/specs/docs, it is a stale pre-retirement artifact — reconcile it to the owning issue (or note it; archived changes under `openspec/changes/archive/**` are immutable history and stay as-is).

The discipline this skill enforces: **keep the open `follow-up` backlog accurate** — close issues whose work silently shipped, migrate issues whose residual work belongs in a canonical doc, and promote ready OpenSpec-shaped work. Unlike the old file, GitHub Issues don't "rot" by accumulating into an unreadable blob — but they DO rot by staying *open* after the work is silently resolved by intervening changes. That stale-open drift is the primary thing this skill catches.

It is **complementary** to `/next-change`, not a replacement:

- `/next-change` answers: "what new capability should I propose next?" — sources are roadmap + version docs + open spec gaps.
- `/triage-follow-ups` answers: "what tracked debt is ready to close, and how?" — sources are the open `follow-up` issues + their referenced files.

If a triage cycle promotes a follow-up to a real OpenSpec change, this skill produces a vetted scope summary and recommends the user invoke `/next-change` next. It does NOT scaffold the proposal itself — `/next-change`'s reconciliation pass + multi-lens review loop are non-negotiable for OpenSpec changes.

**Parallel-session coordination (canonical for this skill).** Multiple `/triage-follow-ups` sweeps — and concurrent `/next-change` sessions — run at the same time, each in its own git worktree (`git worktree list`). Triage is **not** naturally partitioned the way `/next-change` is: every sweep reads the SAME shared list (the open `follow-up` issues), and its side-effects hit shared state (issue closes are global, not per-branch; doc-migrations historically funneled into ONE dated branch). Without coordination, two sweeps double-process the same issue — duplicate close-comments, and the same residual work migrated into two PRs. This skill coordinates with **zero extra infra — a transient `triaging` label + a per-session migration branch ARE the claim registry** (the analog of `/next-change`'s open-PR-and-branch registry):

- **The claim unit is the issue number** (the analog of `/next-change`'s change-name/branch). A sweep reserves an issue by adding the `triaging` label to it; other sweeps treat any `triaging`-labeled issue as claimed and skip it.
- **Read claims before processing (A.1).** Enumerate in-flight work — `gh issue list --label triaging` (issues another sweep owns), `gh pr list --state open` (triage migration PRs *and* `/next-change` claim PRs), `git branch -r`, `git worktree list` — and EXCLUDE any `follow-up` issue already labeled `triaging`, already assigned, or with an open linked PR. That exclusion is the dedup.
- **Claim each issue right before acting on it (C.9), not at list-time.** A sweep claims only the issues the user approved it to act on, then re-reads to confirm. Claiming at act-time (not list-time) keeps the reservation window small — the analog of `/next-change`'s "claim right after the pick, just before the slow scaffold."
- **A claim is a reservation, not a commitment, and `triaging` is TRANSIENT state — never persistent.** Every issue that stays OPEN after the sweep MUST have `triaging` removed (E.16). A closed issue drops out of the `--state open` filter so its label is moot, but a `triaging` label left on an *open* issue permanently hides it from the next sweep. Abandon an issue mid-sweep (user redirects) → un-claim it immediately (`--remove-label triaging`). This is the triage analog of `/next-change`'s abandoned-claim-PR cleanup.
- **Residual race + recovery (same robustness as `/next-change`, not stronger).** The label-add + re-read shrinks but does not eliminate the collision window — two sweeps adding `triaging` in the same second can both think they won. The C.9 re-check (drop any issue that gained a competing claim since A.1) is the analog of `/next-change`'s A.5.1 re-check + "PR already exists" guard. A residual double-claim is low-harm (a duplicate close-comment, or the same migration in two PRs that rebase at merge) — surface it, don't crash.
- **Per-session migration branch.** Doc-migrations no longer funnel into one dated branch. Each sweep opens its OWN PR on `chore/triage-follow-ups-<YYYY-MM-DD>-<session-token>` (session-token = worktree basename), covering only the issues it owns. Disjoint issue sets → disjoint doc edits; if two sweeps migrate into the same doc section, the later PR rebases (flag the overlap, like `/next-change`'s footprint note).

## Steps

### Phase A — Read & classify

**A.0 — Session identity & claim setup (run FIRST, before anything else).**

- **Derive this sweep's session token** — `SESSION=$(basename "$(git rev-parse --show-toplevel)")` (the worktree basename, e.g. `determined-hawking-40f52a`; falls back to the repo name on a non-worktree checkout). Used for the per-session migration branch (E.15) and when surfacing claim collisions. This is the triage analog of `/next-change`'s one-worktree-per-session model.
- **Ensure the `triaging` claim label exists** (idempotent): `gh label create triaging --color FBCA04 --description "Transient claim: being processed by a /triage-follow-ups sweep" 2>/dev/null || true`.
- **gh account:** confirm the active `gh` account has write access to this repo (`gh auth status`; nearyou-id needs `aditrioka` — `gh auth switch --user aditrioka` if wrong). Closing / labeling / assigning issues with the wrong account 403s.

1. **Pre-flight checks.**
   - **Active change:** if `openspec/changes/` contains an unarchived directory (a change is mid-flight), surface to the user and ask whether to proceed. Triage during an active change risks spurious classifications — the active change might silently resolve a follow-up but isn't merged yet.
   - **Read in-flight claims (parallel-session coordination).** Build the in-flight set so this sweep partitions cleanly against other sweeps and concurrent `/next-change` sessions:
     ```bash
     gh issue list --label triaging --state open --json number,title         # issues another sweep owns
     gh pr list --state open --json number,title,headRefName,isDraft         # triage migration PRs + /next-change claim PRs
     git fetch origin --quiet && git branch -r
     git worktree list                                                        # sibling worktrees = concurrent sessions
     ```
     Any `follow-up` issue already labeled `triaging`, already assigned, or with an open linked PR is claimed (by another sweep or a human) — it is excluded at step 2. **Stale-vs-live `triaging` labels** (a crashed prior sweep vs a running one) can't be told apart automatically — surface to the user per Recovery § stale claim rather than blindly reclaiming.

2. **List the open `follow-up` issues in full:**
   ```bash
   gh issue list --label follow-up --state open --limit 200 \
     --json number,title,labels,body,assignees,createdAt
   ```
   **Drop any issue already labeled `triaging`, already assigned, or with an open linked PR** — those are claimed by another sweep or a human (parallel-session coordination); leave them for the owner. The remainder is THIS sweep's candidate set. Per remaining issue, extract: number, title (the kebab slug), area labels, the body's **action items** (the live work) + **Finding / Impact** sections, and any file references (specs / code / docs at fault).

3. **Count the candidate `follow-up` issues** (the post-exclusion JSON length above; also note the total open count for reporting). There is no hard file-cap anymore, but a large open backlog is still a drawdown signal: if ≥40 open, flag urgency up-front and prioritize closes/migrations over promotions; if <15, note it's healthy and triage at lower urgency. The goal is accuracy (no stale-open issues), not a magic number.

4. **Run staleness checks per issue, in parallel where possible** (parallel code-reading sub-agents are the right tool when the backlog is large — give each a cluster of issues + file:line/spec/archive evidence requirements; this intra-sweep fan-out is the primary throughput lever, distinct from the cross-sweep partitioning above). For each issue, check whether its action items silently shipped:
   - **"File OpenSpec change `<name>`"** → list `openspec/changes/<name>/` and `openspec/changes/archive/<name>/`. If either exists → `superseded`.
   - **"Update `<spec-file>` § X"** / **"Update `<doc>` § Y"** → grep the spec/doc for the prescribed change. If present → `resolved-silently`.
   - **"… once `<change>` merges"** → check `openspec/changes/archive/<change>/`. If archived → `resolved-silently`.
   - **Trigger-gated** ("rule of three", "when signal X fires", "when SDK Y ships") → verify whether the trigger has fired (e.g., a 3rd call site now exists, an upstream release landed). If fired → `still-valid` and likely promotable; if not → `still-valid-defer`.
   - **A linked/closing PR** → `gh pr view <pr> --json mergedAt`. If merged → `resolved-silently`; else keep `still-valid`.
   - **Assignee present OR a linked open PR** → `in-progress`; leave alone. (Note: a `triaging` label is a sweep claim, not human in-progress — those issues were already excluded at step 2, so they won't appear here.)

5. **Classify every issue into one of:**
   - `resolved-silently` — action items already done in code/specs/docs → **close**
   - `superseded` — covered by a merged/in-flight change → **close**
   - `migrate-to-doc` — residual work belongs in a canonical doc (roadmap decision / runbook step), not an open issue → **migrate + close**
   - `still-valid-openspec` — real outstanding work, capability+behavior shape (spec-driven) → keep open; promote-candidate
   - `still-valid-regular-pr` — real outstanding work, not spec-driven (docs amendment, infra tweak, lint rule) → keep open; regular-PR candidate
   - `still-valid-defer` — real but blocked / waiting on external / trigger not fired → keep open
   - `in-progress` — assigned or has an open PR → leave alone

6. **Surface a triage table to the user.** One row per issue: `#N`, slug, area label, classification, one-line rationale. For `resolved-silently` and `superseded`, show the evidence (file:line / archived-change path / PR number). Group by classification for scannability. If step 2 excluded any issues as already-claimed, list them separately under "deferred to concurrent sweep" so the user sees the full backlog accounting.

### Phase B — Confirm dispositions with user

7. **Use `AskUserQuestion` to batch decisions.** Don't ask per-issue; group by disposition:
   - "Close these N silently-resolved + superseded issues?" — typically yes; show evidence again at decision time.
   - "Migrate these M issues to `<canonical-doc>` (then close)?" — list each migration target.
   - "These P still-valid OpenSpec-shaped issues: promote one now via `/next-change` hand-off, bundle multiple, or defer all?"
   - "These Q still-valid regular-PR-shaped issues: bundle into one chore PR, address one-by-one, or defer?"

8. **For ambiguous classifications, surface separately.** If a staleness check is borderline (e.g., the spec was updated but a parallel doc change in the action items is still missing), present the issue alone with options: keep open / split residual into a new issue (`gh issue create --label follow-up --label <area>`, then close the original with a "residual tracked in #<new>" comment) / close.

### Phase C — Claim, then execute closes and migrations

9. **Claim the issues this sweep will act on — BEFORE closing or migrating them** (parallel-session coordination). The set to claim = every issue the user approved for a close / migrate / promote / regular-PR-surface action. (Do NOT claim `still-valid-defer` or `in-progress` issues — you're not touching them.) For each:
   ```bash
   gh issue edit <N> --add-label triaging
   gh issue view <N> --json labels,assignees   # re-read to confirm the claim
   ```
   - **Re-check (residual-race recovery).** If the re-read shows the issue *also* gained an assignee, or it was already `triaging` before your add (a concurrent sweep claimed it in the window since A.1), **drop it from this sweep** — that session owns it. Report it as "deferred to concurrent sweep," not failed. This is the analog of `/next-change`'s A.5.1 re-check + "PR already exists" guard.
   - **Reservation, not commitment.** Claiming is cheap and reversible — if you later abandon an issue (user redirects, context split), un-claim immediately: `gh issue edit <N> --remove-label triaging`.
   - **Label-only claim (deliberate).** Do NOT self-assign as the claim. For the solo `aditrioka` account an assignee can't be distinguished from a human working the issue — step 2 would then hide it as `in-progress`, and a self-assign left behind (E.16 only removes the label) would lock the issue out of future sweeps. The `triaging` label is the single authoritative claim marker; a *human-set* assignee remains a legitimate back-off signal at the re-check above.

10. **For `resolved-silently` and `superseded` issues the user approved** (now claimed): close with an evidence comment. (A closed issue drops out of `--state open`, so its `triaging` label is moot — no cleanup needed for closed issues.)
    ```bash
    gh issue close <N> --reason completed \
      --comment "Resolved by <evidence: file:line / change / PR #>. (triage sweep <YYYY-MM-DD>)"
    ```
    Use `--reason completed` for resolved/superseded/migrated; reserve `--reason "not planned"` for an explicit accept-the-gap drop the user signed off on (and say why in the comment).

11. **For `migrate-to-doc` issues the user approved** (now claimed):
    - Move the residual work into the canonical doc (launch-prerequisite → `docs/08-Roadmap-Risk.md` § Pre-Launch or § Open Decisions; runbook tweak → `docs/07-Operations.md` Deployment Runbook; etc.). Match the doc's existing format — a checklist line / a numbered Open Decision — and add a one-line provenance note (`migrated from follow-up #N`). Don't paste the whole issue body verbatim.
    - These doc edits go on this sweep's **per-session** migration branch + PR (no direct push to `main`) — see E.15.
    - Close the issue with `--comment "Migrated to docs/<file> § <section> (PR #<X>). (triage sweep <date>)"`. Closing immediately is fine — the doc PR is the durable home.

12. **No file to delete or "empty-and-recreate" anymore.** The deferred-work surface is the issue list; closing issues IS the cleanup. (Historical note: the old skill deleted `FOLLOW_UPS.md` when it hit zero entries — that machinery is retired along with the file.)

### Phase D — Promote real-work candidates

13. **For each `still-valid-openspec` issue the user approved for promotion** (now claimed):
    - Synthesize a scope summary in chat:
      - Proposed change name (kebab-case, derived from the issue slug or action items — no `-v<N>` suffix per `openspec/project.md` § Change Delivery Workflow)
      - One-paragraph "why" (paraphrased from the issue's Finding + Impact)
      - One-paragraph "what changes" (derived from action items)
      - Sources: `follow-up issue #N` + any additional docs referenced
    - Recommend the user invoke `/next-change` next. That skill independently rediscovers + reconfirms the scope; if it disagrees with the promotion, that's useful tension — the user can override or re-prioritize.
    - Add a `promoted` label (`gh issue edit <N> --add-label promoted`) so the issue isn't re-surfaced as fresh next sweep. Leave it **open** until the change ships, then it closes via the change's PR (`Closes #N`). (Because it stays open, its `triaging` claim must be released at E.16.)
    - **Do NOT invoke `openspec-propose` directly here.** `/next-change`'s own reconciliation pass + multi-lens review loop (its phases, not this skill's) are the value-add for OpenSpec changes; this skill must not bypass them.

14. **For `still-valid-regular-pr` issues the user wants bundled** (now claimed):
    - Synthesize a chore PR scope: one paragraph + bulleted file-list of changes derived from action items, referencing the issue numbers it would close.
    - Surface the scope to the user. The skill does NOT itself write the implementation — that's the user's call (separate explicit invocation). (These issues stay open → release their `triaging` claim at E.16.)

### Phase E — Push migration PR, release claims, wrap up

15. **If any `migrate-to-doc` (or accept-gap doc edits) produced file changes,** push them on this sweep's **per-session** branch `chore/triage-follow-ups-<YYYY-MM-DD>-<SESSION>` (SESSION from A.0 — the suffix is what lets concurrent sweeps each open their own PR without colliding on branch name, per parallel-session coordination) and open ONE PR for THIS sweep's doc edits:
    ```bash
    git push -u origin chore/triage-follow-ups-<YYYY-MM-DD>-<SESSION>
    gh pr create --title "chore: triage follow-up issues (<YYYY-MM-DD>, <SESSION>)" --body "$(cat <<'EOF'
    ## Summary
    Triage sweep (session `<SESSION>`) of the open `follow-up` GitHub issues. Open count: <before> → <after>.

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

    ## Deferred to a concurrent sweep (claimed by another session)
    - #<N> `<slug>` — dropped at claim re-check

    ## Test plan
    - [ ] `gh issue list --label follow-up --state open` count is now <after>.
    - [ ] No issue with an assignee or open PR was closed.
    - [ ] Migrated issues appear in their target docs with consistent formatting + a `migrated from #N` provenance note.
    - [ ] No `triaging` label remains on any issue this sweep left open (`gh issue list --label triaging --state open`).

    🤖 Generated with [Claude Code](https://claude.com/claude-code)
    EOF
    )"
    ```
    If another concurrent sweep's migration PR touched the SAME doc section, rebase this branch on the merged one (no force-push to `main`; `--force-with-lease` on your own branch is fine) — see Recovery § doc overlap. If the sweep produced ONLY issue closes (no doc edits), there is no PR — the closes are the audit trail. Still release claims (E.16) and report (E.17).

16. **Release transient claims (parallel-session coordination — do NOT skip).** Remove the `triaging` label from every issue this sweep claimed that is **still open** (promoted, regular-PR-surfaced, or any acted-on-but-kept-open):
    ```bash
    gh issue edit <N> --remove-label triaging
    ```
    Closed issues (resolved / superseded / migrated-then-closed) need no cleanup — they dropped out of the `--state open` filter, so the label is moot. **Never leave `triaging` on an open issue**: it permanently hides the issue from future sweeps (the triage analog of `/next-change`'s abandoned-claim-PR cleanup). Verify with `gh issue list --label triaging --state open` — only issues owned by *other* live sweeps should remain.

17. **Report final state to user:**
    - Open `follow-up` count before / after (`gh issue list --label follow-up --state open | wc -l`).
    - Issues closed (with evidence) and migrated (with doc + PR).
    - Promotions handed off (list change-name candidates for `/next-change`).
    - Regular-PR work surfaced (list bundles awaiting user action).
    - Issues deferred to a concurrent sweep (dropped at the C.9 re-check) and any left `in-progress` / `still-valid-defer` (left alone, with the gating trigger).
    - The per-session migration PR (`chore/triage-follow-ups-<date>-<SESSION>`), if one was opened, and confirmation that no `triaging` label remains on issues this sweep owned.

## Recovery from common failures

- **C.9 re-check shows the issue is already claimed** (gained a competing `triaging` / assignee since A.1) — a concurrent sweep won the race. Drop the issue; report it as "deferred to concurrent sweep." Do NOT force or re-add — that's the residual-race guard working as intended.
- **Stale `triaging` labels (§ stale claim)** — at A.1 you see `triaging`-labeled issues but no corresponding running sweep or open migration PR / worktree. A crashed prior sweep can leave a claim behind, and it can't be auto-distinguished from a live claim. Surface to the user via `AskUserQuestion`: **skip** (assume a sibling sweep is live) or **reclaim** (assume stale → `gh issue edit <N> --remove-label triaging`, then include it in this sweep). Audit the full set with `gh issue list --label triaging --state open`.
- **`--add-label triaging` 404s the label** — A.0's idempotent `gh label create … || true` should have created it; if it's still missing, create it explicitly and retry the claim.
- **Two migration PRs touch the same doc section (§ doc overlap)** — the later sweep rebases its `chore/triage-follow-ups-<date>-<SESSION>` branch on the merged one and resolves the doc conflict. No force-push to `main`; `--force-with-lease` on the topic branch is fine. Flag the overlap at A.1 when you see another open triage migration PR.
- **`gh` 403 on close / label / assign ("must be a collaborator")** — wrong active account. nearyou-id needs `aditrioka` (not `adi-at-buku`). `gh auth switch --user aditrioka` and retry.
- **Dirty / wrong-branch worktree before the migration push (E.15)** — do NOT silently stash or commit unknown state. Surface it to the user (mirror `/next-change`'s A.5.1 guard). Confirm `git rev-parse --show-toplevel` is this sweep's worktree before staging doc edits.
- **Pre-commit hook fails on a doc-migration commit** — NEVER `--no-verify`. Diagnose (usually a docs lint / formatting rule), fix, re-stage, create a NEW commit (do NOT amend — per CLAUDE.md).

## Notes

- **This skill never silently rewrites issue bodies.** Actions are close, label, or migrate — not "summarize and shrink." A verbose issue body is fine; readability isn't the concern (unlike the old single-file blob).
- **Concurrent sweeps partition, they do NOT serialize.** (This supersedes the old "if a triage PR is already open, reconcile / wait" posture.) Each sweep claims a disjoint issue set via the `triaging` label and opens a per-session migration PR. The coordination is the same shape as `/next-change`'s — read claims before acting, claim just before acting, re-check, release on abandon — adapted to issues-as-claim-units.
- **`triaging` is transient.** Every issue this sweep leaves OPEN must end the sweep without a `triaging` label (E.16). A `triaging` label surviving on an open issue is a leaked claim that hides the issue from the next sweep — treat it like a leaked next-change claim PR.
- **Stale-open is the new rot.** An issue left open after its work silently shipped is the failure mode this skill exists to catch — always run the Phase A staleness checks against current code/specs/archive, don't trust the issue's age or title.
- **Don't expand scope from triage into implementation.** If a follow-up is `still-valid-regular-pr`, surface the scope and stop. Don't write the implementation in the same session — that's a separate explicit user action.
- **Don't merge follow-ups into OpenSpec scope without `/next-change`.** Phase D explicitly hands off to `/next-change`; do not invoke `openspec-propose` directly. The reconciliation pass and multi-lens review loop are non-optional for any OpenSpec change.
- **Drawdown discipline.** When the open backlog is large, prioritize closes/migrations over promotions (closes shrink the backlog; promotions don't) and verify the post-triage open count is meaningfully lower before stopping.
- **Branch naming.** Doc-migration PRs use `chore/triage-follow-ups-<YYYY-MM-DD>-<session-token>` (the `<area>/<slug>` convention for non-OpenSpec changes, per `openspec/project.md` § Change Delivery Workflow, with a per-session suffix for concurrency-safety). Don't use the `change-name`-as-branch convention — that's reserved for OpenSpec changes.
- **Don't `--no-verify` or skip hooks** on any doc-migration commit.
- **Engineering judgment over context budget.** Per `CLAUDE.md` § Engineering judgment over context budget: do NOT silently compress the triage list to fit a fading window. If context is tight, surface it explicitly and offer to split into a follow-up session — do not skip issues.
- **Public-repo posture applies.** Issues + their close/migration comments are public (source-available repo). Ensure no real secrets, customer PII, or speculative commercial strategy land in close comments or migration-target docs.
