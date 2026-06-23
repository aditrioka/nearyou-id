---
name: openspec-preflight
description: Run the start-of-change preflight gate for a nearyou-id OpenSpec change — AFTER the proposal is scaffolded and BEFORE /opsx:apply writes code. Surfaces human-required/operator tasks (credentials, GCP provisioning, store/dashboard config, physical-device verify), counterpart-layer gaps (a feature missing its client/admin surface or an un-threaded wire field), and unmapped test scenarios, so they're delegated to the operator up front instead of discovered mid-implementation as follow-ups. Use it as `/next-change` Phase B.5, at the top of `/opsx:apply`, or whenever asked to "preflight a change" before implementing.
---

This is nearyou-id's **start-of-change preflight gate**. It exists because a large share of the open `follow-up` backlog is work that was *discovered late* — mid-`/opsx:apply` or post-merge — that a check at proposal time would have surfaced. The gate runs once, after the proposal artifacts exist and before implementation begins, and produces a short **Preflight report** that is recorded in the PR body and surfaced to the operator.

It is read-only with respect to code — it inspects the scaffolded `proposal.md` / `design.md` / `specs/**` / `tasks.md` and the canonical docs, and it files `follow-up` issues + writes the report. It does **not** implement tasks.

## When to run

- **`/next-change` Phase B.5** — after B.4 (standards check), before Phase C finalizes the PR body. This is the primary entry point; the report's output goes into the C.3 PR body.
- **Top of `/opsx:apply`** — as a precondition: if the PR body has an unacknowledged **Human-required tasks** block, surface it and do not start tasks that depend on the missing setup until the operator confirms.
- **Skip** for non-OpenSpec / regular-PR work (infra/tooling/CI/docs-only) — there is no proposal to preflight.

## The four checks

Run all four against the scaffolded change. Each produces report entries; none silently passes.

### 1. Human-required / operator tasks (the delegate-up-front check)

Scan the change for work the **agent cannot do in-session** and must route to the operator *now*, not discover when it hard-blocks at apply time. Flag any of:

- **Secrets / credentials** — a new `secretKey(env, name)` read, a new Secret Manager slot, a store/test key (RevenueCat `test_`/prod, FCM/APNs key, OAuth client). The agent can name the slot but the operator must populate it (memory `project_revenuecat_staging_config_ids`, `reference_staging_supabase_db_access`).
- **GCP provisioning** — Cloud Scheduler jobs, `run.invoker` bindings, Test Lab device infra, anything needing `gcloud` against project resources. Network egress to GCP APIs is blocked in-session (memory `gcloud_hangs_curl_works_local_sandbox`); precedent: [#344](https://github.com/aditrioka/nearyou-id/issues/344), [#382](https://github.com/aditrioka/nearyou-id/issues/382) were *attempted* in-session, hard-blocked, then filed as follow-ups.
- **External dashboards / consoles** — RevenueCat product/price config, Supabase dashboard SQL (destructive writes are classifier-gated → a human runs them), Qodo config, Firebase console.
- **Physical-device / manual verify** — a two-device chat smoke, an App-Store-only flow, anything the device farm can't cover ([#280](https://github.com/aditrioka/nearyou-id/issues/280)).

For each, write a one-line task naming **what the operator must do** and **what blocks without it**. These are the report's **Human-required tasks** block.

### 2. Counterpart-layer check (cross-layer cohesion — see [`docs/12`](../../../docs/12-Integration-Contracts.md))

Decide whether the capability is **user-facing** (a screen, action, notification, or admin surface a real user/operator observes). If it is, verify the proposal ships its full vertical slice per `docs/12` §2:

- Does a backend capability have the **client surface** (mobile and/or admin) that makes it usable? If not, is the missing layer declared as a `docs/12` §3 deferred requirement (positive + negative-guard + tracking issue), or is it an undeclared split?
- Does a new/changed response field thread through **every read-path** that returns the entity, and through the **client DTO(s)** that parse it? (The `imageUrl`-missing-from-notifications gap, [#388](https://github.com/aditrioka/nearyou-id/issues/388), is the canonical miss.)

Flag any undeclared single-layer slice of a multi-layer capability as a **blocking** report entry — the fix is to either widen scope or add the §3 deferred requirement before apply.

### 3. Doc-reconciliation diff

This overlaps `/next-change` B.3 — **do not duplicate it; confirm it ran and record the result.** If invoked outside `/next-change` (e.g. directly before `/opsx:apply`), run the B.3 procedure now: diff every schema/algorithm/domain claim in the proposal against the canonical `docs/**` / `openspec/specs/**` it cites; bucket each divergence (a) fix proposal / (b) file stale-doc follow-up / (c) surface to user. Precedent for late catches: [#357](https://github.com/aditrioka/nearyou-id/issues/357), [#337](https://github.com/aditrioka/nearyou-id/issues/337).

### 4. Test-scenario → task mapping

For every `#### Scenario:` in `specs/**`, confirm `tasks.md` has a task that will produce a backing test. A scenario with no mapped test is either an unplanned gap (add the task) or a deliberate deferral — and a deliberate deferral MUST be an explicit requirement, never a silent drop (memory `feedback_defer_as_explicit_requirement`, and CLAUDE.md § "Engineering judgment over context budget"). Precedent for spec'd-but-skipped tests resurfacing as follow-ups: [#310](https://github.com/aditrioka/nearyou-id/issues/310), [#347](https://github.com/aditrioka/nearyou-id/issues/347).

## Output — the Preflight report

Emit this block and have `/next-change` C.3 paste it into the PR body under a `## Preflight` heading:

```
## Preflight (run <date>)

### Human-required tasks (operator must action — block where noted)
- [ ] <task> — blocks: <what fails without it> (issue #<n> if filed)
- [ ] … (or "None")

### Cross-layer cohesion (docs/12)
- Vertical slice: <complete | deferred layer(s) declared as §3 requirements | ⚠ undeclared split — BLOCKING>
- Wire fields threaded to all read-paths + client DTOs: <yes | n/a | ⚠ gap: …>

### Doc reconciliation (B.3)
- <zero divergence | fixed in proposal | stale-doc follow-up #<n> | surfaced to user>

### Test coverage
- Scenarios mapped to tasks: <N/N | gaps added as tasks | deferral declared as requirement>
```

## Blocking rule

The preflight does not stop the lifecycle on its own, but:

- **Human-required tasks** that block implementation MUST be acknowledged by the operator before `/opsx:apply` starts the dependent tasks. `/opsx:apply` re-reads this block as a precondition (do not silently implement around a missing secret/provisioning step — that recreates the late-discovery failure).
- An **undeclared single-layer slice** (check 2) is blocking: fix scope or add the `docs/12` §3 deferred requirement before handoff.
- A **silent test-scenario drop** (check 4) is blocking: add the task or declare the deferral as a requirement.

Surface the report to the user via `AskUserQuestion` when any blocking entry exists; otherwise record it and continue.

## Self-improving rule

When you hit a human-required task category or a cross-layer gap **not** listed above, add it to the relevant check before you finish — same discipline as `verify-loop`. This file is committed; your addition tightens the gate for the next change.
