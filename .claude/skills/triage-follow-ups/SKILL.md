---
name: triage-follow-ups
description: Triage the open `follow-up` GitHub issues end-to-end — list every open issue, run staleness checks, classify each as silently-resolved / superseded / migrate-to-canonical-doc / still-valid (OpenSpec or regular-PR shape) / in-progress, then act per disposition (close, migrate, hand off to `/next-change`, or surface as regular-PR scope). Keeps the `follow-up` backlog accurate. NOT for proposing new capabilities (use `/next-change`). Safe to run as concurrent sweeps — each claims a disjoint set via a transient `triaging` label + a per-session migration branch.
---

List the open `follow-up` issues, verify each is still valid against current code/specs/docs, surface classifications to the user, then execute closes / doc-migrations / promotion hand-offs per the user's choices. **Multiple sweeps may run concurrently** (each in its own worktree) — they partition the backlog by claiming issues with a transient `triaging` label rather than serializing.

## Context

Deferred-work tracking lives in **GitHub Issues labeled `follow-up`** (`gh issue list --label follow-up --state open`). The legacy `FOLLOW_UPS.md` was retired 2026-06-09 ([PR #206](https://github.com/aditrioka/nearyou-id/pull/206)). New findings are filed as `gh issue create --label follow-up` (+ an area label: `mobile`/`backend`/`observability`/`admin`/`deferred`). A stray `FOLLOW_UPS.md` reference in code/specs/docs is a stale pre-retirement artifact — reconcile it to the owning issue (archived changes under `openspec/changes/archive/**` are immutable history; leave them).

This skill keeps the open backlog accurate: close issues whose work silently shipped, migrate residual work to a canonical doc, promote ready OpenSpec-shaped work. GitHub Issues rot by staying **open** after intervening changes silently resolve them — that stale-open drift is the primary thing this skill catches.

**Complementary to `/next-change`, not a replacement:** `/next-change` answers "what new capability next?" (roadmap + version docs + spec gaps); this answers "what tracked debt is ready to close, and how?" (open `follow-up` issues + their referenced files). If a cycle promotes a follow-up to a real OpenSpec change, this skill produces a vetted scope summary and recommends the user invoke `/next-change` — it does NOT scaffold the proposal (that skill's reconciliation pass + multi-lens review loop are non-negotiable).

**Parallel-session coordination (canonical).** Triage is NOT naturally partitioned the way `/next-change` is: every sweep reads the SAME shared issue list and its side-effects hit shared state (closes are global; migrations historically funneled into ONE branch). Coordinate with zero extra infra — a transient `triaging` label + a per-session migration branch ARE the claim registry:

- **Claim unit = the issue number.** A sweep reserves an issue by adding `triaging`; others treat any `triaging`-labeled issue as claimed and skip it.
- **Read claims before processing (A.1).** Enumerate in-flight work (`gh issue list --label triaging`, `gh pr list --state open`, `git branch -r`, `git worktree list`) and EXCLUDE any `follow-up` issue already labeled `triaging`, already assigned, or with an open linked PR. That exclusion is the dedup.
- **Claim at act-time, not list-time (C.9).** Claim only the issues the user approved acting on, then re-read to confirm — keeps the reservation window small.
- **`triaging` is TRANSIENT — never persistent.** Every issue that stays OPEN after the sweep MUST have `triaging` removed (E.16). A `triaging` label left on an *open* issue permanently hides it from the next sweep. Abandon an issue mid-sweep → un-claim immediately (`--remove-label triaging`).
- **Residual race + recovery.** Label-add + re-read shrinks but doesn't eliminate the window — the C.9 re-check (drop any issue that gained a competing claim since A.1) is the guard. A double-claim is low-harm (a duplicate close-comment, or the same migration in two PRs that rebase) — surface it, don't crash.
- **Per-session migration branch.** Each sweep opens its OWN PR on `chore/triage-follow-ups-<YYYY-MM-DD>-<session-token>` (session-token = worktree basename), covering only the issues it owns. If two sweeps migrate into the same doc section, the later PR rebases (flag the overlap).

## Steps

### Phase A — Read & classify

**A.0 — Session identity & claim setup (FIRST).**
- **Session token:** `SESSION=$(basename "$(git rev-parse --show-toplevel)")` (worktree basename; falls back to repo name). Used for the per-session migration branch (E.15) and surfacing collisions.
- **Ensure the claim label exists** (idempotent): `gh label create triaging --color FBCA04 --description "Transient claim: /triage-follow-ups sweep" 2>/dev/null || true`.
- **gh account:** nearyou-id needs `aditrioka` (`gh auth switch --user aditrioka` if wrong) — closing/labeling with the wrong account 403s.

1. **Pre-flight checks.**
   - **Active change:** if `openspec/changes/` has an unarchived directory, surface to the user and ask whether to proceed — a mid-flight change might silently resolve a follow-up but isn't merged yet, risking spurious classifications.
   - **Read in-flight claims:**
     ```bash
     gh issue list --label triaging --state open --json number,title        # issues another sweep owns
     gh pr list --state open --json number,title,headRefName,isDraft        # triage PRs + /next-change claim PRs
     git fetch origin --quiet && git branch -r
     git worktree list                                                       # sibling worktrees = concurrent sessions
     ```
     Any `follow-up` issue already `triaging`, assigned, or with an open linked PR is claimed — excluded at step 2. **Stale vs live `triaging`** can't be told apart automatically — surface per Recovery § stale claim rather than blindly reclaiming.

2. **List the open `follow-up` issues:**
   ```bash
   gh issue list --label follow-up --state open --limit 200 \
     --json number,title,labels,body,assignees,createdAt
   ```
   **Drop any already `triaging` / assigned / with an open linked PR** (claimed by another sweep or a human). The remainder is THIS sweep's candidate set. Per issue, extract: number, slug, area labels, the body's **action items** + **Finding / Impact**, and file references.

3. **Count candidates** (post-exclusion; also note total open for reporting). No hard cap, but ≥40 open → flag urgency, prioritize closes/migrations over promotions; <15 → healthy, lower urgency. Goal is accuracy (no stale-open issues), not a number.

4. **Run staleness checks per issue, in parallel where possible** (parallel code-reading sub-agents for a large backlog — give each a cluster + file:line/spec/archive evidence requirements; this intra-sweep fan-out is the throughput lever, distinct from cross-sweep partitioning). Check whether action items silently shipped:
   - **"File OpenSpec change `<name>`"** → list `openspec/changes/<name>/` + `archive/<name>/`. Either exists → `superseded`.
   - **"Update `<spec/doc>` § X"** → grep for the prescribed change. Present → `resolved-silently`.
   - **"… once `<change>` merges"** → check `archive/<change>/`. Archived → `resolved-silently`.
   - **Trigger-gated** ("rule of three", "when X fires", "when SDK Y ships") → verify the trigger fired. Fired → `still-valid` + likely promotable; else `still-valid-defer`.
   - **A linked/closing PR** → `gh pr view <pr> --json mergedAt`. Merged → `resolved-silently`; else `still-valid`.
   - **Assignee OR linked open PR** → `in-progress`; leave alone.

5. **Classify into one of:**
   - `resolved-silently` — action items already done → **close**
   - `superseded` — covered by a merged/in-flight change → **close**
   - `migrate-to-doc` — residual work belongs in a canonical doc → **migrate + close**
   - `still-valid-openspec` — real outstanding capability+behavior work → keep open; promote-candidate
   - `still-valid-regular-pr` — real work, not spec-driven (docs/infra/lint) → keep open; regular-PR candidate
   - `still-valid-defer` — real but blocked / trigger not fired → keep open
   - `in-progress` — assigned or open PR → leave alone

6. **Surface a triage table** — one row per issue (`#N`, slug, area, classification, one-line rationale); show evidence (file:line / archive path / PR #) for resolved/superseded. Group by classification. List step-2-excluded issues separately under "deferred to concurrent sweep" for full accounting.

### Phase B — Confirm dispositions

7. **Batch decisions via `AskUserQuestion`** (group by disposition, don't ask per-issue): close N resolved+superseded? migrate M to `<doc>` then close? promote/bundle/defer the P still-valid-OpenSpec? bundle/one-by-one/defer the Q still-valid-regular-PR?

8. **Ambiguous classifications, surface separately.** Borderline (spec updated but a parallel doc change still missing) → present alone with options: keep open / split residual into a new `follow-up` issue (then close original with "residual tracked in #<new>") / close.

### Phase C — Claim, then execute closes and migrations

9. **Claim the issues this sweep will act on — BEFORE closing/migrating** (set = every issue approved for close/migrate/promote/regular-PR-surface; do NOT claim `still-valid-defer`/`in-progress`):
   ```bash
   gh issue edit <N> --add-label triaging
   gh issue view <N> --json labels,assignees   # re-read to confirm
   ```
   - **Re-check (residual-race recovery).** If the re-read shows a new assignee, or it was already `triaging` before your add, **drop it** — report "deferred to concurrent sweep," not failed.
   - **Reservation, not commitment** — abandon → un-claim immediately (`--remove-label triaging`).
   - **Label-only claim (deliberate).** Do NOT self-assign as the claim: for the solo `aditrioka` account an assignee can't be distinguished from a human working the issue (step 2 would hide it as `in-progress`, and E.16 only removes the label). The `triaging` label is the single authoritative claim marker; a *human-set* assignee stays a legitimate back-off signal at the re-check.

10. **Close approved `resolved-silently`/`superseded`** (now claimed) with evidence (a closed issue drops out of `--state open`, so its label is moot):
    ```bash
    gh issue close <N> --reason completed \
      --comment "Resolved by <evidence: file:line / change / PR #>. (triage sweep <YYYY-MM-DD>)"
    ```
    `--reason completed` for resolved/superseded/migrated; reserve `--reason "not planned"` for an explicit user-signed accept-the-gap drop (say why).

11. **Migrate approved `migrate-to-doc`** (now claimed): move the residual into the canonical doc (launch-prerequisite → `docs/08` § Pre-Launch / § Open Decisions; runbook tweak → `docs/07` Deployment Runbook). Match the doc's format + add a one-line provenance (`migrated from follow-up #N`); don't paste the whole body. Doc edits go on this sweep's per-session migration branch + PR (E.15). Close with `--comment "Migrated to docs/<file> § <section> (PR #<X>). (triage sweep <date>)"`.

12. **No file to delete anymore** — the deferred-work surface is the issue list; closing issues IS the cleanup.

### Phase D — Promote real-work candidates

13. **Each approved `still-valid-openspec`** (now claimed):
    - Synthesize a scope summary in chat: proposed change name (kebab, no `-v<N>`), one-paragraph "why" (from Finding + Impact), one-paragraph "what changes" (from action items), sources (`follow-up #N` + referenced docs).
    - Recommend the user invoke `/next-change` — it independently rediscovers + reconfirms scope (disagreement is useful tension).
    - Add `promoted` (`gh issue edit <N> --add-label promoted`) so it isn't re-surfaced as fresh; leave it **open** until the change ships (closes via the change's PR `Closes #N`). Because it stays open, release its `triaging` claim at E.16.
    - **Do NOT invoke `openspec-propose` directly** — that bypasses `/next-change`'s reconciliation + multi-lens review.

14. **Each `still-valid-regular-pr` the user wants bundled** (now claimed): synthesize a chore PR scope (one paragraph + file-list from action items, referencing the issue numbers it closes). Surface it; the skill does NOT write the implementation (separate explicit invocation). These stay open → release `triaging` at E.16.

### Phase E — Push migration PR, release claims, wrap up

15. **If any doc edits were produced,** push on `chore/triage-follow-ups-<YYYY-MM-DD>-<SESSION>` (SESSION from A.0 — the suffix lets concurrent sweeps each open their own PR) and open ONE PR for THIS sweep's edits:
    ```bash
    git push -u origin chore/triage-follow-ups-<YYYY-MM-DD>-<SESSION>
    gh pr create --title "chore: triage follow-up issues (<YYYY-MM-DD>, <SESSION>)" --body "$(cat <<'EOF'
    ## Summary
    Triage sweep (session `<SESSION>`). Open count: <before> → <after>.

    ## Closed (silently-resolved / superseded)
    - #<N> `<slug>` — <evidence>

    ## Migrated to canonical docs (then closed)
    - #<N> `<slug>` → `docs/<file>` § <section>

    ## Promoted to /next-change hand-off (left open, labeled `promoted`)
    - `<change-name>` (from #<N> `<slug>`)

    ## Surfaced as regular-PR scope
    - `<bundle-name>` covering: #<N>, #<M>, …

    ## Deferred to a concurrent sweep (claimed by another session)
    - #<N> `<slug>` — dropped at claim re-check

    ## Test plan
    - [ ] `gh issue list --label follow-up --state open` count is now <after>.
    - [ ] No issue with an assignee or open PR was closed.
    - [ ] Migrated issues appear in their target docs with a `migrated from #N` note.
    - [ ] No `triaging` label remains on any issue this sweep left open.

    🤖 Generated with [Claude Code](https://claude.com/claude-code)
    EOF
    )"
    ```
    If another sweep's migration PR touched the SAME doc section, rebase on the merged one (`--force-with-lease` on your own branch; never on `main`) — Recovery § doc overlap. Closes-only sweep (no doc edits) → no PR; the closes are the audit trail. Still release claims (E.16) and report (E.17).

16. **Release transient claims (do NOT skip).** Remove `triaging` from every issue this sweep claimed that is **still open** (promoted, regular-PR-surfaced, any acted-on-but-kept-open):
    ```bash
    gh issue edit <N> --remove-label triaging
    ```
    Closed issues need no cleanup (dropped out of `--state open`). **Never leave `triaging` on an open issue** — it permanently hides the issue from future sweeps. Verify: `gh issue list --label triaging --state open` should show only *other* live sweeps' issues.

17. **Report final state:** open count before/after; issues closed (with evidence) + migrated (with doc + PR); promotions handed off (change-name candidates for `/next-change`); regular-PR bundles awaiting user action; issues deferred to a concurrent sweep + any left `in-progress`/`still-valid-defer` (with gating trigger); the per-session migration PR (if opened) + confirmation no `triaging` remains on owned issues.

## Recovery from common failures

- **C.9 re-check shows the issue is already claimed** — a concurrent sweep won the race. Drop it, report "deferred to concurrent sweep." Don't force or re-add.
- **Stale `triaging` labels (§ stale claim)** — at A.1 you see `triaging` issues but no running sweep / open migration PR / worktree. Can't auto-distinguish crashed-vs-live. AskUserQuestion: **skip** (assume a sibling is live) or **reclaim** (`--remove-label triaging` then include). Audit with `gh issue list --label triaging --state open`.
- **`--add-label triaging` 404s the label** — A.0's idempotent create should have made it; create explicitly and retry.
- **Two migration PRs touch the same doc section (§ doc overlap)** — the later sweep rebases its branch on the merged one (`--force-with-lease` on the topic branch; never `main`). Flag the overlap at A.1 when you see another open triage migration PR.
- **`gh` 403 on close/label/assign** — wrong active account (nearyou-id needs `aditrioka`, not `adi-at-buku`). `gh auth switch --user aditrioka`.
- **Dirty / wrong-branch worktree before the migration push (E.15)** — do NOT silently stash/commit unknown state. Surface it; confirm `git rev-parse --show-toplevel` is this sweep's worktree before staging.
- **Pre-commit hook fails on a doc-migration commit** — NEVER `--no-verify`. Diagnose (usually a docs lint), fix, re-stage, create a NEW commit (do NOT amend).

## Safety

Issue closes/labels are global side-effects, and close/migration comments + migration-target docs are public (source-available repo) — no real secrets, customer PII, or speculative commercial strategy in them. Never `--no-verify`; never force-push `main` (`--force-with-lease` on a topic branch only). Always release transient `triaging` labels (E.16) — a leaked claim hides an issue from the next sweep.

## Notes

- **Never silently rewrite issue bodies** — actions are close/label/migrate, not "summarize and shrink." A verbose body is fine.
- **Concurrent sweeps partition, don't serialize** (supersedes the old "if a triage PR is open, reconcile/wait"). Same coordination shape as `/next-change`, adapted to issues-as-claim-units.
- **Stale-open is the new rot** — always run the Phase A staleness checks against current code/specs/archive; don't trust an issue's age or title.
- **Don't expand triage into implementation** — surface regular-PR scope and stop.
- **Don't merge follow-ups into OpenSpec scope without `/next-change`** (Phase D hands off; never `openspec-propose` directly).
- **Drawdown discipline** — large backlog → prioritize closes/migrations (they shrink it) over promotions; verify the post-triage open count is meaningfully lower before stopping.
- **Engineering judgment over context budget** (CLAUDE.md): don't silently compress the triage list to fit a fading window — surface tightness and offer to split into a follow-up session; don't skip issues.
- **Branch naming:** `chore/triage-follow-ups-<YYYY-MM-DD>-<session-token>` (the `<area>/<slug>` convention for non-OpenSpec changes + a per-session suffix). Not the change-name-as-branch convention (OpenSpec-only).
