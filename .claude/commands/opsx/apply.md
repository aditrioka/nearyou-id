---
name: "OPSX: Apply"
description: Implement tasks from an OpenSpec change (Experimental)
category: Workflow
tags: [workflow, artifacts, experimental]
---

Implement tasks from an OpenSpec change.

**Input**: Optionally specify a change name (e.g., `/opsx:apply add-auth`). If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **Select the change**

   If a name is provided, use it. Otherwise:
   - Infer from conversation context if the user mentioned a change
   - Auto-select if only one active change exists
   - If ambiguous, run `openspec list --json` to get available changes and use the **AskUserQuestion tool** to let the user select

   Always announce: "Using change: <name>" and how to override (e.g., `/opsx:apply <other>`).

2. **Check status to understand the schema**
   ```bash
   openspec status --change "<name>" --json
   ```
   Parse the JSON to understand:
   - `schemaName`: The workflow being used (e.g., "spec-driven")
   - Which artifact contains the tasks (typically "tasks" for spec-driven, check status for others)

3. **Get apply instructions**

   ```bash
   openspec instructions apply --change "<name>" --json
   ```

   This returns:
   - Context file paths (varies by schema)
   - Progress (total, complete, remaining)
   - Task list with status
   - Dynamic instruction based on current state

   **Handle states:**
   - If `state: "blocked"` (missing artifacts): show message, suggest re-running `/opsx:propose` to fill missing artifacts
   - If `state: "all_done"`: congratulate, suggest archive via `/opsx:archive`
   - Otherwise: proceed to implementation

4. **Read context files**

   Read the files listed in `contextFiles` from the apply instructions output.
   The files depend on the schema being used:
   - **spec-driven**: proposal, specs, design, tasks
   - Other schemas: follow the contextFiles from CLI output

5. **Show current progress**

   Display:
   - Schema being used
   - Progress: "N/M tasks complete"
   - Remaining tasks overview
   - Dynamic instruction from CLI

6. **Implement tasks (loop until done or blocked)**

   For each pending task:
   - Show which task is being worked on
   - Make the code changes required
   - Keep changes minimal and focused
   - Mark task complete in the tasks file: `- [ ]` → `- [x]`
   - Continue to next task

   **Pause if:**
   - Task is unclear → ask for clarification
   - Implementation reveals a design issue → suggest updating artifacts
   - Error or blocker encountered → report and wait for guidance
   - User interrupts

7. **Pre-archive staging deploy + smoke (nearyou-id, when the change has runtime impact)**

   The squash-merge is a one-way door that auto-deploys from `main`. To catch deploy-config bugs (secret-slot drift, env-var renames, TLS scheme, eager-connect crashes — all the lessons from `like-rate-limit/tasks.md` 9.7) BEFORE they ship, run a manual branch deploy + smoke pre-archive. Mandatory when the change has runtime impact and a smoke script exists; skip for docs-only / refactor-only changes.

   **Detection.** Run the smoke step when ALL apply:
   - `tasks.md` has a Section 6 (or equivalent) with smoke-script references, AND
   - `dev/scripts/smoke-<change-name>.sh` exists (or equivalent), AND
   - The change touches runtime behavior (production code in `backend/`, schema migrations, etc.) — pure docs/spec changes skip.

   **Sequence.**
   ```bash
   # 1. Trigger the staging deploy on the change branch.
   gh workflow run deploy-staging.yml --ref <change-name>
   gh run list --workflow=deploy-staging.yml --branch=<change-name> --limit=1 --json databaseId
   # → capture run ID

   # 2. Poll until completion (5-8 min wall-clock typical). Use ScheduleWakeup,
   #    not tight-loop polling. Budget 600s total.
   gh run view <id> --json status,conclusion

   # 3. On SUCCESS, run the smoke. Most smokes need the staging RSA key:
   KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
     --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
     dev/scripts/smoke-<change-name>.sh <user-uuid>

   # 4. On smoke green, tick Section 6 tasks in tasks.md, commit, push.
   #    Then proceed to /opsx:archive.

   # 5. On deploy or smoke FAILED, fetch logs (gh run view --log-failed),
   #    surface to user, propose a fix. Do NOT archive until green.
   ```

   **Common failure modes** (from `like-rate-limit/tasks.md` 9.7 + `reply-rate-limit` cycle):
   - Smoke 21st request returns 201 (cap not firing) → limiter likely fail-softed; check `RedisRateLimiter` connect logs for `event=redis_connect_failed fail_soft=true`. Most likely cause: secret-slot value uses `redis://` but Upstash needs `rediss://` (TLS).
   - Smoke `Retry-After` value suspiciously low (< 60s for daily-cap window) → same as above; smoke scripts SHOULD include a lower-bound guard.
   - Test fixtures insufficient (e.g., need 21 visible posts but only 11 exist) → seed via public API (`POST /api/v1/posts` with a JWT minted for the existing test author), don't bypass via psql to staging Postgres (Supabase is IPv6-only; only Cloud Run has both stacks).

   **Skip cleanly when not applicable.** If the change has no runtime impact (docs-only, refactor-only), mark Section 6 as N/A with a one-line rationale in the archive commit. Don't trigger a deploy that will silently no-op.

8. **Mark PR ready + final code review (nearyou-id, qodo + sub-agent)**

   This step runs ONLY when all tasks are complete AND smoke is green (or smoke is N/A for non-runtime changes). If implementation is still in progress or smoke failed, skip to step 9 (status display); do NOT mark the PR ready prematurely.

   The PR has been a draft since `/next-change` opened it — that suppressed qodo through proposal review + implementation. Now that the implementation is functionally done, we mark the PR ready so qodo reviews the full implementation diff once, before `/opsx:archive` runs.

   **8.1 — Mark the PR ready for review.**

   ```bash
   PR=$(gh pr list --head "<change-name>" --state open --json number --jq '.[0].number')
   gh pr ready "$PR"
   ```

   This converts draft → ready and fires qodo's `ready_for_review` event (qodo posts ~1 min later). If `gh pr ready` reports the PR is already ready (e.g., user un-drafted earlier), proceed silently — qodo already ran on the prior push.

   **8.2 — Spawn sub-agent review (parallel to qodo).**

   Triage implementation complexity:

   - **Trivial**: single-file diff <100 LOC, no schema migration, no new public API. → ONE general-lens sub-agent.
   - **Non-trivial**: anything else. → FOUR parallel lens sub-agents (one Agent message, multiple tool calls).

   Lens dispatch (non-trivial) — invoke `general-purpose` sub-agents with PR URL + change name + "read CLAUDE.md § Reviewing a PR before reviewing" + structured-report-under-600-words ask grouped by severity:

   - **general** — overall design coherence, scope drift from `proposal.md`, dead code, dependency-order sanity.
   - **security-and-invariant** — CLAUDE.md critical-invariants list (16 code-level rules), allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins.
   - **code-correctness** — bugs, edge cases, race conditions, null-handling, off-by-one, transaction boundaries, error propagation.
   - **test-coverage** — missing scenarios from `tasks.md`, untested edge cases, integration-test surface, fixture seeding correctness.

   Sub-agents typically take 2–4 min; qodo posts ~1 min after `gh pr ready`. By the time sub-agents return, qodo is usually already done.

   **8.3 — Collect qodo output.**

   After sub-agents return, check qodo:

   ```bash
   gh pr view "$PR" --json comments,reviews \
     --jq '{qodo: {
              comments: [.comments[] | select(.author.login | test("qodo"; "i"))],
              reviews:  [.reviews[]  | select(.author.login | test("qodo"; "i"))]
            }}'
   ```

   If qodo absent, `ScheduleWakeup` with `delaySeconds: 120` and recheck (up to 3 reschedules / 6 min total). If still absent after 6 min, proceed with sub-agent findings alone and add a one-liner to PR body: "qodo did not respond within timeout; review based on sub-agent dispatch only." Do NOT poll qodo in a tight loop.

   **8.4 — Merge findings.**

   - Tag each finding with its source (`sub-agent` / `qodo`).
   - Deduplicate: if both flag the same `file:line` with overlapping meaning, keep one entry with both sources listed.
   - Classify by severity: **blocking** (bug / invariant violation / incorrectness / missing required test) vs **non-blocking** (suggestion / nit / question / style).

   Sub-agent findings arrive as prose in tool-result context; qodo's inline comments need manual patching per suggestion.

   **8.5 — Present findings via `AskUserQuestion`.**

   Concise digest (1–2 sentences per finding, citing `file:line`) grouped by blocking vs non-blocking. Options:

   - **Apply blocking fixes; keep non-blocking as-is (Recommended)** — edit, run relevant tests + `openspec validate <change-name> --strict`, commit + push. If changes touch runtime, re-run smoke (step 7) before continuing. Loop back to 8.2 — push re-triggers qodo (`synchronize` event on a non-draft PR).
   - **Apply all findings (blocking + non-blocking)** — same as above, address non-blocking too.
   - **Ignore review; hand off to `/opsx:archive`** — skip fixes. Record skipped findings in PR description for visibility at squash-merge time.
   - **Pause — user reviews PR manually** — stop here; user re-invokes `/opsx:apply` or `/opsx:archive` when ready.

   **Iteration cap: 2 rounds total.** On cap-hit, `AskUserQuestion` with:

   - **Stop iterating; hand off to `/opsx:archive`** — record remaining non-blocking findings in PR body.
   - **Surface to user for manual triage** — pause; user decides.

   **Same-PR iteration rule.** Review-feedback commits land on the existing PR (now non-draft). Do NOT open a new PR per review round. Per `openspec/project.md` § Change Delivery Workflow — single squash-merge at end-of-lifecycle.

9. **On completion or pause, show status**

   Display:
   - Tasks completed this session
   - Overall progress: "N/M tasks complete"
   - If all done: suggest archive
   - If paused: explain why and wait for guidance

**Output During Implementation**

```
## Implementing: <change-name> (schema: <schema-name>)

Working on task 3/7: <task description>
[...implementation happening...]
✓ Task complete

Working on task 4/7: <task description>
[...implementation happening...]
✓ Task complete
```

**Output On Completion**

```
## Implementation Complete

**Change:** <change-name>
**Schema:** <schema-name>
**Progress:** 7/7 tasks complete ✓
**Smoke:** ✓ green (or "N/A — docs-only change")
**Code review:** ✓ settled (sub-agent + qodo, N blocking fixes applied / 0 blocking)
**PR:** ready-for-review (#<pr-number>)

### Completed This Session
- [x] Task 1
- [x] Task 2
...

All tasks complete, smoke green, review settled. Ready to archive — invoke `/opsx:archive`.
```

**Output On Pause (Issue Encountered)**

```
## Implementation Paused

**Change:** <change-name>
**Schema:** <schema-name>
**Progress:** 4/7 tasks complete

### Issue Encountered
<description of the issue>

**Options:**
1. <option 1>
2. <option 2>
3. Other approach

What would you like to do?
```

**Guardrails**
- Keep going through tasks until done or blocked
- Always read context files before starting (from the apply instructions output)
- If task is ambiguous, pause and ask before implementing
- If implementation reveals issues, pause and suggest artifact updates
- Keep code changes minimal and scoped to each task
- Update task checkbox immediately after completing each task
- Pause on errors, blockers, or unclear requirements - don't guess
- Use contextFiles from CLI output, don't assume specific file names

**Fluid Workflow Integration**

This skill supports the "actions on a change" model:

- **Can be invoked anytime**: Before all artifacts are done (if tasks exist), after partial implementation, interleaved with other actions
- **Allows artifact updates**: If implementation reveals design issues, suggest updating artifacts - not phase-locked, work fluidly

**Branching (nearyou-id project — one PR per change lifecycle)**

When this command is invoked for a change that already has an open proposal PR (the typical case after `/next-change`), commit and push feat work to the **existing change branch** — the one `/next-change` opened, branch name = change name. Do NOT create a new feat branch and do NOT open a new PR. The same PR carries proposal → review iteration → feat → archive commits through to a single squash-merge.

**PR is draft for most of /opsx:apply.** `/next-change` opens the PR with `--draft` so qodo's auto-review is suppressed during proposal-phase iteration. `/opsx:apply` keeps the PR draft through implementation — qodo still doesn't review feat commits as they land. Only at step 8 (after all tasks + smoke), this command runs `gh pr ready` to mark the PR ready-for-review, which is the single moment qodo runs against the full implementation diff. Do NOT mark the PR ready earlier (e.g., on first feat commit); doing so triggers qodo against a partial implementation and burns its free-tier quota for low-signal output.

After feat commits land (any commit — title can change early, draft state stays):
- Update the existing PR's title from `docs(openspec): propose <change-name>` to `feat(<area>): <change-name>` (or matching conventional-commit prefix). Use `gh pr edit <pr-number> --title "..."`. This is purely cosmetic; safe to run after the first feat commit.
- Update the PR body to reflect the implementation now included (add a "Migrations" / "Tests" / "Capabilities-shipped" section as appropriate). Use `gh pr edit <pr-number> --body "..."`.

If the change has no open PR yet (e.g., the user invoked apply directly without going through `/next-change`), create the change branch from `main` (branch name = change name), commit the feat work, and open the unified-lifecycle PR with `--draft` (`gh pr create --draft ...`) so qodo doesn't fire on incomplete implementation. The proposal commits will follow on the same branch when the user returns to scaffold them; step 8 marks the PR ready at end-of-implementation as normal. This bypass-`/next-change` path is rare.

Per `openspec/project.md` § Change Delivery Workflow ("Sequence per OpenSpec change — one PR carries the full lifecycle"). Pre-PR-#37 archives ran the OLD 3-PR shape; PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) was the first change to ship under the new one-PR convention. PR [#38](https://github.com/aditrioka/nearyou-id/pull/38) is the docs PR that codified the convention after the fact.

<!-- Mirror of .claude/skills/openspec-apply-change/SKILL.md § Branching — keep in sync. -->

