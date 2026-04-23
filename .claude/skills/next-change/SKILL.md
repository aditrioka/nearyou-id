---
name: next-change
description: Determine the next OpenSpec change to work on for nearyou-id by reading docs/, openspec/specs/, openspec/changes/archive/, and recent commits — then hand off to openspec-propose to scaffold the proposal, push it to a remote branch for qodo review, and iterate on the review feedback before asking whether to proceed to apply.
---

Figure out what should be built next for nearyou-id, kick off an OpenSpec proposal for it, push it for qodo review, and iterate on the review feedback — all in one skill.

## Context

This project (nearyou-id) is built incrementally via OpenSpec changes. Each change follows a pattern visible in the commit history:

- `feat(<area>): <what> (V<N>)` — implementation commit
- `docs(openspec): archive <change-name> and sync specs` — change gets archived after merge

The roadmap lives in `docs/08-Roadmap-Risk.md` and `docs/09-Versions.md`. Already-shipped work is reflected in:
- `openspec/specs/` — current authoritative specs
- `openspec/changes/archive/` — completed changes

Proposals land as their own PRs (branch name = change name) and get reviewed by qodo before the user decides whether to move into implementation. This skill drives that loop end-to-end.

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

### Phase C — Push for qodo review

7. **Create the feature branch.** Starting from `main` (if there's other untracked/uncommitted work in the tree that isn't the proposal, ask the user what to do — do NOT silently stash or commit unknown state):
   ```bash
   git checkout main && git pull --ff-only
   git checkout -b <change-name>
   ```
   Branch name MUST equal the change name per `openspec/project.md` § Change Delivery Workflow.

8. **Commit only the proposal directory.**
   ```bash
   git add openspec/changes/<change-name>/
   ```
   Do NOT `git add -A`. Verify `git status` shows only the proposal files staged. If other files are unexpectedly staged, unstage them and surface the surprise to the user.

   Commit message: `docs(openspec): propose <change-name>` with a short body summarizing what the change will add (1–3 sentences, derived from `proposal.md` § Why + § What Changes).

9. **Push + open the PR.**
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

### Phase D — Iterate on qodo review

10. **Wait for qodo review.** Qodo reviews typically post within 2–5 minutes of a push. Use `ScheduleWakeup` with `delaySeconds: 270` (cache-friendly) to pause. On wake-up, poll:
    ```bash
    gh pr view <pr-number> --json reviews,comments,reviewThreads \
      --jq '{reviews: [.reviews[] | select(.author.login | test("qodo"; "i"))],
             comments: [.comments[] | select(.author.login | test("qodo"; "i"))],
             threads:  [.reviewThreads[] | select(.comments.nodes[0].author.login | test("qodo"; "i"))]}'
    ```
    Stopping conditions:
    - Qodo has posted at least one review AND no new qodo review in the last 60 seconds → proceed.
    - No qodo review after 15 minutes total wait → tell the user qodo didn't respond and ask whether to proceed without the review, wait longer, or abort.

    Do NOT poll in a tight loop. Use `ScheduleWakeup` between checks. Each wake-up, only run the single `gh pr view` above — don't do other work.

11. **Categorize every qodo comment.** For each review comment / suggestion / inline thread, sort into one of two buckets:

    **Safe-apply** (auto-apply + resolve):
    - Typos, grammar, punctuation
    - Missing SHALL/MUST in a requirement body (spec-validation class)
    - Missing `#### Scenario:` header under a requirement
    - Clarifying wording that doesn't change intent (e.g., "A MUST be B" → "A MUST be B before C runs" where C is already established in the same spec)
    - Cross-reference fixes (wrong line number, wrong file path in a prose reference)
    - Explicit test coverage gaps called out in spec (e.g., "add scenario: …")
    - Dead markdown (empty bullets, broken list indentation)

    **Needs-user** (surface, do NOT auto-apply):
    - Scope change (add/remove a capability, widen/narrow a requirement)
    - Capability rename
    - Architectural decision reversal (e.g., qodo suggests on-read reverse-geocode where design rejected it)
    - Dataset / library / tool choice change
    - Security / privacy / compliance implication
    - Anything marked by qodo as "critical" / "major" / "blocking"
    - Anything where the fix requires a judgment call the user hasn't authorized (e.g., "consider splitting this into two changes")
    - Dependency additions (new libraries, new infra components)

    When in doubt, classify as needs-user. Don't silently make decisions the user should make.

12. **Apply all safe-apply items in one batch.** Edit the relevant proposal/design/specs/tasks files. Re-run `openspec validate <change-name> --strict` after the edits. Commit:
    ```bash
    git add openspec/changes/<change-name>/
    git commit -m "docs(openspec): apply qodo review nits to <change-name>"
    git push
    ```
    Commit message body: bulleted list of what changed (one line per qodo comment addressed, referencing the file and the nature of the fix).

13. **Resolve the safe-apply review threads.** For each thread backed by a qodo comment that you just addressed:
    ```bash
    gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -F id=<thread-id>
    ```
    Get `thread-id` values from the `reviewThreads` array of the earlier `gh pr view --json reviewThreads` call. Do NOT resolve needs-user threads — leave them open for the user to see.

14. **Surface the needs-user items + ask to proceed.** Use `AskUserQuestion` with a summary of each needs-user item (what qodo said + which file + why you didn't auto-apply) and four options:
    - **Apply all and commit (Recommended when nothing looks scope-level)** — the fixes are clearly-correct and within the authorized scope; apply them, commit, push, resolve the threads.
    - **Apply with modifications** — walk through each needs-user item with the user and apply per their call.
    - **Ignore qodo's remaining feedback and hand off to /opsx:apply** — proceed to implementation.
    - **Pause — I want to review the PR myself before deciding** — stop here; user will re-invoke `/opsx:apply` or `/next-change` when ready.

    If the user picks "Apply all" or "Apply with modifications," edit → validate → commit → push → resolve threads → loop back to step 10 once (one more qodo round is normal if substantive changes went in; if user picks "Apply all," expect one more cheap round). Cap the loop at 3 iterations total — if qodo keeps surfacing new concerns after round 3, stop and hand control to the user.

### Phase E — Hand off

15. **After the review loop settles (no new qodo threads, or user chose to stop iterating):**
    - If the user chose to proceed: remind them to run `/opsx:apply` (or ask whether to invoke it now via `AskUserQuestion`).
    - If the user chose to pause: report the PR URL, the open thread count, and stop.

## Notes

- Don't invent work that isn't grounded in `docs/` or the roadmap.
- If the docs are ambiguous about what's next, surface that to the user rather than guessing.
- Commits tell you what *just* shipped — useful for ordering, not for deciding scope. Scope comes from `docs/`.
- **Only propose an OpenSpec change if it's spec-driven** — capability + behavior + WHEN/THEN scenarios. If the candidate is pure infra / tooling / CI / docs, recommend a regular PR instead and don't hand off to `openspec-propose`, and skip Phases B–E entirely.
- **Feat vs archive PRs.** This skill handles only the proposal PR (docs-only, spec-driven). Implementation (feat commits) and archiving are separate PRs per `openspec/project.md` § Change Delivery Workflow — do NOT land feat commits or run `openspec archive` on this branch.
- **Don't `--no-verify` or skip hooks.** If pre-commit hooks fail, diagnose and fix the underlying issue. The `main` branch has a direct-push hook-block; feature branches are fine.
- **Don't force-push.** Every push in this skill is either the initial push (step 9) or a new commit on top of the branch (steps 12, 14). `--force-with-lease` is only appropriate if rewriting already-pushed history, which this skill never does.
- **Stashing user work.** If Phase C step 7 finds uncommitted local changes that aren't the proposal, ask the user before stashing or committing them — do not silently stash. The untracked proposal directory is the only expected working-tree state after `openspec-propose` runs.
- **Qodo bot name.** The grep in step 10 matches `qodo` case-insensitive. If this project ever migrates to a different review bot, update the filter rather than hard-coding a specific handle.
