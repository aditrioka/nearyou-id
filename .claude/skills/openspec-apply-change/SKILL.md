---
name: openspec-apply-change
description: Implement tasks from an OpenSpec change. Use to start implementing, continue implementation, or work through tasks. NOT for proposing a change (use /opsx:propose) or finalizing it (use /opsx:archive).
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.0"
---

Implement tasks from an OpenSpec change.

**Input**: optionally a change name. If omitted, infer from context; if vague you MUST prompt for available changes.

**Steps**

1. **Select the change.** Provided name wins; else infer from context, auto-select if only one active change exists, or `openspec list --json` + AskUserQuestion if ambiguous. Announce "Using change: <name>" + how to override (`/opsx:apply <other>`).

2. **Check status / schema:** `openspec status --change "<name>" --json` → `schemaName` + which artifact holds the tasks.

3. **Get apply instructions:** `openspec instructions apply --change "<name>" --json` → context file paths, progress, task list, dynamic instruction. Handle states: `blocked` (missing artifacts) → show message, suggest openspec-continue-change; `all_done` → congratulate, suggest archive; else implement.

4. **Read context files** (`contextFiles` from the apply instructions — vary by schema; spec-driven = proposal/specs/design/tasks).

   **nearyou-id mandatory context (beyond contextFiles):** read [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) — the architectural baseline (state/nav/data/backend contracts + Pattern Registry + Definition of Done). For `:mobile:app` UI changes, also read `openspec/specs/mobile-design-system/spec.md` and apply the `mobile-ui-foundation` checklist per screen. Implementation MUST conform to the registered patterns or amend docs/11 in the same PR — never silently introduce a second pattern for a listed concern (the anti-patchwork contract: a component built today must fit the skeleton built last month). **Tests — where/when each runs** (the CI-equivalent gate, the `!network` *not* `!database` kotest tag, dev-DB-pollution avoidance, the three checks that run only in CI): follow [`docs/13-Test-Matrix.md`](../../../docs/13-Test-Matrix.md).

   **Preflight precondition (nearyou-id).** Check the PR body for a `## Preflight` block from `/opsx:preflight` (run at `/next-change` B.5). If its **Human-required tasks** block has unacknowledged blocking items (a missing secret slot, GCP provisioning, store/dashboard config, physical-device verify), surface them to the operator and do NOT start the tasks that depend on them — implementing *around* a missing operator step is exactly the late-discovery failure the preflight exists to prevent. If no `## Preflight` block exists (e.g. apply was invoked without going through `/next-change`), run `/opsx:preflight` now before implementing.

5. **Show progress** — schema, "N/M tasks complete", remaining overview, dynamic instruction.

6. **Implement tasks (loop until done or blocked).** Per pending task:
   - **Coherence check first (nearyou-id):** before writing a new composable/component/helper, scan `ui/components/` + sibling feature packages (mobile) or the feature package + `docs/05-Implementation.md` canonical queries (backend) for an existing implementation to reuse/extend — reuse-first per docs/11 §4. Match the existing naming scheme; don't introduce synonyms for an existing role.
   - Make the minimal, focused change; mark the task `- [ ]` → `- [x]`; continue.

   **Pause if:**
   - Task unclear → ask.
   - **Implementation reveals a design issue → do NOT rationalize a revision from training-data memory.** Run a fresh dated `WebSearch` first (`"<framework> <symptom> <year> best practice"`, `"<library> <API> canonical pattern"`), read 2–3 sources weighting official docs over blogs, then suggest updating artifacts. Per `openspec/project.md` § Change Delivery Workflow "Apply-phase design-revision re-check" — pretrained "canonical pattern for X" knowledge can be 1–2 years stale. (Precedent: `shared-resources-swap-to-cmp-resources` first-attempt revision overclaimed "build-time validation eliminates runtime font failures"; fresh search disproved it + surfaced the canonical `FontFamilyResolver.preload()` + `LaunchedEffect` pattern — PR [#119](https://github.com/aditrioka/nearyou-id/pull/119).)
   - Error/blocker → report and wait.
   - User interrupts.

7. **Pre-archive staging deploy + smoke (when the change has runtime impact).** The squash-merge is a one-way door that auto-deploys from `main`; a manual branch deploy + smoke first catches deploy-config bugs (secret-slot drift, env renames, TLS scheme, eager-connect crashes — the `like-rate-limit/tasks.md` 9.7 lessons) BEFORE they ship. Mandatory when the change has runtime impact AND a smoke script exists; skip for docs-only / refactor-only.

   **Detection — run when ALL apply:** `tasks.md` has a Section 6 (or equivalent) with smoke-script refs; `dev/scripts/smoke-<change-name>.sh` exists; the change touches runtime behavior (production code, schema migrations).

   ```bash
   # 1. Trigger the staging deploy on the change branch.
   gh workflow run deploy-staging.yml --ref <change-name>
   gh run list --workflow=deploy-staging.yml --branch=<change-name> --limit=1 --json databaseId   # capture run ID

   # 2. Poll until complete (5–8 min; use ScheduleWakeup, not tight-loop; budget 600s).
   gh run view <id> --json status,conclusion

   # 3. On SUCCESS, run the smoke (most need the staging RSA key):
   KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
     --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
     dev/scripts/smoke-<change-name>.sh <user-uuid>

   # 4. On smoke green, tick Section 6 in tasks.md, commit, push → proceed to /opsx:archive.
   # 5. On deploy/smoke FAILED, fetch logs (gh run view --log-failed), surface, propose a fix. Do NOT archive until green.
   ```

   **Common failure modes** (from `like-rate-limit` 9.7 + `reply-rate-limit`):
   - Smoke 21st request returns 201 (cap not firing) → limiter fail-softed; check `RedisRateLimiter` logs for `event=redis_connect_failed fail_soft=true`. Most likely: secret-slot uses `redis://` but Upstash needs `rediss://` (TLS).
   - `Retry-After` suspiciously low (< 60s for a daily-cap window) → same; smoke scripts SHOULD include a lower-bound guard.
   - Test fixtures insufficient (need 21 visible posts, only 11 exist) → seed via public API (`POST /api/v1/posts` with a JWT minted for the existing test author), NOT psql to staging Postgres (Supabase is IPv6-only; only Cloud Run has both stacks).

   **Skip cleanly when N/A** (docs-only, refactor-only) → mark Section 6 N/A with a one-line rationale in the archive commit. Don't trigger a no-op deploy.

7.5. **Manual verification gate (nearyou-id — MANDATORY for UI-affecting changes).** "Tests green" alone is NOT done for UI — build-green-but-broken-on-device is this project's recurring failure mode (insets, navigation feel, loading-state flicker, platform-actual gaps, font/resource crashes that unit/Robolectric suites miss). Per docs/11 §5 #3:
   - **Touches `:mobile:app` screens / Compose UI / navigation / platform actuals** → invoke `verify-loop` BEFORE step 8: bring the app up on the Android emulator (+ iOS sim when platform actuals, resources/fonts, or nav serialization changed), drive the changed flow end-to-end, screenshot, check the `mobile-ui-foundation` checklist. Attach screenshot evidence (or a one-line summary + artifact path) to the PR body.
   - **Touches backend runtime without a smoke script** → at minimum boot locally (`KTOR_ENV=test`, verify-loop §A) and curl the changed endpoint(s); paste request/response into the PR body.
   - **Skip ONLY when the change has zero runtime surface** (docs/spec/lint-rule-only) → record "Verification: N/A (no runtime surface)" in the PR body so the waiver is explicit.

8. **Mark PR ready + final code review (nearyou-id, qodo + sub-agent).** Runs ONLY when all tasks complete AND smoke green (or N/A) AND the 7.5 gate passed or was explicitly N/A. Otherwise skip to step 9; do NOT mark ready prematurely.

   The PR has been draft since `/next-change` opened it. Qodo dashboard is Manual mode (see `next-change` § Context), so qodo did NOT auto-fire on any prior commit. Now: (a) mark ready for human reviewers, (b) invoke qodo via `/review` once against the full implementation diff, before `/opsx:archive`.

   **8.1 — Mark ready (UX) + invoke qodo via `/review`.** **Mergeable precheck FIRST (non-negotiable):** a CONFLICTING/DIRTY branch does NOT run CI lanes on push, so posting `/review` + arming a Monitor wait burns wall-clock indefinitely (precedent: PR [#116](https://github.com/aditrioka/nearyou-id/pull/116) burned ~6h idle when a sibling merged to `main` mid-implementation and left this branch conflicting on `gradle/libs.versions.toml` + `docs/09-Versions.md`).
   ```bash
   PR=$(gh pr list --head "<change-name>" --state open --json number --jq '.[0].number')

   STATE=$(gh pr view "$PR" --json mergeable,mergeStateStatus -q '"\(.mergeable) \(.mergeStateStatus)"')
   case "$STATE" in
     *CONFLICTING*|*DIRTY*)
       echo "❌ PR #$PR not mergeable ($STATE). Rebase + resolve before /review:" >&2
       echo "   git fetch origin main && git rebase origin/main; resolve; git push --force-with-lease" >&2
       echo "   # then re-invoke /opsx:apply (expect a fast-forward re-poke commit post-rebase —" >&2
       echo "   # memory feedback_ci_force_push_orphans_before)" >&2
       exit 1 ;;
     *UNKNOWN*)
       sleep 5
       STATE=$(gh pr view "$PR" --json mergeable,mergeStateStatus -q '"\(.mergeable) \(.mergeStateStatus)"')
       case "$STATE" in *CONFLICTING*|*DIRTY*) echo "❌ not mergeable ($STATE after re-check)." >&2; exit 1 ;; esac ;;
   esac

   gh pr ready "$PR"
   gh pr comment "$PR" --body "/review"
   ```
   `gh pr ready` is a UX signal only (does NOT trigger qodo — Manual mode). The `/review` comment is the actual qodo trigger (posts ~1 min later). If `gh pr ready` says already-ready, proceed silently and still post `/review` (each invocation re-reviews current state). **On `CONFLICTING` halt:** the user rebases onto main, force-pushes, re-invokes `/opsx:apply` — expect to need a fast-forward re-poke commit before CI's heavy lanes run (memory `feedback_ci_force_push_orphans_before`).

   **8.2 — Spawn sub-agent review (parallel to qodo).** Triage: trivial (single-file <100 LOC, no migration, no new public API) → ONE general-lens sub-agent. Non-trivial → FOUR parallel lens sub-agents (one message, multiple Agent calls), each with PR URL + change name + "read CLAUDE.md § Reviewing a PR before reviewing" + structured-report-under-600-words grouped by severity:
   - **general** — design coherence, scope drift from `proposal.md`, dead code, dependency-order sanity, **standards conformance (docs/11 Pattern Registry — undeclared pattern forks are blocking)**.
   - **security-and-invariant** — CLAUDE.md 16 critical invariants, allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins.
   - **code-correctness** — bugs, edge cases, races, null-handling, off-by-one, transaction boundaries, error propagation.
   - **test-coverage** — missing scenarios from `tasks.md`, untested edge cases, integration-test surface, fixture seeding.

   Sub-agents take 2–4 min; qodo posts ~1 min after `/review` — usually already done by the time sub-agents return.

   **8.3 — Collect qodo output:**
   ```bash
   gh pr view "$PR" --json comments,reviews \
     --jq '{qodo: {comments: [.comments[] | select(.author.login | test("qodo"; "i"))],
                   reviews:  [.reviews[]  | select(.author.login | test("qodo"; "i"))]}}'
   ```
   If absent, `ScheduleWakeup delaySeconds:120` and recheck (≤3 reschedules / 6 min). Still absent → proceed on sub-agent findings + add a PR-body one-liner ("qodo did not respond within timeout; review based on sub-agent dispatch only"). Do NOT poll in a tight loop.

   **8.4 — Merge findings.** Tag each by source (`sub-agent`/`qodo`); dedup same-`file:line` overlaps (keep one, list both sources); classify **blocking** (bug / invariant / incorrectness / missing required test) vs **non-blocking** (suggestion/nit/question/style). Sub-agent findings arrive as prose; qodo's inline comments need manual patching.

   **8.5 — Present via `AskUserQuestion`** (1–2 sentences per finding citing `file:line`, grouped blocking vs non-blocking):
   - **Apply blocking fixes; keep non-blocking (Recommended)** — edit, run relevant tests + `openspec validate <change-name> --strict`, commit + push. Runtime-touching → re-run smoke (step 7). **Re-post `/review`** (Manual mode = push does NOT re-trigger qodo). Loop back to 8.2.
   - **Apply all** — same, address non-blocking too.
   - **Ignore review; hand off to `/opsx:archive`** — record skipped findings in the PR body.
   - **Pause — user reviews manually.**

   **Iteration cap: 2 rounds.** On cap-hit, AskUserQuestion: stop & hand off to `/opsx:archive` (record remaining non-blocking in PR body) / surface to user for manual triage.

   **Same-PR iteration rule.** Review-feedback commits land on the existing PR (now non-draft); each round needs a fresh `/review` (Manual mode = no auto-trigger on push). Do NOT open a new PR per round — single squash-merge at end-of-lifecycle.

9. **On completion or pause, show status** — tasks completed this session, "N/M complete"; all done → suggest archive; paused → explain why and wait.

**Output On Completion**
```
## Implementation Complete
**Change:** <change-name>  **Schema:** <schema-name>  **Progress:** 7/7 ✓
**Smoke:** ✓ green (or "N/A — docs-only")
**Verification:** ✓ verified on <surface(s)> — evidence in PR body (or "N/A — no runtime surface")
**Code review:** ✓ settled (sub-agent + qodo, N blocking fixes applied / 0 blocking)
**PR:** ready-for-review (#<pr-number>)

Ready to archive — invoke `/opsx:archive`.
```

**Output On Pause** — Change/Schema/Progress + Issue Encountered + numbered options + "What would you like to do?"

**Guardrails**
- Keep going until done or blocked; always read context files first; pause (don't guess) on ambiguity, errors, or revealed design issues; keep changes minimal + scoped; update the task checkbox immediately on completion.

**Fluid workflow** — invocable anytime (before all artifacts done if tasks exist, after partial implementation, interleaved); if implementation reveals a design issue, suggest artifact updates (not phase-locked).

## Branching (nearyou-id — one PR per change lifecycle)

When invoked for a change that already has an open proposal PR (the typical post-`/next-change` case), commit + push feat work to the **existing change branch** (branch name = change name). Do NOT create a new feat branch or a new PR — the same PR carries proposal → review → feat → archive through one squash-merge.

After feat commits land: retitle the PR `docs(openspec): propose <name>` → `feat(<area>): <name>` (cosmetic; `gh pr edit <pr> --title`), and update the body to reflect the implementation (Migrations / Tests / Capabilities-shipped sections).

If the change has no open PR (apply invoked without `/next-change` — rare): branch from `main` (name = change name), commit feat work, open the unified-lifecycle PR with `--draft`. Proposal commits follow on the same branch later; step 8 marks ready + posts `/review` at end-of-implementation as normal.

Per `openspec/project.md` § Change Delivery Workflow. PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) was the first under the one-PR convention; PR [#38](https://github.com/aditrioka/nearyou-id/pull/38) codified it.

**Qodo gate is the `/review` comment, not draft state.** Qodo dashboard is **Manual only** — qodo NEVER auto-fires on PR events (opened / ready_for_review / synchronize), only on a posted `/review` (or `/describe`). `/next-change` opens with `--draft` for human UX; `/opsx:apply` keeps it draft through implementation; step 8 runs `gh pr ready` (UX) + `gh pr comment "/review"` (the trigger). Do NOT post `/review` earlier (e.g. on first feat commit) — it burns a review on a partial implementation and exhausts the 30-reviews-per-Git-org-per-month free-tier quota faster ([qodo docs](https://docs.qodo.ai/subscription-plans)).

## Safety

Feat commits go to the existing change branch only — never `main`, never a new PR, never `--no-verify`. The step-7 staging deploy hits real branch-deploy infra — gate on green before archive; seed smoke fixtures via the public API, never psql to staging Postgres. Never post `/review` prematurely (quota). The mergeable precheck (8.1) is a hard halt — don't arm a CI wait against a conflicting branch.
