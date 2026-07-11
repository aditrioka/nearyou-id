---
name: issue-burndown
description: Execute ONE open `follow-up` GitHub issue end-to-end — claim it, route it to the right delivery shape (regular fix/refactor PR vs OpenSpec lifecycle), implement under the docs/11 rails + verification gates, then close the issue. This is the EXECUTOR leg of the follow-up backlog: `/triage-follow-ups` classifies & cleans the backlog (never codes), `/next-change` proposes NEW capabilities (not issue-driven), and THIS skill turns one still-valid debt issue into a merged PR. Use on "/issue-burndown", "burn down a follow-up", "babat satu issue follow-up", "kerjakan issue #N", "implement follow-up #372", or any request to actually BUILD/FIX a tracked follow-up debt issue. NOT for triage/classification (use /triage-follow-ups) or net-new capabilities (use /next-change).
---

Eat the open `follow-up` backlog one issue per invocation. One issue = one branch = one PR. Never start a second issue in the same session.

This is the generic sibling of `/audit-burndown` (same "1 item → branch → PR → close the loop" shape) but sourced from the live `follow-up` issue list instead of the frozen 2026-06-10 audit menu. The three-legged model it completes:

```
/triage-follow-ups  → classify & clean the backlog (close / migrate / label). NEVER codes.
                        │ for "still-valid debt"
                        ▼
/issue-burndown     → execute ONE issue → PR → close.   ← this skill
/next-change        → propose a NEW capability (not issue-driven).
```

Without this skill the seam is broken: triage classifies a follow-up as "still-valid debt" and historically hands it to `/next-change` — the wrong tool, which proposes net-new capabilities rather than executing tracked debt. This skill is that missing executor.

## 0 — Claim survey (always, before picking)

Same discipline as `/triage-follow-ups` and `/next-change` — concurrent sessions must not double-pick. Enumerate in-flight work:

```bash
gh auth switch --user aditrioka 2>/dev/null   # nearyou-id needs aditrioka, else gh 403s
gh label create burning-down --color D93F0B --description "Transient claim: /issue-burndown is executing this issue" 2>/dev/null || true
gh pr list --state open --json number,title,headRefName,isDraft
git fetch origin --quiet && git branch -r
git worktree list                                                  # sibling worktrees = concurrent sessions
gh issue list --label follow-up --state open --limit 200 --json number,title,labels,body,assignees
```

An issue is **claimed** (skip it) if it's labeled `burning-down`, labeled `triaging` (a triage sweep owns it), assigned, or has an open linked PR / sibling-worktree branch. Work from a fresh branch off `origin/main`.

## 1 — Resolve & pick ONE issue

Pick order:

1. **Explicit issue number given as argument** (`/issue-burndown 372`) → take it (after the claim-survey + audit-dedup checks below).
2. **No argument** → default to the highest-value **unclaimed** issue labeled `ready-to-burndown` (the queue `/triage-follow-ups` populates for still-valid, code-shaped debt). Tie-break by area/impact in the body, preferring smaller, self-contained slices first.
3. **No `ready-to-burndown` issues exist** (the queue is empty — e.g. before triage has run) → do NOT re-triage the whole backlog from scratch (that's `/triage-follow-ups`' job, and the boundary that keeps this skill an executor). Instead, make the operator's explicit pick easy without classifying: scan the unclaimed `follow-up` issues for obviously code-shaped ones (body has concrete file refs / action items, not "decide whether to…"), surface a short candidate list (`#N` · slug · one-line what), and recommend either `/triage-follow-ups` (to populate the queue properly) or naming one of the shortlist to burn down now. This is a *shortlist, not triage* — it does not close, migrate, or label anything. Then stop and wait for the operator's pick.

**Audit dedup (hard boundary).** Exclude any issue still live on the 2026-06-10 audit menu — those belong to `/audit-burndown`, and double-execution wastes work. Cross-check the candidate against the "Shipped / Still open" line in [`.claude/skills/audit-burndown/SKILL.md`](../audit-burndown/SKILL.md) (read it live — its open list shrinks as items ship; don't trust a memorized snapshot). If the issue is an audit-menu item → hand back to `/audit-burndown` and pick another.

State the pick + one-line reason, then proceed (don't stop to ask).

## 2 — Re-validate before executing (the freshness gate)

A triaged issue can go stale between triage and execution — it may have silently shipped on `origin/main`, or collided with in-flight work since it was labeled. **Before writing any code**, confirm the issue is still real and still yours to do:

- **Silently resolved?** Re-run the issue's own staleness check against latest `origin/main` (the action items may already be present — grep for the prescribed file/spec change; check whether a referenced change archived). If done → close it with evidence (`gh issue close <N> --reason completed --comment "Resolved by <file:line / PR #>. (issue-burndown <date>)"`); do NOT open an empty PR. Pick another or stop.
- **Collided with in-flight work?** Diff `<branch-base>..origin/main` and scan open PRs for overlap (precedent: an item gets folded into a sibling's sweep after triage labeled it — e.g. a VM `stateIn` fold absorbed by a broader sweep PR). If blocked/overlapping → un-claim, surface to the operator, recommend re-triage or sequencing behind the claim. Do NOT force-code into a collision.

Only a still-valid, un-blocked issue proceeds to step 3. **When stale or blocked, throw it back — don't manufacture work.**

Once validated, claim it: `gh issue edit <N> --add-label burning-down`, then re-read (`gh issue view <N> --json labels,assignees`) to confirm no competing claim landed in the window — if one did, drop it and report "deferred to concurrent session."

## 3 — Route by shape

Read the issue body's Finding / action items and classify:

- **bug / refactor / docs / CI / lint / infra** → regular PR. Branch `fix/<slug>` or `refactor/<slug>` (the `<area>/<slug>` convention). PR body ends with `Closes #N` (repeat the keyword per issue if it closes several — GitHub only auto-closes the first otherwise).
- **new capability / behavior change / spec-driven** → OpenSpec lifecycle on ONE branch (`<change-name>`, kebab, no `-v<N>`): `/opsx:propose` → `/opsx:apply` → `/opsx:archive`, squash-merged once. The change's PR carries `Closes #N`. If the issue was already `promoted` by triage with a scoped summary, reuse it. Run `/opsx:preflight` (next-change Phase B.5) after the proposal scaffolds and before `/opsx:apply` — surfaces operator/human tasks + cross-layer gaps up front.

When unsure which shape, apply OpenSpec's own test: does it add a capability + behavior + WHEN/THEN scenarios? Yes → OpenSpec. Detekt rules, CI config, build-logic, READMEs, mechanical moves → regular PR ([CLAUDE.md](../../../CLAUDE.md) § When NOT to use OpenSpec).

## 4 — Execute under the rails

- Read the relevant § of [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) BEFORE coding — non-negotiable. Check the Pattern Registry: an undeclared second pattern for a registered concern is a blocking self-review finding.
- Honor [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md): a user-facing capability ships its full vertical slice or declares each deferred layer as an explicit requirement.
- **Gates before every push:** `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + (mobile-touching) `:mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`. CI runs BOTH lint frameworks — `detekt` alone is insufficient (precedent: PRs #31/#32 hit CI lint failures after skipping `ktlintCheck` locally). **Name `:mobile:app:ktlintCheck` explicitly for mobile diffs** — a root `ktlintCheck` run has returned exit 0 while `:mobile:app:ktlintCommonMainSourceSetCheck` failed on the same tree (import-ordering, PR #462); don't trust the root aggregate for the module you touched. Don't run the gate while a local `:backend:ktor:run` is alive (Postgres connection budget — see verify-loop § Known blockers).
- **UI-affecting** → manual verification (verify-loop §B/§C) with screenshot evidence in the PR body before merge (docs/11 §5 DoD). **K/N-touching** → `:mobile:app:iosSimulatorArm64Test`.
- Keep the PR title/body current at every phase boundary (`gh pr edit`) — it's what the reviewer sees at squash-merge.
- **Engineering judgment over context budget** ([CLAUDE.md](../../../CLAUDE.md)): don't silently drop a spec'd scenario or compress a deferred-work list to fit a fading window — surface tightness and offer to split into a fresh-context follow-up.

## 5 — Close the loop

After the PR merges:

1. The `Closes #N` auto-closes the issue on merge; verify (`gh issue view <N> --json state` — the GitHub search index lags after a mutation, confirm explicitly). If only **partially** shipped, comment progress on the issue and leave it open with a residual note rather than closing.
2. Release any transient claim still standing: closed issues drop out of `--state open` automatically; if you abandoned mid-session, `gh issue edit <N> --remove-label burning-down`. **Never leave `burning-down` on an open issue** — it permanently hides the issue from the next sweep.
3. Report to the operator: what shipped (PR #), the remaining `ready-to-burndown` count, and a recommended next pick.

## Boundaries (anti-overlap)

- **issue-burndown does NOT re-triage from zero.** It trusts the `ready-to-burndown` queue (or an explicit number) + a quick freshness re-check (step 2). Full backlog classification is `/triage-follow-ups`.
- **`/triage-follow-ups` does NOT code.** Its still-valid disposition labels `ready-to-burndown` (or hands to `/issue-burndown`); it never implements.
- **Audit menu is `/audit-burndown`'s.** Step 1's audit-dedup keeps the two executors disjoint.
- **`/next-change` is for NEW capabilities**, discovered from roadmap/version gaps — not issue-sourced.

## Parallel-session coordination

Same model as `/triage-follow-ups`: zero extra infra, claims live in labels + branches.

- **Claim unit = the issue number**, reserved by adding `burning-down` (step 2). Others skip any `burning-down`/`triaging`/assigned/linked-PR issue (step 0).
- **Claim at execute-time, not pick-time**, then re-read to confirm — keeps the window small. A double-claim is low-harm (drop and report "deferred to concurrent session").
- **`burning-down` is TRANSIENT** — removed when the issue closes (automatic) or on abandon. A leaked claim hides the issue forever.
- **One branch per session**, named per the issue's shape (`fix/`/`refactor/` slug, or the `<change-name>` for OpenSpec).

## Safety

All mutation lands on a fresh feature branch + PR — never push to `main`, never `--no-verify`/`--no-gpg-sign`. Don't run the gradle gate while a local `:backend:ktor:run` is alive. Issue/label/comment side-effects are public (source-available repo) — no secrets, customer PII, or speculative commercial strategy. `gh` needs the `aditrioka` account (else 403).

## Self-improving rule

New blocker / stale constraint / wrong route discovered while burning down an issue → fix it HERE (the relevant step) before finishing, same as verify-loop and audit-burndown.
