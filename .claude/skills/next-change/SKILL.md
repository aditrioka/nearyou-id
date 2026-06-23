---
name: next-change
description: Pick the next OpenSpec change for nearyou-id — surveying in-flight PRs/branches first so concurrent sessions don't pick the same thing — claim it immediately with an early draft PR, scaffold the proposal, run in-session sub-agent review, and hand off to /opsx:apply (qodo runs later, against the implementation diff only).
---

Figure out what should be built next for nearyou-id — **surveying in-flight work (open PRs + remote branches) first so concurrent `/next-change` sessions don't land on the same pick** — then **claim the pick immediately with an early draft PR** (before the slow scaffold step, so the reservation is visible to other sessions right away), kick off an OpenSpec proposal onto that branch, iterate on in-session sub-agent review, and hand off to `/opsx:apply`. The PR stays draft through proposal review + implementation; qodo only fires once `/opsx:apply` step 8 posts `/review` as a PR comment, against the full implementation diff. This keeps qodo's 30-reviews-per-Git-org-per-month free-tier quota from being burned on docs-only proposal commits or intermediate feat commits.

## Context

This project (nearyou-id) is built incrementally via OpenSpec changes. The roadmap lives in [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) and [`docs/09-Versions.md`](../../../docs/09-Versions.md). Already-shipped work is reflected in [`openspec/specs/`](../../../openspec/specs/) (current authoritative specs) and [`openspec/changes/archive/`](../../../openspec/changes/archive/) (completed changes).

**Same-PR convention (canonical for this skill).** Under the one-PR-per-change convention from [`openspec/project.md`](../../../openspec/project.md) § Change Delivery Workflow, the PR opened by this skill carries the FULL change lifecycle: proposal-review (this skill) → implementation (`/opsx:apply`) → archive (`/opsx:archive`), all on the SAME branch, squash-merged ONCE at end-of-lifecycle. PR title evolves via `gh pr edit` as scope progresses. NEVER open a new PR per phase or per review round.

**Parallel-session coordination (canonical for this skill).** Multiple `/next-change` sessions run concurrently — each in its own git worktree (see `git worktree list`). Without coordination they collide: two sessions both run discovery, both see no in-flight PR for their candidate, and both pick the same change (or two changes that touch the same files and can't squash-merge independently). This skill coordinates claims with **zero extra infra — open PRs + branches ARE the claim registry**:

- **Read claims before picking (Phase A).** Enumerate in-flight work: `gh pr list --state open`, `git fetch origin && git branch -r`, and the local non-archive `openspec/changes/`. Dedup candidates against these; prefer a pick whose file/module/migration footprint is disjoint from in-flight claims so it can land in parallel without rebase pain.
- **Register the claim right after the pick (Phase A.5).** As soon as the user confirms the pick (A.4), open a draft "claim" PR — branch + empty commit + `gh pr create --draft` — **before** the `openspec-propose` scaffold (the long pole). This shrinks the collision window from "discovery + scaffold + validate + reconcile" (minutes) down to the few seconds between the pre-check and the claim push. It does not eliminate the window (two sessions starting in the same second can still both see no claim), so the A.5 re-check + Recovery "PR already exists" guard remain.
- **A claim is a reservation, not a commitment.** If the pick is later abandoned (scaffold fails, user redirects, reconciliation kills the scope), close the claim PR and delete the branch so it stops blocking other sessions — see Recovery § abandoned claim.
- **This relaxes validate-before-PUSH for the claim's empty commit ONLY.** The claim carries no proposal content, so there is nothing to validate at A.5. The proposal artifacts are still `openspec validate --strict`-checked in B.2 **before** they are pushed (Phase C) and **before** any Phase D review — validate-before-review is fully preserved.

**Review channel (canonical for this skill).** Proposal-phase review is sub-agent-only:

- **Sub-agent** (in-session, skill-driven) — `general-purpose` sub-agent invoked from this skill, CLAUDE.md-aware. Catches in-session bias that self-review misses (stale references, allowlist gaps, spec/code drift). 2–4 min wall-clock typical per dispatch.

**Qodo dashboard prerequisite.** The Qodo dashboard at https://app.qodo.ai/configurations?tab=code-review is configured **Manual only** for both Code review trigger + PR summary trigger. This means qodo NEVER auto-fires on PR events — neither on `opened`, `ready_for_review`, nor `synchronize`. The only way qodo posts a review is when someone explicitly posts `/review` (or `/describe` for summary) as a PR comment. This skill therefore does NOT need to gate qodo via draft state — qodo is silent by default at every PR event. The `--draft` flag is still used (Phase A.5, when the claim PR is opened) for human UX (work-in-progress signal + prevents accidental merge), and as belt-and-suspenders against future dashboard config drift back to "Published PRs" (which still skips drafts). qodo's only invocation in the OpenSpec lifecycle is the `/review` comment posted by `/opsx:apply` step 8 against the full implementation diff. Rationale: Qodo free tier caps at **30 reviews per Git organization per month** (per [their docs](https://docs.qodo.ai/subscription-plans)); Manual mode + one `/review` per change keeps each OpenSpec change to exactly 1 review at step 8, making 30/month → ~30 changes/month sustainable. The legacy auto-Claude-review Action was retired post-PR [#36](https://github.com/aditrioka/nearyou-id/pull/36); sub-agent review in-session replaces it.

## Steps

### Phase A — Pick the next change

**A.0 — Priority check (run FIRST, before anything else).** Read [`openspec/project.md`](../../../openspec/project.md) § "Mobile-First to Full-Demo Priority" (formerly "Mobile + Admin Scaffolding Priority"; same section, renamed 2026-06-07 — the heading keeps the old name as an alias so this and other cross-references resolve). **Honor whatever pick-priority that section declares, as written** — it is the DEFAULT source of picks and its **live menu** is the candidate list A.2 draws from. The section is re-stamped at each priority boundary and self-describes its current phase, live menu, and the objective trigger to flip out of it — so follow the section, don't reconstruct a prior phase's logic (e.g. a specific commit-grep trigger) from memory. As of 2026-06-14 the declared priority is *Balanced — no single priority* (the mobile-first phase completed when its flip trigger fired 2026-06-13): draw the highest-value candidate by judgment across the **three live lanes** — admin (next surfaces per docs/07 § Admin Panel; every admin-UI pick MUST consult the admin mockup board per docs/11 § 3.6), Phase 4 / premium (premium / billing / image upload), and mobile follow-ups (polish + deferred items) — surveying in-flight PRs first to dedup; no lane is privileged. (This sentence is an illustrative snapshot — the project.md section is authoritative if it has been re-stamped since.) Backend hardening picks are valid only when they're a real blocker (security invariant gap, pre-launch test requirement, or a dependency for the prioritized work) — never the default. Override the declared priority only with explicit user-facing justification. This check exists to counter the historical pattern where `/next-change` drifts to whatever looks recent in the commit log instead of following the declared priority.

**A.1 — Gather full context in parallel** (multiple tool calls in one message):

- **Staged doc loading — maps first, sections on demand** (replaces the former "read every file in `docs/`" rule, which cost ~100k tokens/invocation; restructured 2026-06-12):
  - **Stage 1 — maps + small navigation files.** Build a heading map: `grep -nE '^#{1,3} ' docs/*.md` (~6 KB). Read fully only `docs/00-README.md` (cross-reference map) and `docs/09-Versions.md` (version sequencing) — both small.
  - **Stage 2 — pick-relevant roadmap sections.** From `docs/08-Roadmap-Risk.md` read § Open Decisions + the phase section(s) matching A.0's declared priority + § Pre-Launch when sequencing matters (use the Stage-1 line numbers with offset/limit reads). Skip § Risk Register and the shipped-detail archive (`docs/archive/08-Shipped-Detail.md`) unless a candidate touches them.
  - **Stage 3 — candidate-targeted reads.** Once A.2 narrows to ≲3 candidates, read ONLY the sections of docs/01–07/10/11 those candidates touch (locate via the heading map + grep). `docs/11` § Pattern Registry + § 5 DoD are always required for product changes (small reads).
  - **Escape hatch:** if the heading map leaves genuine ambiguity about where a topic lives, read that whole file — correctness beats budget (CLAUDE.md § Engineering judgment over context budget). B.3's section-level reconciliation reads are unchanged and never skipped.
- Read [`openspec/project.md`](../../../openspec/project.md).
- List [`openspec/specs/`](../../../openspec/specs/) and [`openspec/changes/archive/`](../../../openspec/changes/archive/).
- Read any in-progress change in [`openspec/changes/`](../../../openspec/changes/) (non-archive). If one exists, that's likely the current focus, not a new proposal.
- **Survey in-flight claims from parallel sessions** (per Context § Parallel-session coordination). Run `gh pr list --state open --json number,title,headRefName,isDraft,createdAt`, `git fetch origin --quiet && git branch -r`, AND `git worktree list` — unpushed sibling-worktree branches are invisible to the first two; a real collision (`admin-report-queue-resolution-actions`) only surfaced via the worktree list. Each open PR / non-`main` remote branch / sibling-worktree branch is a change another concurrent session has already claimed — treat the branch name as a reserved change name. Build this in-flight set now; A.2 dedups against it.
- Run `git log --oneline -20` for direction and the V-number sequence.

**A.2 — Identify the next change.** Cross-reference:

- The next unshipped version (V-number) in `docs/09-Versions.md`.
- Open roadmap items in `docs/08-Roadmap-Risk.md` not yet represented in `openspec/specs/`.
- Planned-work signals in other docs (business, product, UX, architecture, security, ops, setup) without a matching archived change — surface them from the Stage-1 heading map plus a marker grep (`grep -nE 'DESIGN|planned|deferred|not yet|Phase [0-9]' docs/*.md`), then read just those sections (A.1 Stage 3).
- Risks or gaps called out in docs that warrant a dedicated change.

**Dedup against in-flight claims first** (from A.1's survey): if a candidate already has an open PR or a remote branch, it's claimed — drop it and take the next one. When multiple candidates remain, pick the one that's the natural next step given dependency order from the roadmap and recent commits, AND honor A.0. **Among otherwise-equal candidates, prefer the one whose expected footprint (modules, screens, and especially the next Flyway migration number) is disjoint from the in-flight claims** — that lets it squash-merge in parallel without rebase conflicts. **Exception — user-facing capabilities (per [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)):** the footprint-disjoint heuristic is for independent / infra work. For a user-facing feature (a screen, action, notification, or admin surface), prefer the *complete vertical slice* — backend wire contract + every client surface + every read-path that returns the entity — even if it overlaps an in-flight claim; coordinate the overlap rather than shipping a single-layer slice that drifts (docs/12 §2). A deliberately deferred layer is allowed only as a docs/12 §3 explicit requirement, not a silent split. If the best dependency-ordered pick DOES overlap an in-flight claim (e.g., both need the next `V<N>__*.sql`, or both edit the same screen), flag the overlap so the user can choose to sequence it behind the claim instead. Briefly note runners-up so the user can redirect.

**A.3 — Present the recommendation.** Show the user:

- **Proposed change name** (kebab-case, descriptive — no `-v<N>` suffix, see project.md § Change Delivery Workflow).
- **One-paragraph summary** of what it does and why it's next.
- **Source** — which doc/version/roadmap item this comes from.
- **In-flight claims** — the open PRs / branches other parallel sessions are working (from A.1), and whether the recommended pick overlaps any of them. Lets the user redirect if they know a session is mid-flight on something related.

**A.4 — Confirm before scaffolding.** Use `AskUserQuestion` with these options: proceed with this pick, pick a different candidate, or abort entirely (clean exit before any branch/scaffold work). Do not skip. The user's answer authorizes the early claim (A.5 — which opens a draft PR immediately) and the rest of Phases B–E without further confirmation.

### Phase A.5 — Claim the pick (early)

Immediately after the user confirms the pick in A.4 — **before** the `openspec-propose` scaffold — open a draft "claim" PR so concurrent sessions see the reservation right away (per Context § Parallel-session coordination). This is the half of the collision guard that A.1's pre-check can't provide on its own. Skip this phase only on the non-OpenSpec / regular-PR path (see Notes): there's nothing to scaffold, so there's no scaffold window to protect.

**A.5.1 — Re-check, then branch from `main`.** Re-run the in-flight survey one last time (`gh pr list --state open` + `git branch -r` + `git worktree list`) to catch a session that claimed the same name in the seconds since A.1. If the pick is now claimed, STOP and surface to the user (take a runner-up, or sequence behind the claim) — do NOT create a duplicate. Otherwise, from a clean `main` (if there's unexpected uncommitted local work, ask the user — do NOT silently stash or commit unknown state):

```bash
git checkout main && git pull --ff-only
git checkout -b <change-name>
```

Branch name MUST equal the change name (per project.md § Change Delivery Workflow).

**A.5.2 — Empty claim commit + push + draft PR.** The claim carries no proposal content yet (openspec-propose runs in Phase B), so reserve the branch with an empty commit — a PR needs ≥1 commit ahead of `main`:

```bash
git commit --allow-empty -m "$(cat <<'EOF'
chore(openspec): claim <change-name> (scaffolding in progress)

Reserves this change name for an in-flight /next-change session so
concurrent sessions don't pick the same thing. Proposal artifacts land
in the next commit. Do not review yet.
EOF
)"
git push -u origin <change-name>
gh pr create --draft --title "docs(openspec): propose <change-name>" --body "$(cat <<'EOF'
## Status
🚧 **Claimed — scaffolding in progress.** This draft PR reserves the change name for an in-flight `/next-change` session so concurrent sessions don't collide. Proposal artifacts (`proposal.md`, `design.md`, `specs/**`, `tasks.md`) land in the next push; Phase C fills in the body below. Do not review yet.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

The empty claim commit touches no code, so the `./gradlew ktlintCheck detekt …` pre-push gate (CLAUDE.md) has nothing to check — it's vacuously satisfied; the real gate runs on `/opsx:apply`'s feat pushes. Capture the PR number + URL — **Phase C finalizes this SAME PR; it does NOT create a new one.** The `--draft` flag is human-UX + belt-and-suspenders against Qodo dashboard drift (see Context § Qodo dashboard prerequisite); qodo stays silent regardless. `gh pr create` needs the correct active `gh` account (nearyou-id → `aditrioka`) — see Recovery if it 403s.

**A.5.3 — If the pick is abandoned after this point** (scaffold fails unrecoverably, user redirects, reconciliation kills the scope), clean up the claim so it stops blocking others: `gh pr close <pr> --delete-branch`. See Recovery § abandoned claim.

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
- **(b) Proposal is correct; docs are stale → file a `follow-up` GitHub issue.** `gh issue create --label follow-up` capturing the stale-doc reconciliation. Do NOT rewrite docs as part of this change. Keep the proposal as-is.
- **(c) Ambiguous → surface to user via `AskUserQuestion`** with options (align proposal to docs / amend docs to proposal / hybrid). Don't silently decide.

**Heuristic for (a) vs (b):**

- Canonical doc cites a specific PR/version/spec as its source-of-truth → bucket (a) (proposal is wrong).
- Canonical doc last touched >6 months ago AND proposal cites recent merged work → bucket (b) (docs are stale).
- Neither signal clear → bucket (c) (surface to user).

**Target: zero silent divergence at push time.** If you catch yourself thinking "close enough, ship it," re-read this step. Precedent for why this exists: PR [#18](https://github.com/aditrioka/nearyou-id/pull/18) / [#19](https://github.com/aditrioka/nearyou-id/pull/19) (global-timeline divergence incident), PR [#24](https://github.com/aditrioka/nearyou-id/pull/24) (v10 notifications spillover audit).

**B.4 — Standards-conformance pre-check (anti-patchwork gate).** For changes touching `:mobile:app` or `:backend:ktor`: verify `design.md` names the [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) Pattern-Registry patterns it builds on (state holder, navigation, data layer, backend layering — whichever apply) and declares any deviation as an explicit Decision **plus** a `tasks.md` item amending docs/11 § Pattern Registry in the same PR. A design that silently introduces a second pattern for an already-listed concern is the patchwork failure mode this gate exists to stop — fix the design before pushing. (UI look-and-feel conformance is separately covered by `openspec/specs/mobile-design-system/spec.md` + the `mobile-ui-foundation` skill.)

### Phase B.5 — Preflight gate

After B.4, before finalizing the PR body in Phase C, run the **`openspec-preflight`** skill (`/opsx:preflight`) against the scaffolded change. It surfaces — at proposal time, not mid-`/opsx:apply` — the work that historically leaks out late as follow-ups: **human-required/operator tasks** (credentials, GCP provisioning, store/dashboard config, physical-device verify), **counterpart-layer gaps** (a user-facing capability missing its client/admin surface, or a wire field not threaded to every read-path — see [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)), and **unmapped test scenarios**. It produces a **Preflight report** block. Carry that block into the C.3 PR body (`## Preflight`), and surface any blocking entry (an undeclared single-layer slice, a silent test-scenario drop, or an unacknowledged human-required blocker) to the user via `AskUserQuestion` before handoff. The doc-reconciliation check overlaps B.3 — don't re-run it, just record that B.3 ran.

### Phase C — Push the proposal to the claim PR

The claim branch + draft PR already exist (Phase A.5). This phase pushes the scaffolded proposal onto that SAME branch and fills in the PR body — it does NOT create a branch or a new PR.

**C.1 — Confirm you're on the claim branch.** `git rev-parse --abbrev-ref HEAD` MUST return `<change-name>`. If it doesn't (e.g., the session resumed on `main`), `git checkout <change-name>` first. Do NOT create a new branch — A.5 already made it.

**C.2 — Commit only the proposal directory.**

```bash
git add openspec/changes/<change-name>/
```

Do NOT `git add -A`. Verify `git status` shows only the proposal files staged. If other files are unexpectedly staged, unstage them and surface the surprise to the user.

Commit message: `docs(openspec): propose <change-name>` with a short body (1–3 sentences) summarizing what the change will add, derived from `proposal.md` § Why + § What Changes. (This is the proposal commit; it follows the empty claim commit from A.5 — both squash into one at end-of-lifecycle.)

**C.3 — Push + finalize the PR body.**

```bash
git push
gh pr edit <pr> --body "$(cat <<'EOF'
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

## Preflight
<paste the Preflight report from B.5: Human-required tasks · cross-layer cohesion (docs/12) · doc reconciliation (B.3) · test coverage. Operators must action any blocking Human-required item before `/opsx:apply` reaches the dependent tasks.>

## Status
**Draft PR — proposal phase.** This PR stays draft through proposal review + implementation. `/opsx:apply` marks it ready-for-review at the end of implementation. qodo's review fires when `/opsx:apply` step 8 posts `/review` as a PR comment (Qodo dashboard is Manual mode — see Context).

## Review
Proposal-phase review is sub-agent-only (in-session, CLAUDE.md-aware). Findings are triaged in-session: safe-apply nits land as follow-up commits on this branch; scope-level feedback is surfaced to the user before handoff to `/opsx:apply`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

This `git push` (no `-u`/branch needed — A.5 set the upstream) updates the existing PR; `gh pr edit <pr>` replaces the A.5 placeholder body with the real proposal summary, where `<pr>` is the number captured in A.5. The PR title stays `docs(openspec): propose <change-name>` (set in A.5); `/opsx:apply` retitles it to `feat(<area>): <name>` when implementation begins. The `--draft` state and the Manual-mode qodo gate are unchanged — qodo stays silent (no `/review` posted yet); see Context § Qodo dashboard prerequisite.

### Phase D — Iterate on sub-agent review

qodo never auto-fires (Qodo dashboard is Manual mode — see Context). No `/review` comment is posted in the proposal phase, so qodo stays silent through all proposal-review iteration regardless of how many commits land. This phase is sub-agent-only. qodo's only invocation in the lifecycle is the `/review` comment posted by `/opsx:apply` step 8 against the full implementation diff.

**D.1 — Spawn sub-agent review.**

Triage proposal complexity first:

- **Trivial**: ≤1 `### Requirement:` ADDED, no schema migration, no new algorithm, no security surface touched. → ONE general-lens sub-agent.
- **Non-trivial**: anything else, OR when uncertain. → FOUR parallel lens sub-agents.

Lens dispatch (non-trivial) — invoke `general-purpose` sub-agents in parallel (one message, multiple Agent tool calls), each with PR URL + change name + "read CLAUDE.md § Reviewing a PR before reviewing" + structured-report-under-600-words ask grouped by severity:

- **general** — overall design coherence, scope creep, missing docs, dependency-order sanity, **standards conformance: undeclared deviations from `docs/11-Engineering-Standards.md` (Pattern Registry) are blocking findings**, **and cross-layer cohesion ([`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)): an undeclared single-layer slice of a user-facing capability — a backend with no client/admin surface, or a wire field not threaded to every read-path — is a blocking finding unless the deferred layer is declared as a docs/12 §3 requirement**.
- **security-and-invariant** — CLAUDE.md critical-invariants list, allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins.
- **OpenSpec format-and-correctness** — `### Requirement:` headers, ADDED/MODIFIED/REMOVED deltas, `#### Scenario:` WHEN/THEN coverage, `tasks.md` checkbox format, `--strict` validation surface.
- **test-coverage** — missing scenarios, untested edge cases, integration-test surface.

Each lens catches findings the others miss — PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1 confirmed: security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't.

**Round 2 regression scan (optional, after round-1 fixes are pushed)** — dispatch ONE sub-agent with prompt: "did the round-1 fixes introduce orphan refs or break previously-correct scenarios?" PR #37 round 2 surfaced 6 stale references the round-1 sweep missed.

**Security Guidance plugin — supplemental for security-relevant changes (optional).** For proposals touching authentication, RLS, rate-limiting, push attestation, secrets management, encryption, content moderation, or any of the 16 critical invariants in [`CLAUDE.md`](../../../CLAUDE.md) § "Critical invariants": after the multi-lens dispatch above completes, additionally invoke the Anthropic-published Security Guidance plugin's review command (`/security-review` or equivalent) on the proposal PR. The plugin scans for generic OWASP / web-security-class concerns (injection patterns, crypto misuse, secret leakage in code / commits, common authn/authz misconfigurations) — **complements** the project-specific invariant + RLS / block-join coverage from the multi-lens `security-and-invariant` lens, not replaces it. For non-security-touching proposals (substrate swaps, typography, refactors, docs-only, format-only OpenSpec changes), the multi-lens `security-and-invariant` lens alone is sufficient — skip the plugin to avoid burning cycles on irrelevant proposals.

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

**D.4 — On "apply" options: edit → validate → commit → push → loop.** Make the edits, run `openspec validate <change-name> --strict`, commit with `docs(openspec): apply review feedback to <change-name>` (list the fixes in the commit body), push to the SAME branch. Qodo stays silent (Manual mode + no `/review` posted yet). Loop back to D.1 for another sub-agent pass.

**Same-PR iteration rule.** New commits land on the existing PR — do NOT open a new PR per review round (see Context § same-PR convention). Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) carried 3 commits during proposal-review phase (initial + round-1 feedback + round-2 sweep) without title/body change.

**Iteration cap: 2 rounds total.** On cap-hit, `AskUserQuestion` with these options:

- **Stop iterating; hand off to `/opsx:apply`** — accept remaining non-blocking findings; record them in PR body for visibility at squash-merge time. PR stays draft until `/opsx:apply` marks it ready.
- **Abandon this proposal** — close PR, pick a different change via re-invoking this skill.
- **Promote to `/openspec-explore`** — recurring findings signal scope confusion better handled in explore mode than via patch loop.

### Phase E — Hand off

**E.1 — After the review loop settles** (no new blocking findings, or user chose to stop iterating):

- **User chose to proceed**: if the B.5 Preflight **Human-required tasks** block is non-empty, surface it and confirm the operator will action the blocking items before implementation reaches the dependent tasks (`/opsx:apply` re-checks this block as a precondition). Then remind them to run `/opsx:apply` (or offer to invoke it now via `AskUserQuestion`). `/opsx:apply` lands feat commits on the SAME branch (see Context § same-PR convention). Do NOT merge the proposal PR before implementation — under one-PR-per-change the PR stays open through proposal-review + implementation + archive, and squash-merges once at end-of-lifecycle. PR title typically retitled via `gh pr edit <pr> --title 'feat(<area>): <name>'` when implementation begins.
- **User chose to pause**: report the PR URL, list any non-blocking findings still unaddressed, and stop. PR stays open at the current commit; future `/opsx:apply` / `/opsx:archive` invocations push to this branch.

## Recovery from common failures

- **`openspec-propose` returns partial output** — verify all four artifacts (`proposal.md`, `design.md`, `specs/`, `tasks.md`) exist before B.2. If any missing, re-invoke `openspec-propose` with an explicit ask for the missing artifact rather than improvising it inline.
- **`git push` fails (network / auth)** — surface the full error to the user. Do NOT retry blindly. Common cause: SSH key not loaded or `gh` auth expired; ask user to run `ssh-add` or `gh auth refresh`.
- **A.1/A.5 pre-check shows the candidate is already claimed** (open PR or remote branch with that name) — this is the parallel-coordination guard working as intended. Drop the candidate; go back to A.2 and take the next dependency-ordered runner-up (note the redirect to the user). Cheaper than discovering the collision at push time.
- **`gh pr create --draft` (A.5) fails because a PR / branch already exists for this name** — a parallel `/next-change` session claimed it in the seconds between A.1's survey and A.5's re-check, or you re-invoked the skill on an in-flight change. `gh pr view <change-name>` to confirm. Do NOT force-create. Surface to the user and take a runner-up (or sequence behind the existing claim).
- **`gh pr create` 403s ("must be a collaborator")** — the active `gh` account is wrong for this repo. nearyou-id needs the `aditrioka` account active (not `adi-at-buku`). `gh auth switch` to the right account and retry.
- **Abandoned claim cleanup (§ abandoned claim)** — if the pick is dropped after A.5 opened the claim PR (scaffold fails unrecoverably, user redirects, reconciliation kills the scope), close the claim so it stops blocking other sessions: `gh pr close <pr> --delete-branch`. Do NOT leave an empty-commit claim PR open — other sessions treat it as a live reservation and route around a change that's actually free.
- **Qodo posts on the proposal PR unexpectedly (auto-review, not in response to `/review`)** — the Qodo dashboard config at https://app.qodo.ai/configurations?tab=code-review has likely drifted from "Manual only" back to "Published PRs" (or to a mode that auto-fires on draft). Verify both Code review trigger + PR summary trigger are still "Manual only"; flip back if needed. The `--draft` flag is belt-and-suspenders for the "Published PRs" case (drafts still skipped) but not for an "All PRs incl. draft" mode. If qodo already burned a quota review on proposal markdown, accept it (one-shot) and continue Phase D — no canonical action needed beyond restoring the config.
- **Pre-commit hook fails** — NEVER `--no-verify`. Diagnose the underlying issue (usually ktlint or Detekt). Fix, re-stage, create a NEW commit (do NOT amend — per CLAUDE.md).
- **Validation fails after applying review feedback** — fix the new `--strict` error before committing. Do not push a broken validation; that defeats the iteration loop's purpose.

## Notes

- Don't invent work that isn't grounded in `docs/` or the roadmap. If docs are ambiguous about what's next, surface that to the user rather than guessing. Commits tell you what *just* shipped — useful for ordering, not for deciding scope.
- **Only propose OpenSpec changes if spec-driven** — capability + behavior + WHEN/THEN scenarios. Pure infra / tooling / CI / docs candidates go through regular PRs; recommend that path and skip Phases B–E entirely.
- **Promoting a deferred follow-up? Run `/triage-follow-ups` first.** If the user references a `follow-up` GitHub issue as the candidate, recommend triage BEFORE this skill — it verifies the issue is still valid (some are silently resolved by intervening work), closes obsolete ones, and produces a vetted scope summary that A.2 can confirm against canonical docs. Saves a wasted `/next-change` cycle if the follow-up turned out obsolete.
- **Deferred-work tracking = GitHub Issues (label `follow-up`).** The root `FOLLOW_UPS.md` file was retired 2026-06-09 ([PR #206](https://github.com/aditrioka/nearyou-id/pull/206)); deferred findings are filed as `gh issue create --label follow-up` (+ an area label: `mobile`/`backend`/`observability`/`admin`/`deferred`), NOT appended to a repo file. To log a finding mid-`/next-change`, open a `follow-up` issue rather than editing a file.
- **External-data dependencies need a sanity-check task.** If the candidate change pulls from an external open-data source (OSM via Overpass, BPS GeoJSON, CC-BY datasets, third-party fixture files), add an explicit verification step to `tasks.md` Phase 1 BEFORE any scripting. Concrete shape: a one-shot lookup that confirms the upstream identifier matches the expected entity (e.g., `relation(304751)` returns `{name: "Indonesia", ISO3166-1: "ID"}`; pin a known kabupaten and assert `admin_level=5` for Indonesian convention). Hardcoded IDs drift over time. Precedent: PR #31 global-timeline import scaffold landed with `area:3600304716` (an Indian relation) hardcoded; required 3 fetch cycles to discover Indonesia is `area:3600304751` and DKI kotamadya live at `admin_level=5`.
