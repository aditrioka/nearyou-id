## Context

The Premium→Free privacy-downgrade flow (`docs/02` §"Privacy Downgrade Flow", Phase 4 item #6) has three parts: (a) **schedule** a flip on lapse, (b) keep the user effectively private during a 72h grace window, (c) **apply** the flip when the deadline elapses. Part (b) already ships — `JdbcUserProfileReader.kt` computes effective-private as `(private_profile_opt_in AND subscription_status IN ('premium_active','premium_billing_retry')) OR (privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at > now())`. Parts (a) and (c) do not exist: the shipped `subscription-billing-webhook` explicitly **defers** scheduling/clearing ("the future privacy-flip-worker change will MODIFY the EXPIRATION and purchase/renewal transitions"), and no worker applies an elapsed flip. Consequently `users.privacy_flip_scheduled_at` is never written, and the shipped admin `privacy-flip-monitor` (`GET /admin/privacy-flips`) has no rows to show.

This change implements (a) and (c). All schema already exists — `privacy_flip_scheduled_at` + `users_privacy_flip_idx` (V2), the `privacy_flip_warning` notification type in the V10 catalog CHECK, the `system` sentinel actor (V18), `admin_actions_log.action_type` as plain `VARCHAR(64)` (V16) — so there is **no Flyway migration**.

## Goals / Non-Goals

**Goals:**
- On `EXPIRATION`, schedule a 72h flip for private users (idempotent `COALESCE`) + emit `privacy_flip_warning` (in-app + FCM); on re-activation, clear the pending flip — both inside the webhook's existing single transaction.
- A new hourly `POST /internal/privacy-flip-worker` that flips elapsed-grace private profiles to public, clears the timestamp, and writes one immutable system-attributed audit row per flip — atomic + idempotent.
- Give the shipped admin privacy-flip monitor real data.

**Non-Goals:**
- Modifying the effective-private read path (`JdbcUserProfileReader`) — the grace short-circuit already exists.
- The time-based grace-elapse auto-downgrade worker (`premium_billing_retry` → `free` after 7d) — a separate deferred change.
- Referral `GRANT` entitlement stacking — owned by the referral-system change.
- A profile read-cache bust — see Decision 4.
- Any Cloud Scheduler resource definition — the hourly job is ops config (Decision 5), tracked but not code in this PR.

## Decisions

**D1 — Scheduling/clearing live in the webhook handler (`SubscriptionService`), as a MODIFY to `subscription-billing-webhook`.** The flip deadline is a side effect of a billing-status transition and must be transactionally consistent with it, so it belongs in the same `UPDATE users … RETURNING` the handler already runs (design D6 of the shipped webhook — read-free status apply). The status apply is extended to `RETURNING private_profile_opt_in` so the handler decides whether to emit `privacy_flip_warning` without a separate read. *Alternative considered:* a standalone "schedule" endpoint the webhook calls — rejected (extra round-trip, breaks the one-transaction exactly-once guarantee). The single dedicated requirement ("EXPIRATION schedules and re-activation clears…") owns the whole coupling, so the two status-transition requirements (`free` / `premium_active` + their notifications) are unchanged.

**D2 — The worker mirrors `suspension-unban-worker` verbatim (single data-modifying CTE, system-actor audit, own-subtree OIDC gate).** `SuspensionUnbanWorker` is the established internal-worker pattern: `eligible AS (SELECT … FOR UPDATE) → flipped AS (UPDATE … RETURNING) → INSERT admin_actions_log SELECT … FROM flipped RETURNING target_id`, one transaction, fresh connection, `SYSTEM_ACTOR_ID` attribution, `flipped_count` + capped id list in a structured INFO log, `classifyHandlerError` → `{timeout, connection_refused, unknown}`. Reusing it (not inventing a parallel worker shape) is the anti-patchwork requirement. The `action_type` is `system_privacy_flip_applied`, paralleling the unban worker's `system_unban_applied` (`system_<action>_applied` convention; `docs/08` Phase-4 item-6 calls it `privacy_flip_applied` loosely — see Risks). *Alternative considered:* a Kotlin-side read-then-update loop — rejected (loses atomicity + the single-statement idempotency the CTE gives for free).

**D3 — Own-subtree OIDC gate, never the shared `/internal` node.** `UnbanWorkerRoute` documents (and `InternalRoutingIsolationTest` guards) that Ktor merges identical path segments across `routing {}` blocks, so installing `InternalEndpointAuth` on `/internal` would also capture `/internal/revenuecat-webhook` — which authenticates by shared-secret Bearer + HMAC, not Google OIDC — and 401 it before its own verification runs. The worker's gate is installed on `route("/privacy-flip-worker") { install(InternalEndpointAuth) … }` only. This is load-bearing here because this very change lives next to that webhook; a routing-isolation test is mandatory.

**D4 — `COALESCE` for idempotent scheduling; unconditional `NULL` for clearing.** A re-delivered `EXPIRATION` (distinct `revenuecat_event_id`, so not caught by the event-idempotency gate) must not push the deadline later — `COALESCE(privacy_flip_scheduled_at, NOW()+72h)` keeps the first-set deadline. Clearing on re-activation is an unconditional `= NULL` (idempotent whether or not a flip was pending). Both are pure column writes in the existing UPDATE, no extra statement.

**D5 — Cloud Scheduler hourly job is ops config, not code.** Like the daily `unban-worker` job, the hourly invocation is a Cloud Scheduler resource with an OIDC identity token targeting the endpoint; it is captured as a `tasks.md` ops/deploy item and a deployment note, not a Kotlin deliverable. The endpoint is correct and idempotent regardless of trigger cadence.

**D6 — No profile read-cache bust (documented no-op).** `docs/02`:97 lists "busts the Redis profile cache" as a worker step, but profile **reads** are uncached today — `JdbcUserProfileReader` hits Postgres directly; only rate-limiters use Redis in the user path. The worker's committed `UPDATE` is therefore immediately visible. The cache-bust step is a no-op now; if a profile read-cache is later added it must subscribe to this flip. Tracked as a follow-up rather than built speculatively.

### Standards conformance (`docs/11-Engineering-Standards.md`)

Backend change; conforms to the §3 Backend architecture contract and introduces **no new Pattern-Registry pattern**:
- **§3.1 layering** — route (`privacyFlipWorkerRoute`) → worker (`PrivacyFlipWorker`) / service (`SubscriptionService`) → JDBC; no business logic in the route.
- **§3.2 JDBC discipline** — one transaction, fresh pooled connection, `FOR UPDATE` snapshot + single data-modifying CTE; pool-bounded dispatcher (`DbDispatchers.db`) as in `SubscriptionService`.
- **Pattern Registry (§3 / §4)** — reuses the existing **internal-worker pattern** (OIDC-own-subtree route + `SYSTEM_ACTOR_ID` audit CTE) from `suspension-unban-worker`, and the existing **notification-emit pattern** (`NotificationEmitter.emit` in-tx + post-commit `NotificationDispatcher.dispatch`) from `SubscriptionService`/`LikeService`. No second pattern for either concern → no `docs/11` Pattern-Registry amendment needed.
- **Invariants** — no `visible_*`/block-join surface (system worker + own-content webhook path); secrets unaffected; audit immutability preserved (insert-only `admin_actions_log`).

## Risks / Trade-offs

- **Up-to-1h window between deadline-elapse and the worker run.** Once `privacy_flip_scheduled_at <= now()`, the read short-circuit (`> now()`) is already FALSE and `subscription_status = 'free'`, so the profile **already reads public** at the deadline; the worker only makes it durable in the column + writes the audit row. → No privacy regression: the user goes public exactly at the deadline via the read formula, independent of worker latency. Worth stating because it means the worker is a bookkeeping/durability step, not the privacy-enforcement boundary.
- **`action_type` naming vs docs.** `docs/08` Phase-4 item-6 writes "`privacy_flip_applied`"; this change uses `system_privacy_flip_applied` to match the shipped `system_unban_applied` convention. → Consistency with running code wins; flag the docs phrasing as a loose reference (reconciliation note, not a behavior change).
- **Two notifications for a private lapse.** A private user's `EXPIRATION` writes both `subscription_expired` and `privacy_flip_warning`. → Intended — distinct concerns (you lost Premium / your profile will go public). Both are in the V10 catalog.
- **Idempotency depends on the clear.** If a flip applied but its audit INSERT failed, the transaction rolls back and the row stays eligible — correct (it retries next run). Because the clear and the flip are one statement, there is no "flipped but still scheduled" partial state. → The atomic CTE is what makes the re-run idempotency hold.

## Migration Plan

No Flyway migration (schema pre-exists). Deploy is a normal Cloud Run rollout. After deploy, add the hourly Cloud Scheduler job (`/internal/privacy-flip-worker`, OIDC token) — the ops step in `tasks.md`. Rollback: revert the PR; the endpoint disappears and the webhook reverts to status-only on EXPIRATION. Any `privacy_flip_scheduled_at` values already written remain harmless (the read short-circuit handles them; without the worker they simply aren't auto-applied — the prior deferred state).

## Open Questions

- None blocking. The `action_type` wording (`system_privacy_flip_applied` vs the docs' `privacy_flip_applied`) is resolved in favor of the running-code convention (D2 / Risks); if a reviewer prefers the docs literal it is a one-token change.
