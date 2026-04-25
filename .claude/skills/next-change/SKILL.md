---
name: next-change
description: Determine the next OpenSpec change to work on for nearyou-id by reading docs/, openspec/specs/, openspec/changes/archive/, and recent commits — then hand off to openspec-propose to scaffold the proposal, push it to a remote branch for auto-review (Claude as primary + qodo as complementary), and iterate on the review feedback before asking whether to proceed to apply.
---

Figure out what should be built next for nearyou-id, kick off an OpenSpec proposal for it, push it for auto-review (Claude + qodo), and iterate on the combined review feedback — all in one skill.

## Context

This project (nearyou-id) is built incrementally via OpenSpec changes. Each change follows a pattern visible in the commit history:

- `feat(<area>): <what> (V<N>)` — implementation commit
- `docs(openspec): archive <change-name> and sync specs` — change gets archived after merge

The roadmap lives in `docs/08-Roadmap-Risk.md` and `docs/09-Versions.md`. Already-shipped work is reflected in:
- `openspec/specs/` — current authoritative specs
- `openspec/changes/archive/` — completed changes

Proposals land as their own PRs (branch name = change name) and get auto-reviewed by Claude (via the `.github/workflows/claude-code-review.yml` workflow running `anthropics/claude-code-action@v1` with a CLAUDE.md-aware custom prompt) before the user decides whether to move into implementation. This skill drives that loop end-to-end.

## Steps

### Phase A — Pick the next change

1. **Gather full context in parallel** (use multiple tool calls in one message):
   - Read **every file in `docs/`** (00-README through whatever latest numbered file exists). Don't pre-filter — business/product/UX/architecture/security/ops context all feed into scope and sequencing decisions.
   - Read `openspec/project.md`
   - List `openspec/specs/` and `openspec/changes/archive/` to see what's already shipped
   - Read any in-progress change in `openspec/changes/` (anything not under `archive/`) — if one exists, that's likely the current focus, not a new proposal
   - Run `git log --oneline -20` for recent direction and the V-number sequence

2. **Identify the next change.** Cross-reference:
   - The next unshipped version (V-number) in `docs/09-Versions.md`
   - Open roadmap items in `docs/08-Roadmap-Risk.md` not yet represented in `openspec/specs/`
   - Anything in the other docs (business, product, UX, architecture, security, ops, setup) that describes planned work without a matching archived change
   - Risks or gaps called out in docs that warrant a dedicated change

   If multiple candidates exist, pick the one that's the natural next step given the dependency order implied by the roadmap and recent commits. Briefly note the runners-up so the user can redirect.

3. **Present the recommendation.** Show the user:
   - **Proposed change name** (kebab-case, descriptive — no `-v<N>` suffix, see `openspec/project.md` § Change Delivery Workflow).
   - **One-paragraph summary** of what it does and why it's next.
   - **Source** — which doc/version/roadmap item this comes from.

4. **Confirm before scaffolding.** Use `AskUserQuestion` to ask whether to proceed with this change or pick a different one. Do not skip this — the user may want to pivot. The user's answer here authorizes the rest of Phases B–D (branch, push, PR, review loop) without further confirmation.

### Phase B — Scaffold the proposal

5. **Hand off to `openspec-propose`.** Invoke the `openspec-propose` skill with the change name and a description derived from the docs. That skill will scaffold `proposal.md`, `design.md`, `specs/`, and `tasks.md`.

6. **Validate before pushing.** Run `openspec validate <change-name> --strict`. If it fails, fix the artifact(s) it flagged before proceeding. Do NOT push an invalid change.

7. **Reconciliation pass against canonical docs.** Before pushing, diff every non-trivial claim in `proposal.md` / `design.md` / `specs/**` against the canonical sources it cites.

   Procedure:
   - Build a list of every `docs/<file>` or `openspec/specs/<capability>` reference the proposal makes (grep for `docs/` and capability names).
   - Re-read the specific **sections** cited (not just skim). Pay particular attention to: schema column names, CHECK constraints, algorithms, fallback ladders, quotas/limits, default values, enum vocabularies.
   - For each non-trivial claim in the proposal (new column, new algorithm step, deferred-vs-included scope line), locate the canonical source and verify exact alignment.

   For each divergence found, classify and act:
   - **(a) Proposal is under-specified / diverges from canonical docs → fix the proposal.** Amend `proposal.md` / `design.md` / `specs/**` to match the canonical source. Re-run `openspec validate --strict`. Commit the amendment before pushing. Example: canonical docs prescribe a 4-step fallback ladder; proposal specs step 1 only → amend proposal to include all 4 steps.
   - **(b) Proposal is correct; canonical docs are stale or wrong → DO NOT rewrite docs as part of this change.** Log a follow-up item in `FOLLOW_UPS.md` at repo root (create file if it doesn't exist — see the Notes section for format). Keep the proposal as-is.
   - **(c) Ambiguous or best-practice judgment call → surface to user via `AskUserQuestion`** with the options (align proposal to docs / amend docs to proposal / hybrid compromise). Do not silently decide.

   **Target: zero silent divergence at push time.** If you catch yourself thinking "close enough, ship it," re-read this step.

   **Why this exists:** without an explicit reconciliation pass, proposals shipped fast tend to skim canonical docs once, write spec based on skim, and accumulate silent divergence that only surfaces mid-implementation (see PRs [#18](https://github.com/aditrioka/nearyou-id/pull/18) / [#19](https://github.com/aditrioka/nearyou-id/pull/19) — the `global-timeline` divergence incident that motivated this step — and PR [#24](https://github.com/aditrioka/nearyou-id/pull/24), the v10 notifications spillover audit that followed).

### Phase C — Push for auto-review

8. **Create the feature branch.** Starting from `main` (if there's other untracked/uncommitted work in the tree that isn't the proposal, ask the user what to do — do NOT silently stash or commit unknown state):
   ```bash
   git checkout main && git pull --ff-only
   git checkout -b <change-name>
   ```
   Branch name MUST equal the change name per `openspec/project.md` § Change Delivery Workflow.

9. **Commit only the proposal directory.**
   ```bash
   git add openspec/changes/<change-name>/
   ```
   Do NOT `git add -A`. Verify `git status` shows only the proposal files staged. If other files are unexpectedly staged, unstage them and surface the surprise to the user.

   Commit message: `docs(openspec): propose <change-name>` with a short body summarizing what the change will add (1–3 sentences, derived from `proposal.md` § Why + § What Changes).

10. **Push + open the PR.**
   ```bash
   git push -u origin <change-name>
   gh pr create --title "docs(openspec): propose <change-name>" --body "$(cat <<'EOF'
   ## Summary
   <one-paragraph summary from proposal.md § Why + § What Changes>

   ## Artifacts
   - `openspec/changes/<change-name>/proposal.md`
   - `openspec/changes/<change-name>/design.md`
   - `openspec/changes/<change-name>/specs/**`
   - `openspec/changes/<change-name>/tasks.md`

   ## Capabilities
   - **New:** <list from proposal>
   - **Modified:** <list from proposal>

   ## Review
   Proposal-only PR. Qodo review feedback is triaged in-session: safe-apply nits land as follow-up commits on this branch; scope-level feedback is surfaced to the user before merging.

   🤖 Generated with [Claude Code](https://claude.com/claude-code)
   EOF
   )"
   ```
   Capture the PR number + URL from the `gh pr create` output for subsequent steps.

### Phase D — Iterate on auto-reviews

Two reviewers run in parallel and are treated as **complementary**, not competing:

- **Claude** (primary) — runs via `.github/workflows/claude-code-review.yml` (`anthropics/claude-code-action@v1`, custom prompt per `code.claude.com/docs/en/github-actions`, `--max-turns 5`, `timeout-minutes: 10`). Posts **one consolidated review comment** as `claude`. Typical: <$2/PR, 1–5 min wall-clock.
- **qodo** (complementary) — runs via the qodo GitHub App (no workflow file on our side; installed at repo level). Posts as `qodo-code-review` with structured inline-style comments + top-level summary. Typical: <1 min wall-clock. Quota-capped on the free tier.

Wait strategy: give qodo **2 minutes** grace; if no qodo output by then, proceed with whatever's available (typically Claude). Claude has the longer wait window because we control it and it's the primary signal.

11. **Wait for review comments from both reviewers.** Schedule a `ScheduleWakeup` with `delaySeconds: 120` to give qodo its 2-min window (and start Claude's clock). On wake-up, poll:
    ```bash
    gh pr view <pr-number> --json comments,reviews \
      --jq '{claude: {
               comments: [.comments[] | select(.author.login == "claude")],
               reviews:  [.reviews[]  | select(.author.login == "claude")]
             },
             qodo: {
               comments: [.comments[] | select(.author.login | test("qodo"; "i"))],
               reviews:  [.reviews[]  | select(.author.login | test("qodo"; "i"))]
             }}'
    ```
    Decision ladder:
    - **qodo present + claude present** → proceed to step 12 with both.
    - **qodo present + claude absent** → keep polling for claude (up to 12 min total). Proceed alone once claude lands or times out.
    - **qodo absent + claude present** → 2-min qodo grace has started (or elapsed); if 2 min since push has passed, proceed with claude alone; else `ScheduleWakeup 60s`.
    - **Both absent** → `ScheduleWakeup 90s` (up to 3 reschedules). If still absent after 12 min total, fetch `gh run list --workflow "Claude Code Review" --limit 1 --json status,conclusion` — if run failed or completed-with-0-comments, tell the user and ask whether to proceed or debug.

    Do NOT poll in a tight loop. Use `ScheduleWakeup` between checks. Each wake-up, only run the single `gh pr view` above.

12. **Read both reviewers' outputs.** Each produces a different shape:
    - **Claude comment** — one free-form markdown body grouped by severity (bug / invariant / suggestion / question).
    - **qodo review** — top-level summary + structured inline suggestions (with severity pills, often `🐞 Bug` / `📘 Rule violation` / `📎 Requirement gap`).
    
    Build a **merged findings list**:
    - Tag each finding with its source (`claude` or `qodo`).
    - Deduplicate: if both flag the same file:line with overlapping meaning, keep one with both sources listed.
    - Classify by severity: **blocking** (bug / invariant violation / rule violation / incorrectness) vs **non-blocking** (suggestion / nit / question / style).
    - If either reviewer explicitly says "no material findings" / "LGTM," note that but still process the other's findings.
    
    You cannot programmatically auto-apply feedback from either (Claude's is prose; qodo's inline comments need manual patch per suggestion). Instead, you judge + surface to user.

13. **Present findings to user via `AskUserQuestion`.** Show a concise digest (1–2 sentences per finding, citing `file:line` when present) grouped by blocking vs non-blocking. Options:
    - **Apply the blocking fixes myself, keep non-blocking as-is (Recommended)** — you (Claude, the skill author) attempt the blocking fixes: edit the proposal/design/specs/tasks files, re-run `openspec validate --strict`, commit + push. Then ask the user again with the same question after the next review round.
    - **Apply all findings (blocking + non-blocking)** — same as above but address non-blocking too.
    - **Ignore the review and hand off to `/opsx:apply`** — skip fixes, proceed to implementation. Record the skipped findings in the PR description so they're visible later.
    - **Pause — I'll review the PR myself** — stop here; user will re-invoke `/opsx:apply` or `/next-change` when ready.

14. **On "apply" options: edit → validate → commit → push → loop.** If the user selected an apply option, make the edits, run `openspec validate <change-name> --strict`, commit with `docs(openspec): apply Claude review feedback to <change-name>` (list the fixes in the commit body), and push. The push re-triggers Claude auto-review — loop back to step 11.

    Cap the loop at **2 iterations total**. If Claude keeps surfacing new blocking findings after round 2, stop and ask the user to triage — recurring findings usually signal scope confusion that the skill can't resolve autonomously.

### Phase E — Hand off

15. **After the review loop settles (no new blocking findings, or user chose to stop iterating):**
    - If the user chose to proceed: remind them to run `/opsx:apply` (or ask whether to invoke it now via `AskUserQuestion`).
    - If the user chose to pause: report the PR URL, list any non-blocking findings still unaddressed, and stop.

## Notes

- Don't invent work that isn't grounded in `docs/` or the roadmap.
- If the docs are ambiguous about what's next, surface that to the user rather than guessing.
- Commits tell you what *just* shipped — useful for ordering, not for deciding scope. Scope comes from `docs/`.
- **Only propose an OpenSpec change if it's spec-driven** — capability + behavior + WHEN/THEN scenarios. If the candidate is pure infra / tooling / CI / docs, recommend a regular PR instead and don't hand off to `openspec-propose`, and skip Phases B–E entirely.
- **Feat vs archive PRs.** This skill handles only the proposal PR (docs-only, spec-driven). Implementation (feat commits) and archiving are separate PRs per `openspec/project.md` § Change Delivery Workflow — do NOT land feat commits or run `openspec archive` on this branch.
- **Don't `--no-verify` or skip hooks.** If pre-commit hooks fail, diagnose and fix the underlying issue. The `main` branch has a direct-push hook-block; feature branches are fine.
- **Don't force-push.** Every push in this skill is either the initial push (step 10) or a new commit on top of the branch (step 14 during the review loop). `--force-with-lease` is only appropriate if rewriting already-pushed history, which this skill never does.
- **Out-of-scope findings during any step** (especially Phase B.5 reconciliation) go to `FOLLOW_UPS.md` at repo root. This file is transient — the convention is "delete entries when their action items are merged, delete the file itself when empty, recreate it when a new finding arises." NEVER sweep findings silently and NEVER force them into the current change's scope. If `FOLLOW_UPS.md` doesn't exist, create it with an intro blurb (preserved across recreations — same header + Format block as PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) shipped) and your first entry.
- **Stashing user work.** If Phase C step 7 finds uncommitted local changes that aren't the proposal, ask the user before stashing or committing them — do not silently stash. The untracked proposal directory is the only expected working-tree state after `openspec-propose` runs.
- **Reviewer author logins.** Step 11 polls two authors: `claude` (exact match — the Claude GitHub App via `anthropics/claude-code-action@v1`) and `qodo-code-review` / similar (regex `test("qodo"; "i")` — the qodo GitHub App). Claude = primary, CLAUDE.md-aware, longer wait window. qodo = complementary, quota-capped free tier, 2-min grace. They catch different classes of issue — treat as complementary, NOT as alternatives to one another. If a third reviewer joins, extend the query + merge logic in step 12.
