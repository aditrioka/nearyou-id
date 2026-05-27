---
name: next-change
description: Pick the next OpenSpec change for nearyou-id, scaffold the proposal, open a draft PR for in-session sub-agent review, and hand off to /opsx:apply (qodo runs later, against the implementation diff only).
---

Figure out what should be built next for nearyou-id, kick off an OpenSpec proposal for it, open the PR as a **draft** for in-session sub-agent review, iterate on feedback, and hand off to `/opsx:apply`. The PR stays draft through proposal review + implementation; qodo only fires once `/opsx:apply` marks the PR ready, against the full implementation diff. This keeps qodo's free-tier quota from being burned on docs-only proposal commits.

## Context

This project (nearyou-id) is built incrementally via OpenSpec changes. The roadmap lives in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) and [`docs/09-Versions.md`](../../../docs/09-Versions.md). Already-shipped work is reflected in [`openspec/specs/`](../../../openspec/specs/) (current authoritative specs) and [`openspec/changes/archive/`](../../../openspec/changes/archive/) (completed changes).

**Same-PR convention (canonical for this skill).** Under the one-PR-per-change convention from [`openspec/project.md`](../../../openspec/project.md) § Change Delivery Workflow, the PR opened by this skill carries the FULL change lifecycle: proposal-review (this skill) → implementation (`/opsx:apply`) → archive (`/opsx:archive`), all on the SAME branch, squash-merged ONCE at end-of-lifecycle. PR title evolves via `gh pr edit` as scope progresses. NEVER open a new PR per phase or per review round.

**Review channel (canonical for this skill).** Proposal-phase review is sub-agent-only:

- **Sub-agent** (in-session, skill-driven) — `general-purpose` sub-agent invoked from this skill, CLAUDE.md-aware. Catches in-session bias that self-review misses (stale references, allowlist gaps, spec/code drift). 2–4 min wall-clock typical per dispatch.

**Why no qodo here.** The PR opened by this skill is a **draft** (`gh pr create --draft`), and qodo's GitHub App skips draft PRs by default (it listens on `opened` / `ready_for_review` for non-draft state). qodo does NOT review proposal markdown — it only reviews the implementation diff, triggered when `/opsx:apply` runs `gh pr ready` at the end of implementation. Rationale: qodo's free-tier quota is precious, and its structured-code-review heuristics produce low-signal output against pure-markdown proposal commits. The legacy auto-Claude-review Action was retired post-PR [#36](https://github.com/aditrioka/nearyou-id/pull/36); sub-agent review in-session replaces it.

## Steps

### Phase A — Pick the next change

**A.0 — Phase-balance check (run FIRST, before anything else).** Read [`openspec/project.md`](../../../openspec/project.md) § Mobile + Admin Scaffolding Priority. If that section is active (verify via `git log --oneline | grep -E "(mobile-nearby-timeline-screen|admin-actions-log-viewer)"` returning fewer than 2 matches), the next-step menu listed there is the DEFAULT source of picks. Backend hardening picks are still valid only when they're real blockers (security invariant gap, pre-launch test requirement, scaffolding-work dependency). Override only with explicit user-facing justification. This check exists to counter the historical pattern where `/next-change` always picked backend because recent commits looked backend-heavy.

**A.1 — Gather full context in parallel** (multiple tool calls in one message):

- Read **every file in `docs/`** (00-README through latest numbered file). Don't pre-filter — business/product/UX/architecture/security/ops context all feed into scope and sequencing.
- Read [`openspec/project.md`](../../../openspec/project.md).
- List [`openspec/specs/`](../../../openspec/specs/) and [`openspec/changes/archive/`](../../../openspec/changes/archive/).
- Read any in-progress change in [`openspec/changes/`](../../../openspec/changes/) (non-archive). If one exists, that's likely the current focus, not a new proposal.
- Run `git log --oneline -20` for direction and the V-number sequence.

**A.2 — Identify the next change.** Cross-reference:

- The next unshipped version (V-number) in `docs/09-Versions.md`.
- Open roadmap items in `docs/08-Roadmap-Risk.md` not yet represented in `openspec/specs/`.
- Anything in other docs (business, product, UX, architecture, security, ops, setup) describing planned work without a matching archived change.
- Risks or gaps called out in docs that warrant a dedicated change.

If multiple candidates exist, pick the one that's the natural next step given dependency order from the roadmap and recent commits, AND honor A.0. Briefly note runners-up so the user can redirect.

**A.3 — Present the recommendation.** Show the user:

- **Proposed change name** (kebab-case, descriptive — no `-v<N>` suffix, see project.md § Change Delivery Workflow).
- **One-paragraph summary** of what it does and why it's next.
- **Source** — which doc/version/roadmap item this comes from.

**A.4 — Confirm before scaffolding.** Use `AskUserQuestion` with these options: proceed with this pick, pick a different candidate, or abort entirely (clean exit before any branch/scaffold work). Do not skip. The user's answer authorizes the rest of Phases B–D without further confirmation.

### Phase B — Scaffold the proposal

**B.1 — Hand off to `openspec-propose`.** Invoke the skill with the change name and a description derived from the docs. It scaffolds `proposal.md`, `design.md`, `specs/`, and `tasks.md`. Verify all four artifacts exist before continuing — see Recovery § partial-output for handling.

**B.2 — Validate before pushing.** Run `openspec validate <change-name> --strict`. If it fails, fix the flagged artifact(s) before proceeding. Do NOT push an invalid change.

**B.3 — Reconciliation pass against canonical docs.** Diff every non-trivial claim in `proposal.md` / `design.md` / `specs/**` against the canonical sources it cites.

Procedure:

- Build a list of every `docs/<file>` or `openspec/specs/<capability>` reference the proposal makes (grep for `docs/` and capability names).
- Re-read the specific **sections** cited (not just skim). Pay particular attention to: schema column names, CHECK constraints, algorithms, fallback ladders, quotas/limits, default values, enum vocabularies.
- For each non-trivial claim in the proposal (new column, new algorithm step, deferred-vs-included scope line), locate the canonical source and verify exact alignment.

For each divergence found, classify and act:

- **(a) Proposal diverges from canonical → fix the proposal.** Amend `proposal.md` / `design.md` / `specs/**` to match the canonical source. Re-run `--strict`. Commit the amendment before pushing.
- **(b) Proposal is correct; docs are stale → log to `FOLLOW_UPS.md`.** Do NOT rewrite docs as part of this change. Keep the proposal as-is.
- **(c) Ambiguous → surface to user via `AskUserQuestion`** with options (align proposal to docs / amend docs to proposal / hybrid). Don't silently decide.

**Heuristic for (a) vs (b):**

- Canonical doc cites a specific PR/version/spec as its source-of-truth → bucket (a) (proposal is wrong).
- Canonical doc last touched >6 months ago AND proposal cites recent merged work → bucket (b) (docs are stale).
- Neither signal clear → bucket (c) (surface to user).

**Target: zero silent divergence at push time.** If you catch yourself thinking "close enough, ship it," re-read this step. Precedent for why this exists: PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) / [#19](https://github.com/aditrioka/nearyou-id/pull/19) (global-timeline divergence incident), PR [#24](https://github.com/aditrioka/nearyou-id/pull/24) (v10 notifications spillover audit).

### Phase C — Push for auto-review

**C.1 — Create the feature branch.** Starting from `main` (if uncommitted local work isn't the proposal, ask the user — do NOT silently stash or commit unknown state):

```bash
git checkout main && git pull --ff-only
git checkout -b <change-name>
```

Branch name MUST equal the change name (per project.md § Change Delivery Workflow).

**C.2 — Commit only the proposal directory.**

```bash
git add openspec/changes/<change-name>/
```

Do NOT `git add -A`. Verify `git status` shows only the proposal files staged. If other files are unexpectedly staged, unstage them and surface the surprise to the user.

Commit message: `docs(openspec): propose <change-name>` with a short body (1–3 sentences) summarizing what the change will add, derived from `proposal.md` § Why + § What Changes.

**C.3 — Push + open the PR.**

```bash
git push -u origin <change-name>
gh pr create --draft --title "docs(openspec): propose <change-name>" --body "$(cat <<'EOF'
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

## Status
**Draft PR — proposal phase.** This PR stays draft through proposal review + implementation. `/opsx:apply` marks it ready-for-review at the end of implementation, which is when qodo's auto-review fires (against the full implementation diff, not proposal markdown).

## Review
Proposal-phase review is sub-agent-only (in-session, CLAUDE.md-aware). Findings are triaged in-session: safe-apply nits land as follow-up commits on this branch; scope-level feedback is surfaced to the user before handoff to `/opsx:apply`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

The `--draft` flag is load-bearing: it suppresses qodo's auto-review for the proposal phase (see Context § Review channel). Capture the PR number + URL from the `gh pr create` output for subsequent steps.

### Phase D — Iterate on sub-agent review

The PR is a draft, so qodo is suppressed and will not review proposal commits. This phase is sub-agent-only. qodo review fires later — `/opsx:apply` marks the PR ready at the end of implementation, which is when qodo first sees the change.

**D.1 — Spawn sub-agent review.**

Triage proposal complexity first:

- **Trivial**: ≤1 `### Requirement:` ADDED, no schema migration, no new algorithm, no security surface touched. → ONE general-lens sub-agent.
- **Non-trivial**: anything else, OR when uncertain. → FOUR parallel lens sub-agents.

Lens dispatch (non-trivial) — invoke `general-purpose` sub-agents in parallel (one message, multiple Agent tool calls), each with PR URL + change name + "read CLAUDE.md § Reviewing a PR before reviewing" + structured-report-under-600-words ask grouped by severity:

- **general** — overall design coherence, scope creep, missing docs, dependency-order sanity.
- **security-and-invariant** — CLAUDE.md critical-invariants list, allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins.
- **OpenSpec format-and-correctness** — `### Requirement:` headers, ADDED/MODIFIED/REMOVED deltas, `#### Scenario:` WHEN/THEN coverage, `tasks.md` checkbox format, `--strict` validation surface.
- **test-coverage** — missing scenarios, untested edge cases, integration-test surface.

Each lens catches findings the others miss — PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1 confirmed: security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't.

**Round 2 regression scan (optional, after round-1 fixes are pushed)** — dispatch ONE sub-agent with prompt: "did the round-1 fixes introduce orphan refs or break previously-correct scenarios?" PR #37 round 2 surfaced 6 stale references the round-1 sweep missed.

**D.2 — Read sub-agent findings.** Build a findings list:

- Classify by severity: **blocking** (bug / invariant violation / rule violation / incorrectness) vs **non-blocking** (suggestion / nit / question / style).
- Deduplicate findings that flag the same `file:line` with overlapping meaning across lens dispatches.
- If a lens says "LGTM / no material findings," note that but still process the other lenses' findings.

Sub-agent findings come as prose in your tool-result context. You judge severity + surface to user.

**D.3 — Present findings to user via `AskUserQuestion`.** Concise digest (1–2 sentences per finding, citing `file:line` when present) grouped by blocking vs non-blocking. Options:

- **Apply blocking fixes; keep non-blocking as-is (Recommended)** — Claude (this skill) edits artifacts, re-runs `--strict`, commits + pushes; loop back to D.1 for new sub-agent pass.
- **Apply all findings (blocking + non-blocking)** — same as above but address non-blocking too.
- **Ignore review; hand off to `/opsx:apply`** — skip fixes, proceed to implementation. Record skipped findings in PR description for visibility.
- **Pause — user reviews PR manually** — stop here; user re-invokes `/opsx:apply` or `/next-change` when ready.

**D.4 — On "apply" options: edit → validate → commit → push → loop.** Make the edits, run `openspec validate <change-name> --strict`, commit with `docs(openspec): apply review feedback to <change-name>` (list the fixes in the commit body), push to the SAME branch. The PR is still draft, so qodo will not run. Loop back to D.1 for another sub-agent pass.

**Same-PR iteration rule.** New commits land on the existing PR — do NOT open a new PR per review round (see Context § same-PR convention). Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) carried 3 commits during proposal-review phase (initial + round-1 feedback + round-2 sweep) without title/body change.

**Iteration cap: 2 rounds total.** On cap-hit, `AskUserQuestion` with these options:

- **Stop iterating; hand off to `/opsx:apply`** — accept remaining non-blocking findings; record them in PR body for visibility at squash-merge time. PR stays draft until `/opsx:apply` marks it ready.
- **Abandon this proposal** — close PR, pick a different change via re-invoking this skill.
- **Promote to `/openspec-explore`** — recurring findings signal scope confusion better handled in explore mode than via patch loop.

### Phase E — Hand off

**E.1 — After the review loop settles** (no new blocking findings, or user chose to stop iterating):

- **User chose to proceed**: remind them to run `/opsx:apply` (or offer to invoke it now via `AskUserQuestion`). `/opsx:apply` lands feat commits on the SAME branch (see Context § same-PR convention). Do NOT merge the proposal PR before implementation — under one-PR-per-change the PR stays open through proposal-review + implementation + archive, and squash-merges once at end-of-lifecycle. PR title typically retitled via `gh pr edit <pr> --title 'feat(<area>): <name>'` when implementation begins.
- **User chose to pause**: report the PR URL, list any non-blocking findings still unaddressed, and stop. PR stays open at the current commit; future `/opsx:apply` / `/opsx:archive` invocations push to this branch.

## Recovery from common failures

- **`openspec-propose` returns partial output** — verify all four artifacts (`proposal.md`, `design.md`, `specs/`, `tasks.md`) exist before B.2. If any missing, re-invoke `openspec-propose` with an explicit ask for the missing artifact rather than improvising it inline.
- **`git push` fails (network / auth)** — surface the full error to the user. Do NOT retry blindly. Common cause: SSH key not loaded or `gh` auth expired; ask user to run `ssh-add` or `gh auth refresh`.
- **`gh pr create --draft` fails because PR already exists for this branch** — `gh pr view <change-name>` to confirm. If a PR exists, you've likely re-invoked the skill on an in-flight change (or a parallel `/next-change` session beat you to it). Surface to user; do not force-create.
- **PR opened as non-draft by mistake (forgot `--draft`)** — convert immediately: `gh pr ready --undo <pr-number>`. If qodo already auto-posted a review against proposal markdown in the gap, leave it as-is (one-shot; `pull_request.synchronize` on a now-draft PR won't re-trigger) and add a one-line note to the PR body: "qodo's initial review fired against proposal markdown by accident — disregard; canonical qodo review will run when `/opsx:apply` marks the PR ready." Continue with Phase D as normal.
- **Pre-commit hook fails** — NEVER `--no-verify`. Diagnose the underlying issue (usually ktlint or Detekt). Fix, re-stage, create a NEW commit (do NOT amend — per CLAUDE.md).
- **Validation fails after applying review feedback** — fix the new `--strict` error before committing. Do not push a broken validation; that defeats the iteration loop's purpose.

## Notes

- Don't invent work that isn't grounded in `docs/` or the roadmap. If docs are ambiguous about what's next, surface that to the user rather than guessing. Commits tell you what *just* shipped — useful for ordering, not for deciding scope.
- **Only propose OpenSpec changes if spec-driven** — capability + behavior + WHEN/THEN scenarios. Pure infra / tooling / CI / docs candidates go through regular PRs; recommend that path and skip Phases B–E entirely.
- **Promoting a deferred follow-up? Run `/triage-follow-ups` first.** If the user references a `FOLLOW_UPS.md` entry as the candidate, recommend triage BEFORE this skill — it verifies the entry is still valid (some are silently resolved by intervening work), prunes obsolete entries, and produces a vetted scope summary that A.2 can confirm against canonical docs. Saves a wasted `/next-change` cycle if the follow-up turned out obsolete.
- **`FOLLOW_UPS.md` format.** This file is transient — convention is "delete entries when their action items are merged, delete the file itself when empty, recreate when a new finding arises." If it doesn't exist, create with the intro blurb + Format block from PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) and your first entry.
- **External-data dependencies need a sanity-check task.** If the candidate change pulls from an external open-data source (OSM via Overpass, BPS GeoJSON, CC-BY datasets, third-party fixture files), add an explicit verification step to `tasks.md` Phase 1 BEFORE any scripting. Concrete shape: a one-shot lookup that confirms the upstream identifier matches the expected entity (e.g., `relation(304751)` returns `{name: "Indonesia", ISO3166-1: "ID"}`; pin a known kabupaten and assert `admin_level=5` for Indonesian convention). Hardcoded IDs drift over time. Precedent: PR #31 global-timeline import scaffold landed with `area:3600304716` (an Indian relation) hardcoded; required 3 fetch cycles to discover Indonesia is `area:3600304751` and DKI kotamadya live at `admin_level=5`.
