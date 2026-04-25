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

Proposals land as their own PRs (branch name = change name) and get reviewed before the user decides whether to move into implementation. Two review channels run in parallel: (a) the qodo GitHub App auto-posts on every PR push (zero skill effort), and (b) for self-authored proposals — i.e., almost everything this skill produces — the skill spawns a `general-purpose` sub-agent for an independent CLAUDE.md-aware pass (the auto-Claude-review GitHub Action was retired post-PR #36 because its OAuth/quota failure rate made the signal unreliable; sub-agent review runs in-session, no GitHub round-trip, and the dispatching is captured here so it's not skipped). This skill drives that loop end-to-end.

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

### Phase D — Iterate on reviews

Two review channels run in parallel and are treated as **complementary**, not competing:

- **qodo** (auto, GitHub-side) — runs via the qodo GitHub App (no workflow file on our side; installed at repo level). Posts as `qodo-code-review` with structured inline-style comments + top-level summary. Typical: <1 min wall-clock. Quota-capped on the free tier.
- **Sub-agent** (in-session, skill-driven) — for self-authored proposals (i.e., everything this skill produces), step 11 below explicitly spawns a `general-purpose` sub-agent with PR URL + CLAUDE.md context. Catches the in-session bias surface that self-review misses (stale references, allowlist gaps, spec/code drift). Per `CLAUDE.md` § Reviewing a PR §7. Typical: 2–4 min wall-clock per dispatch.

(The previous auto-Claude-review GitHub Action was retired in PR #36 — its OAuth/quota failure rate climbed to ~89% in the global-timeline ship cycle, so the signal stopped being trustworthy. Sub-agent review in-session is faster, more reliable, and lets the skill author own severity calls directly.)

11. **Spawn sub-agent review + wait for qodo.** In parallel:
    - **Sub-agent dispatch** (immediate): invoke `general-purpose` agent(s) with self-contained prompt(s) — include the PR URL, the change name, "read CLAUDE.md § Reviewing a PR before reviewing," and ask for a structured report under 600 words grouped by severity (bug / invariant / suggestion / question). Return findings to this skill's context as each agent's tool result.
      - **Trivial proposal** (one-requirement spec tweak, single doc fix, etc.): one general-lens agent is sufficient.
      - **Non-trivial proposal** (new capability + schema + algorithm changes): SHOULD dispatch **multiple lenses in parallel** — typically four: **general** / **security-and-invariant** (CLAUDE.md critical-invariants list, allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins) / **OpenSpec format-and-correctness** (`### Requirement:` headers, ADDED/MODIFIED/REMOVED deltas, `#### Scenario:` WHEN/THEN coverage, `tasks.md` checkbox format, `--strict` validation) / **test-coverage** (missing scenarios, untested edge cases, integration-test surface). Each lens catches findings the others miss; PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1 confirmed: security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't.
      - **Round 2 regression scan** (optional, after round-1 fixes are pushed): dispatch ONE sub-agent with the explicit prompt "did the round-1 fixes introduce orphan refs or break previously-correct scenarios?" PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 2 surfaced 6 stale references the round-1 sweep missed.
    - **qodo polling** (deferred): schedule a `ScheduleWakeup` with `delaySeconds: 120` to give qodo its 2-min window. On wake-up, poll:
      ```bash
      gh pr view <pr-number> --json comments,reviews \
        --jq '{qodo: {
                 comments: [.comments[] | select(.author.login | test("qodo"; "i"))],
                 reviews:  [.reviews[]  | select(.author.login | test("qodo"; "i"))]
               }}'
      ```
      If qodo absent at 120 s, `ScheduleWakeup 90s` (up to 3 reschedules). If still absent after 6 min total, proceed with sub-agent findings alone.

    Do NOT poll qodo in a tight loop. Use `ScheduleWakeup` between checks.

12. **Read both channels' outputs.** Each produces a different shape:
    - **Sub-agent report** — markdown grouped by severity, ≤600 words, in your tool-result context.
    - **qodo review** — top-level summary + structured inline suggestions (with severity pills, often `🐞 Bug` / `📘 Rule violation` / `📎 Requirement gap`) on the GitHub PR.

    Build a **merged findings list**:
    - Tag each finding with its source (`sub-agent` or `qodo`).
    - Deduplicate: if both flag the same file:line with overlapping meaning, keep one with both sources listed.
    - Classify by severity: **blocking** (bug / invariant violation / rule violation / incorrectness) vs **non-blocking** (suggestion / nit / question / style).
    - If either channel explicitly says "no material findings" / "LGTM," note that but still process the other's findings.

    Sub-agent findings are prose; qodo's inline comments need manual patch per suggestion. You judge + surface to user.

13. **Present findings to user via `AskUserQuestion`.** Show a concise digest (1–2 sentences per finding, citing `file:line` when present) grouped by blocking vs non-blocking. Options:
    - **Apply the blocking fixes myself, keep non-blocking as-is (Recommended)** — you (Claude, the skill author) attempt the blocking fixes: edit the proposal/design/specs/tasks files, re-run `openspec validate --strict`, commit + push. Then re-invoke step 11 (new sub-agent dispatch + qodo poll on the new push).
    - **Apply all findings (blocking + non-blocking)** — same as above but address non-blocking too.
    - **Ignore the review and hand off to `/opsx:apply`** — skip fixes, proceed to implementation. Record the skipped findings in the PR description so they're visible later.
    - **Pause — I'll review the PR myself** — stop here; user will re-invoke `/opsx:apply` or `/next-change` when ready.

14. **On "apply" options: edit → validate → commit → push → loop.** If the user selected an apply option, make the edits, run `openspec validate <change-name> --strict`, commit with `docs(openspec): apply review feedback to <change-name>` (list the fixes in the commit body), and push to the **same** change branch. After the push re-triggers qodo, loop back to step 11.

    **Same-PR iteration rule (full lifecycle).** New commits land on the existing change PR — do NOT open a new PR per review round, per phase, or for any reason short of a genuine new change. The PR opened by `/next-change` carries through proposal-review, implementation (`/opsx:apply`), and archive (`/opsx:archive`); it squash-merges ONCE at end-of-lifecycle. PR title evolves via `gh pr edit` as scope progresses (`docs(openspec): propose <name>` → `feat(<area>): <name>` when implementation begins → optionally one final retitle before squash). Body gets updated to reflect current state at each phase boundary. Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) carried 3 commits during the proposal-review phase (initial proposal + round-1 review feedback + round-2 sweep) without title/body change; subsequent `/opsx:apply` and `/opsx:archive` invocations push to the SAME branch.

    Cap the proposal-review loop at **2 iterations total**. If new blocking findings keep surfacing after round 2, stop and ask the user to triage — recurring findings usually signal scope confusion that the skill can't resolve autonomously.

### Phase E — Hand off

15. **After the review loop settles (no new blocking findings, or user chose to stop iterating):**
    - If the user chose to proceed: remind them to run `/opsx:apply` (or ask whether to invoke it now via `AskUserQuestion`). **CRITICAL: `/opsx:apply` lands feat commits on the SAME branch, not a new one.** Do NOT merge the proposal PR before implementation — under the one-PR-per-change convention the PR stays open through proposal-review, implementation, AND archive, and squash-merges once at end-of-lifecycle. The PR title gets retitled (typically via `gh pr edit <pr> --title 'feat(<area>): <name>'`) when implementation begins.
    - If the user chose to pause: report the PR URL, list any non-blocking findings still unaddressed, and stop. The PR stays open at the current commit; future `/opsx:apply` / `/opsx:archive` invocations will push to this branch.

## Notes

- Don't invent work that isn't grounded in `docs/` or the roadmap.
- If the docs are ambiguous about what's next, surface that to the user rather than guessing.
- Commits tell you what *just* shipped — useful for ordering, not for deciding scope. Scope comes from `docs/`.
- **Only propose an OpenSpec change if it's spec-driven** — capability + behavior + WHEN/THEN scenarios. If the candidate is pure infra / tooling / CI / docs, recommend a regular PR instead and don't hand off to `openspec-propose`, and skip Phases B–E entirely.
- **Phase boundary with `/opsx:apply` and `/opsx:archive`.** Under the one-PR-per-change convention (per `openspec/project.md` § Change Delivery Workflow + the same-PR iteration rule in step 14), this skill is responsible only for the proposal-review phase: open the PR, scaffold proposal/design/specs/tasks, drive the qodo + sub-agent review loop, iterate fixes on the same branch. When the user transitions to implementation, hand off to `/opsx:apply` — it pushes feat commits to the **same branch** (do not open a new PR) and retitles via `gh pr edit`. Final archive is `/opsx:archive` on the same branch. The PR squash-merges ONCE at end-of-lifecycle.
- **Don't `--no-verify` or skip hooks.** If pre-commit hooks fail, diagnose and fix the underlying issue. The `main` branch has a direct-push hook-block; feature branches are fine.
- **Don't force-push.** Every push in this skill is either the initial push (step 10) or a new commit on top of the branch (step 14 during the review loop). `--force-with-lease` is only appropriate if rewriting already-pushed history, which this skill never does.
- **Out-of-scope findings during any step** (especially Phase B.5 reconciliation) go to `FOLLOW_UPS.md` at repo root. This file is transient — the convention is "delete entries when their action items are merged, delete the file itself when empty, recreate it when a new finding arises." NEVER sweep findings silently and NEVER force them into the current change's scope. If `FOLLOW_UPS.md` doesn't exist, create it with an intro blurb (preserved across recreations — same header + Format block as PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) shipped) and your first entry.
- **Stashing user work.** If Phase C step 7 finds uncommitted local changes that aren't the proposal, ask the user before stashing or committing them — do not silently stash. The untracked proposal directory is the only expected working-tree state after `openspec-propose` runs.
- **Review channels.** Step 11 dispatches two complementary channels: an in-session `general-purpose` sub-agent (CLAUDE.md-aware, fast, no GitHub round-trip) and the qodo GitHub App (auto-posts as `qodo-code-review` / similar — regex `test("qodo"; "i")`). The legacy auto-Claude-review GitHub Action (`.github/workflows/claude-code-review.yml`) was retired in PR #36 — its OAuth/quota failure rate hit ~89% in the global-timeline ship cycle, so the signal stopped being trustworthy. The on-demand `claude.yml` workflow that responds to `@claude` mentions in PR comments is **kept** — that's a separate, lower-frequency channel a reviewer can manually invoke for ad-hoc Q&A on a specific point. If a third auto-reviewer joins later, extend the qodo query + merge logic in step 12.
- **External-data dependencies need a sanity-check task.** If the candidate change pulls from an external open-data source (OSM via Overpass, BPS GeoJSON, CC-BY datasets, third-party fixture files) — add an explicit verification step to `tasks.md` Phase 1 *before* any scripting. Concrete shape: a one-shot lookup that confirms the upstream identifier matches the expected entity (e.g., `relation(304751)` returns `{name: "Indonesia", ISO3166-1: "ID"}` for the OSM Indonesia area; pin a known kabupaten and assert `admin_level=5` for Indonesian convention). Hardcoded IDs in scaffolds drift over time (re-numbered relations, license changes, dataset retirements); verify each at the start of the work session, don't trust comments in old import scripts. Precedent: the global-timeline import scaffold landed with `area:3600304716` (an Indian relation) hardcoded; required 3 fetch cycles to discover Indonesia is `area:3600304751` and that DKI kotamadya live at `admin_level=5`, not `admin_level=6` as initially assumed (PR #31).
