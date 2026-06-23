---
name: next-change
description: Pick the next OpenSpec change for nearyou-id — surveying in-flight PRs/branches first so concurrent sessions don't pick the same thing — claim it immediately with an early draft PR, scaffold the proposal, run in-session sub-agent review, and hand off to /opsx:apply (qodo runs later, against the implementation diff only). NOT for implementing (use /opsx:apply), deferred-debt cleanup (use /triage-follow-ups), or infra/tooling/CI/docs-only work (regular PR).
---

Figure out what to build next for nearyou-id — **surveying in-flight work (open PRs + remote branches + sibling worktrees) first so concurrent `/next-change` sessions don't land on the same pick** — then **claim the pick immediately with an early draft PR** (before the slow scaffold, so the reservation is visible right away), scaffold an OpenSpec proposal onto that branch, iterate on in-session sub-agent review, and hand off to `/opsx:apply`. The PR stays draft through proposal review + implementation; qodo only fires once `/opsx:apply` step 8 posts `/review` against the full implementation diff — keeping qodo's 30-reviews-per-org-per-month free-tier quota off docs-only proposal commits.

## Context

Built incrementally via OpenSpec changes. Roadmap: [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) + [`docs/09-Versions.md`](../../../docs/09-Versions.md). Shipped work: [`openspec/specs/`](../../../openspec/specs/) (current specs) + [`openspec/changes/archive/`](../../../openspec/changes/archive/).

**Same-PR convention (canonical).** Under one-PR-per-change ([`openspec/project.md`](../../../openspec/project.md) § Change Delivery Workflow), the PR opened here carries the FULL lifecycle: proposal-review (this skill) → implementation (`/opsx:apply`) → archive (`/opsx:archive`), all on the SAME branch, squash-merged ONCE. Title evolves via `gh pr edit`. NEVER open a new PR per phase or review round.

**Parallel-session coordination (canonical).** Multiple sessions run concurrently, each in its own worktree. Without coordination they collide (both pick the same change, or two changes touching the same files that can't squash-merge independently). Open PRs + branches ARE the claim registry — zero extra infra:

- **Read claims before picking (Phase A).** Enumerate in-flight work (`gh pr list --state open`, `git fetch origin && git branch -r`, local non-archive `openspec/changes/`). Dedup; prefer a footprint disjoint from in-flight claims so it lands in parallel.
- **Register the claim right after the pick (A.5).** As soon as the user confirms (A.4), open a draft "claim" PR (branch + empty commit + `gh pr create --draft`) **before** the `openspec-propose` scaffold. This shrinks the collision window to a few seconds; it doesn't eliminate it (two sessions in the same second can both see no claim), so the A.5 re-check + Recovery "PR already exists" guard remain.
- **A claim is a reservation, not a commitment.** If abandoned (scaffold fails, user redirects, reconciliation kills scope), close the claim PR + delete the branch (Recovery § abandoned claim).
- **This relaxes validate-before-PUSH for the claim's empty commit ONLY** — it carries no proposal content. Proposal artifacts are still `--strict`-validated (B.2) before push (Phase C) and before any Phase D review.

**Review channel (canonical).** Proposal-phase review is **sub-agent-only** — `general-purpose` sub-agents invoked from this skill, CLAUDE.md-aware, catching in-session bias self-review misses (stale refs, allowlist gaps, spec/code drift). 2–4 min per dispatch.

**Qodo dashboard prerequisite.** The Qodo dashboard (https://app.qodo.ai/configurations?tab=code-review) is **Manual only** for both Code-review + PR-summary triggers — qodo NEVER auto-fires on PR events (`opened`/`ready_for_review`/`synchronize`); the only trigger is an explicit `/review` (or `/describe`) comment. So this skill needs no draft-state gating of qodo. `--draft` is still used (A.5) for human UX + belt-and-suspenders against dashboard config drift. Qodo's only OpenSpec-lifecycle invocation is the `/review` posted by `/opsx:apply` step 8 against the full implementation diff. Rationale: the free tier caps at **30 reviews per Git org per month** ([qodo docs](https://docs.qodo.ai/subscription-plans)); Manual mode + one `/review` per change keeps each change to exactly 1 review, making 30/month sustainable. (The legacy auto-Claude-review Action was retired post-PR [#36](https://github.com/aditrioka/nearyou-id/pull/36); in-session sub-agent review replaces it.)

## Steps

### Phase A — Pick the next change

**A.0 — Priority check (FIRST).** Read [`openspec/project.md`](../../../openspec/project.md) § "Mobile-First to Full-Demo Priority" (renamed 2026-06-07; the heading keeps the old "Mobile + Admin Scaffolding Priority" as an alias). **Honor whatever pick-priority it declares, as written** — it is the DEFAULT source of picks and its **live menu** is the candidate list A.2 draws from. The section is re-stamped at each priority boundary and self-describes its current phase, live menu, and flip trigger — follow it, don't reconstruct a prior phase's logic from memory. (Illustrative snapshot as of 2026-06-14: priority is *Balanced — no single priority*; draw the highest-value candidate by judgment across the **three live lanes** — admin (next surfaces per docs/07 § Admin Panel; every admin-UI pick MUST consult the admin mockup board per docs/11 § 3.6), Phase 4 / premium (premium / billing / image upload), mobile follow-ups (polish + deferred) — surveying in-flight PRs first; no lane privileged. project.md is authoritative if re-stamped since.) Backend hardening picks are valid only as a real blocker (security invariant gap, pre-launch test requirement, or a dependency for prioritized work) — never the default. Override the declared priority only with explicit user-facing justification. This counters the historical drift to "whatever looks recent in the commit log."

**A.1 — Gather context in parallel** (multiple tool calls in one message):
- **Staged doc loading — maps first, sections on demand** (replaces the old "read every file in `docs/`" rule, ~100k tokens/invocation):
  - **Stage 1 — maps + small files.** Heading map `grep -nE '^#{1,3} ' docs/*.md`; read fully only `docs/00-README.md` (cross-ref map) + `docs/09-Versions.md` (version sequencing).
  - **Stage 2 — pick-relevant roadmap sections.** From `docs/08` read § Open Decisions + the phase section(s) matching A.0 + § Pre-Launch when sequencing matters (offset/limit reads via Stage-1 line numbers). Skip § Risk Register + `docs/archive/08-Shipped-Detail.md` unless a candidate touches them.
  - **Stage 3 — candidate-targeted reads.** Once A.2 narrows to ≲3 candidates, read ONLY the sections of docs/01–07/10/11 those candidates touch. `docs/11` § Pattern Registry + § 5 DoD always required for product changes.
  - **Escape hatch:** genuine ambiguity about where a topic lives → read the whole file (correctness beats budget). B.3's reconciliation reads are never skipped.
- Read [`openspec/project.md`](../../../openspec/project.md).
- List [`openspec/specs/`](../../../openspec/specs/) + [`openspec/changes/archive/`](../../../openspec/changes/archive/).
- Read any in-progress change in [`openspec/changes/`](../../../openspec/changes/) (non-archive) — likely the current focus, not a new proposal.
- **Survey in-flight claims:** `gh pr list --state open --json number,title,headRefName,isDraft,createdAt`, `git fetch origin --quiet && git branch -r`, AND `git worktree list` — unpushed sibling-worktree branches are invisible to the first two (a real collision, `admin-report-queue-resolution-actions`, only surfaced via the worktree list). Treat each open PR / non-`main` remote branch / sibling-worktree branch as a reserved change name. A.2 dedups against this set.
- `git log --oneline -20` for direction + the V-number sequence.

**A.2 — Identify the next change.** Cross-reference: the next unshipped version in `docs/09`; open `docs/08` items not yet in `openspec/specs/`; planned-work signals in other docs without a matching archived change (surface via the Stage-1 heading map + `grep -nE 'DESIGN|planned|deferred|not yet|Phase [0-9]' docs/*.md`, then read just those sections); risks/gaps warranting a dedicated change.

**Dedup against in-flight claims first** (from A.1's survey): if a candidate already has an open PR or a remote branch, it's claimed — drop it and take the next one. When multiple candidates remain, pick the one that's the natural next step given dependency order from the roadmap and recent commits, AND honor A.0. **Among otherwise-equal candidates, prefer the one whose expected footprint (modules, screens, and especially the next Flyway migration number) is disjoint from the in-flight claims** — that lets it squash-merge in parallel without rebase conflicts. **Exception — user-facing capabilities (per [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)):** the footprint-disjoint heuristic is for independent / infra work. For a user-facing feature (a screen, action, notification, or admin surface), prefer the *complete vertical slice* — backend wire contract + every client surface + every read-path that returns the entity — even if it overlaps an in-flight claim; coordinate the overlap rather than shipping a single-layer slice that drifts (docs/12 §2). A deliberately deferred layer is allowed only as a docs/12 §3 explicit requirement, not a silent split. If the best dependency-ordered pick DOES overlap an in-flight claim (e.g., both need the next `V<N>__*.sql`, or both edit the same screen), flag the overlap so the user can choose to sequence it behind the claim instead. Briefly note runners-up so the user can redirect.

**A.3 — Present the recommendation:** proposed change name (kebab-case, descriptive — no `-v<N>` suffix); one-paragraph summary (what + why next); source (doc/version/roadmap item); in-flight claims (the open PRs/branches other sessions hold, and whether the pick overlaps any — lets the user redirect).

**A.4 — Confirm before scaffolding.** `AskUserQuestion`: proceed / pick a different candidate / abort (clean exit before any branch/scaffold work). Do not skip. The answer authorizes the early claim (A.5) + Phases B–E without further confirmation.

### Phase A.5 — Claim the pick (early)

Immediately after A.4 — **before** the scaffold — open a draft "claim" PR so concurrent sessions see the reservation right away. Skip this phase only on the non-OpenSpec / regular-PR path (Notes): nothing to scaffold, no window to protect.

**A.5.1 — Re-check, then branch from `main`.** Re-run the survey (`gh pr list --state open` + `git branch -r` + `git worktree list`) to catch a same-name claim from the seconds since A.1. If now claimed, STOP and surface (take a runner-up, or sequence behind) — do NOT create a duplicate. Else, from a clean `main` (unexpected uncommitted local work → ask the user; do NOT silently stash/commit):
```bash
git checkout main && git pull --ff-only
git checkout -b <change-name>   # branch name MUST equal the change name
```

**A.5.2 — Empty claim commit + push + draft PR** (a PR needs ≥1 commit ahead of `main`; the proposal lands in the next commit):
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
🚧 **Claimed — scaffolding in progress.** This draft PR reserves the change name. Proposal artifacts land in the next push; Phase C fills in the body. Do not review yet.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
The empty commit touches no code, so the pre-push gate is vacuously satisfied (the real gate runs on `/opsx:apply`'s feat pushes). Capture the PR number + URL — **Phase C finalizes this SAME PR; it does NOT create a new one.** `gh pr create` needs the correct active account (nearyou-id → `aditrioka`) — Recovery if it 403s.

**A.5.3 — If abandoned after this point** (scaffold fails unrecoverably, user redirects, reconciliation kills scope): `gh pr close <pr> --delete-branch` so it stops blocking others (Recovery § abandoned claim).

### Phase B — Scaffold the proposal

**B.1 — Hand off to `openspec-propose`** with the change name + a docs-derived description. It scaffolds `proposal.md`, `design.md`, `specs/`, `tasks.md`. Verify all four exist before continuing (Recovery § partial-output).

**B.2 — Validate before pushing:** `openspec validate <change-name> --strict`. Fix flagged artifacts; do NOT push an invalid change.

**B.3 — Reconciliation pass against canonical docs.** Diff every non-trivial claim in `proposal.md`/`design.md`/`specs/**` against the canonical sources it cites:
- Build the list of every `docs/<file>` / `openspec/specs/<capability>` reference the proposal makes.
- Re-read the cited **sections** (not skim) — schema column names, CHECK constraints, algorithms, fallback ladders, quotas/limits, defaults, enum vocabularies.
- For each non-trivial claim (new column, algorithm step, deferred-vs-included scope line), locate the canonical source and verify exact alignment.

Per divergence:
- **(a) Proposal diverges → fix the proposal.** Amend to match canonical; re-run `--strict`; commit before pushing.
- **(b) Proposal correct, docs stale → file a `follow-up` issue** (`gh issue create --label follow-up`). Do NOT rewrite docs here; keep the proposal as-is.
- **(c) Ambiguous → `AskUserQuestion`** (align proposal to docs / amend docs to proposal / hybrid). Don't silently decide.

Heuristic (a)-vs-(b): canonical doc cites a specific PR/version/spec as source-of-truth → (a); canonical doc untouched >6 months AND proposal cites recent merged work → (b); neither clear → (c). **Target: zero silent divergence at push time** — "close enough, ship it" → re-read this step. (Precedent: PRs [#18](https://github.com/aditrioka/nearyou-id/pull/18)/[#19](https://github.com/aditrioka/nearyou-id/pull/19) global-timeline divergence; [#24](https://github.com/aditrioka/nearyou-id/pull/24) v10 notifications spillover.)

**B.4 — Standards-conformance pre-check (anti-patchwork gate).** For `:mobile:app` / `:backend:ktor` changes: verify `design.md` names the [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) Pattern-Registry patterns it builds on (state holder, navigation, data layer, backend layering) and declares any deviation as an explicit Decision **plus** a `tasks.md` item amending docs/11 § Pattern Registry in the same PR. A design that silently introduces a second pattern for an already-listed concern is the patchwork failure mode this gate stops — fix before pushing. (UI look-and-feel is separately covered by `openspec/specs/mobile-design-system/spec.md` + the `mobile-ui-foundation` skill.)

### Phase B.5 — Preflight gate

After B.4, before finalizing the PR body in Phase C, run the **`openspec-preflight`** skill (`/opsx:preflight`) against the scaffolded change. It surfaces — at proposal time, not mid-`/opsx:apply` — the work that historically leaks out late as follow-ups: **human-required/operator tasks** (credentials, GCP provisioning, store/dashboard config, physical-device verify), **counterpart-layer gaps** (a user-facing capability missing its client/admin surface, or a wire field not threaded to every read-path — see [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)), and **unmapped test scenarios**. It produces a **Preflight report** block. Carry that block into the C.3 PR body (`## Preflight`), and surface any blocking entry (an undeclared single-layer slice, a silent test-scenario drop, or an unacknowledged human-required blocker) to the user via `AskUserQuestion` before handoff. The doc-reconciliation check overlaps B.3 — don't re-run it, just record that B.3 ran.

### Phase C — Push the proposal to the claim PR

The claim branch + draft PR already exist (A.5). This phase pushes the scaffolded proposal onto that SAME branch and fills in the body — it does NOT create a branch or a new PR.

**C.1 — Confirm you're on the claim branch.** `git rev-parse --abbrev-ref HEAD` MUST return `<change-name>`; if not (session resumed on `main`), `git checkout <change-name>` first. Do NOT create a new branch.

**C.2 — Commit only the proposal directory.**
```bash
git add openspec/changes/<change-name>/
```
Do NOT `git add -A`. Verify `git status` shows only the proposal files staged; if others are staged, unstage + surface the surprise. Commit message: `docs(openspec): propose <change-name>` with a 1–3-sentence body from `proposal.md` § Why + § What Changes. (Follows the empty claim commit; both squash into one.)

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
**Draft PR — proposal phase.** Stays draft through proposal review + implementation. `/opsx:apply` marks it ready at end-of-implementation. qodo fires when `/opsx:apply` step 8 posts `/review` (Qodo Manual mode — see Context).

## Review
Proposal-phase review is sub-agent-only (in-session, CLAUDE.md-aware). Safe-apply nits land as follow-up commits on this branch; scope-level feedback is surfaced to the user before handoff to `/opsx:apply`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
`git push` (no `-u` — A.5 set upstream) updates the existing PR; `gh pr edit <pr>` replaces the A.5 placeholder body. Title stays `docs(openspec): propose <change-name>` (A.5); `/opsx:apply` retitles to `feat(<area>): <name>` when implementation begins. Draft state + Manual-mode qodo unchanged (no `/review` yet).

### Phase D — Iterate on sub-agent review

qodo never auto-fires (Manual mode); no `/review` is posted in the proposal phase, so qodo stays silent through all iteration. Sub-agent-only.

**D.1 — Spawn sub-agent review.** Triage: **trivial** (≤1 `### Requirement:` ADDED, no schema migration, no new algorithm, no security surface) → ONE general-lens sub-agent. **Non-trivial** (anything else, or when uncertain) → FOUR parallel lens sub-agents (one message, multiple Agent calls), each with PR URL + change name + "read CLAUDE.md § Reviewing a PR before reviewing" + structured-report-under-600-words grouped by severity:
- **general** — overall design coherence, scope creep, missing docs, dependency-order sanity, **standards conformance: undeclared deviations from `docs/11-Engineering-Standards.md` (Pattern Registry) are blocking findings**, **and cross-layer cohesion ([`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md)): an undeclared single-layer slice of a user-facing capability — a backend with no client/admin surface, or a wire field not threaded to every read-path — is a blocking finding unless the deferred layer is declared as a docs/12 §3 requirement**.
- **security-and-invariant** — CLAUDE.md critical-invariants list, allowlist gaps, RLS, rate-limit math, secret reads, block/shadow-ban joins.
- **OpenSpec format-and-correctness** — `### Requirement:` headers, ADDED/MODIFIED/REMOVED deltas, `#### Scenario:` WHEN/THEN coverage, `tasks.md` checkbox format, `--strict` surface.
- **test-coverage** — missing scenarios, untested edge cases, integration-test surface.

Each lens catches what others miss (PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1: security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't).

**Round 2 regression scan (optional, after round-1 fixes pushed)** — ONE sub-agent: "did the round-1 fixes introduce orphan refs or break previously-correct scenarios?" (PR #37 round 2 surfaced 6 stale refs the round-1 sweep missed.)

**Security Guidance plugin — supplemental (optional).** For proposals touching authentication, RLS, rate-limiting, push attestation, secrets, encryption, content moderation, or any of the 16 critical invariants: after the multi-lens dispatch, additionally run the Anthropic Security Guidance plugin's review (`/security-review`) on the proposal PR — it scans generic OWASP/web-security-class concerns (injection, crypto misuse, secret leakage, authn/authz misconfig), **complementing** the project-specific `security-and-invariant` lens. Skip for non-security-touching proposals (substrate swaps, typography, refactors, docs-only, format-only).

**D.2 — Read findings.** Classify by severity (**blocking** = bug/invariant/rule violation/incorrectness vs **non-blocking** = suggestion/nit/question/style); dedup same-`file:line` overlaps across lenses; note any "LGTM" but still process the other lenses.

**D.3 — Present via `AskUserQuestion`** (1–2 sentences per finding citing `file:line`, grouped blocking vs non-blocking):
- **Apply blocking fixes; keep non-blocking (Recommended)** — edit artifacts, re-run `--strict`, commit + push, loop to D.1.
- **Apply all** — same + non-blocking.
- **Ignore review; hand off to `/opsx:apply`** — record skipped findings in PR description.
- **Pause — user reviews manually.**

**D.4 — On "apply": edit → validate → commit → push → loop.** Commit `docs(openspec): apply review feedback to <change-name>` (list fixes in body), push to the SAME branch. Loop to D.1.

**Same-PR iteration rule.** New commits land on the existing PR — no new PR per round (PR #37 carried 3 proposal-review commits without title/body change).

**Iteration cap: 2 rounds.** On cap-hit, `AskUserQuestion`: stop & hand off to `/opsx:apply` (record remaining non-blocking in PR body) / abandon this proposal (close PR, pick a different change) / promote to `/openspec-explore` (recurring findings signal scope confusion better handled in explore mode).

### Phase E — Hand off

**E.1 — After the review loop settles** (no new blocking findings, or user chose to stop):
- **User chose to proceed**: if the B.5 Preflight **Human-required tasks** block is non-empty, surface it and confirm the operator will action the blocking items before implementation reaches the dependent tasks (`/opsx:apply` re-checks this block as a precondition). Then remind them to run `/opsx:apply` (or offer to invoke it now via `AskUserQuestion`). `/opsx:apply` lands feat commits on the SAME branch (see Context § same-PR convention). Do NOT merge the proposal PR before implementation — under one-PR-per-change the PR stays open through proposal-review + implementation + archive, and squash-merges once at end-of-lifecycle. PR title typically retitled via `gh pr edit <pr> --title 'feat(<area>): <name>'` when implementation begins.
- **User chose to pause**: report the PR URL, list any non-blocking findings still unaddressed, and stop. PR stays open at the current commit; future `/opsx:apply` / `/opsx:archive` invocations push to this branch.

## Recovery from common failures

- **`openspec-propose` returns partial output** — verify all four artifacts exist before B.2; re-invoke `openspec-propose` for the missing one rather than improvising it inline.
- **`git push` fails (network/auth)** — surface the full error; do NOT retry blindly. Usual cause: SSH key not loaded or `gh` auth expired → ask the user to `ssh-add` / `gh auth refresh`.
- **A.1/A.5 pre-check shows the candidate already claimed** — the coordination guard working. Drop it, take the next dependency-ordered runner-up (note the redirect). Cheaper than a push-time collision.
- **`gh pr create --draft` (A.5) fails — PR/branch already exists** — a parallel session claimed it in the A.1→A.5 window, or you re-invoked on an in-flight change. `gh pr view <change-name>` to confirm. Do NOT force-create; surface + take a runner-up (or sequence behind).
- **`gh pr create` 403s ("must be a collaborator")** — wrong active account (nearyou-id needs `aditrioka`, not `adi-at-buku`). `gh auth switch` to `aditrioka` and retry.
- **Abandoned claim cleanup** — pick dropped after A.5 opened the claim PR → `gh pr close <pr> --delete-branch`. Don't leave an empty-commit claim PR open; other sessions treat it as a live reservation.
- **Qodo posts on the proposal PR unexpectedly** (auto-review, not from `/review`) — the dashboard config likely drifted from "Manual only" to "Published PRs". Verify both triggers are still Manual; flip back. `--draft` is belt-and-suspenders for the "Published PRs" case (drafts skipped) but not an "All PRs incl. draft" mode. If a quota review was already burned on proposal markdown, accept it (one-shot) and continue Phase D.
- **Pre-commit hook fails** — NEVER `--no-verify`. Diagnose (usually ktlint/Detekt), fix, re-stage, create a NEW commit (do NOT amend).
- **Validation fails after applying review feedback** — fix the new `--strict` error before committing.

## Safety

All mutation is per-change: a claim branch + draft PR (never `main`, never a new PR per phase), never `--no-verify`, never force-push `main`. Stage only the proposal directory in C.2 (no `git add -A`). The empty claim commit is a reversible reservation — clean it up (`gh pr close --delete-branch`) if the pick is abandoned, so it stops blocking other sessions.

## Notes

- Don't invent work ungrounded in `docs/` or the roadmap. Ambiguous "what's next" → surface to the user, don't guess. Commits tell you what *just* shipped (ordering), not scope.
- **Only propose OpenSpec changes if spec-driven** (capability + behavior + WHEN/THEN). Pure infra/tooling/CI/docs → regular PR; recommend that path, skip Phases B–E.
- **Promoting a deferred follow-up? Run `/triage-follow-ups` first** — it verifies the issue is still valid (some are silently resolved), closes obsolete ones, and produces a vetted scope summary A.2 can confirm. Saves a wasted cycle.
- **Deferred-work tracking = GitHub Issues (label `follow-up`).** `FOLLOW_UPS.md` retired 2026-06-09 ([PR #206](https://github.com/aditrioka/nearyou-id/pull/206)); file via `gh issue create --label follow-up` (+ area label), not a repo file.
- **External-data dependencies need a sanity-check task.** If a candidate pulls from an external open-data source (OSM/Overpass, BPS GeoJSON, CC-BY datasets, third-party fixtures), add an explicit verification step to `tasks.md` Phase 1 BEFORE any scripting: a one-shot lookup confirming the upstream identifier matches the expected entity (e.g. `relation(304751)` returns `{name: "Indonesia", ISO3166-1: "ID"}`; pin a known kabupaten + assert `admin_level=5` for Indonesian convention). Hardcoded IDs drift. (Precedent: PR #31 landed with `area:3600304716` (an Indian relation) hardcoded; took 3 fetch cycles to find Indonesia is `area:3600304751`.)
