## Context

The retention windows for three growing tables are already canonical in `docs/05-Implementation.md`, each naming the same un-built endpoint:

- §112 — `refresh_tokens`: "Cleanup (Cloud Scheduler `/internal/cleanup`): daily `WHERE expires_at < NOW() - INTERVAL '1 day'`; weekly `WHERE last_used_at < NOW() - INTERVAL '90 days'`."
- §582 — `notifications`: "Retention: 90 days auto-purge via `/internal/cleanup`."
- §1120 — `user_fcm_tokens`: "Stale (weekly via `/internal/cleanup`) → delete `WHERE last_seen_at < NOW() - INTERVAL '30 days'`."

Every target table and the index that serves each sweep already ship: `refresh_tokens_expires_idx` (V2), `notifications_user_all_idx` (V10), `user_fcm_tokens_last_seen_idx` (V14). The only missing piece is the worker. Three sibling internal workers are already in production and define the exact pattern to copy — `suspension-unban-worker`, `privacy-flip-worker`, and `account-hard-delete-worker` — each a Cloud-Scheduler-invoked `POST /internal/<name>` gated by the Google-OIDC verifier on its **own** route subtree.

This is a `:backend:ktor`-only change. No mobile, no admin UI, no `:infra:*` module, no Flyway migration.

## Goals / Non-Goals

**Goals:**
- Enforce the three written retention windows (`refresh_tokens`, `notifications`, `user_fcm_tokens`) on a schedule, closing the UU-PDP data-minimization gap, the stale-token security surface, and unbounded table growth.
- Reuse the shipped internal-worker shape exactly: own-subtree OIDC gate, idempotent bulk `DELETE`s, `200` + per-sweep counts, one structured INFO log line, classified `500`, `401` when unauthenticated.
- Bound the scope explicitly: name the two deferred sweeps (WebAuthn challenges, moderation/reports archival) as scope-boundary requirements so a later change can MODIFY them.

**Non-Goals:**
- The FCM on-send `404/410` (`UNREGISTERED`/`SENDER_ID_MISMATCH`) immediate single-token delete — **already shipped** in `infra/fcm/FcmDispatcher.kt` → `UserFcmTokenReader.deleteTokenIfStale` (`fcm-push-dispatch` spec). This change adds only the scheduled bulk stale sweep and must not touch the send path.
- WebAuthn challenge cleanup (`admin_webauthn_challenges`, `docs/05` §705) — deferred; the multi-admin WebAuthn period hasn't started and nothing writes that table.
- Moderation-queue / reports 1-year archival (`docs/08` Phase 3.5 item 12) — deferred; distinct archival concern on a 1-year horizon.
- Any new Flyway migration, schema change, or secret slot.
- Per-row audit logging of deletions (see Decision D3).

## Decisions

### D1 — Endpoint name `/internal/cleanup`, OIDC gate on its own subtree
Use the canonical name `docs/05` already commits to in three places: `POST /internal/cleanup`. Mount the internal-endpoint OIDC verifier on the `/cleanup` subtree **only**, never on the shared `/internal` node — identical to the `privacy-flip-worker` requirement that keeps a worker gate from capturing the sibling `/internal/revenuecat-webhook` (which authenticates by shared-secret Bearer + HMAC, not Google OIDC).

*Reconciliation:* `docs/08` Phase 3.5 item 6 loosely also says hard-delete is on `/internal/cleanup`, but that capability actually shipped as `/internal/account-hard-delete-worker` on its own subtree. So `/internal/cleanup` is unused and free, and it is the name the three `docs/05` retention sections cite — this change takes it for the retention sweeps. *Alternative considered:* a generic name like `/internal/retention-worker`; rejected because it would orphan the three existing `docs/05` `/internal/cleanup` references and require a docs reconciliation.

### D2 — One daily invocation runs all sweeps (simplification of the docs/05 daily/weekly split)
`docs/05` §112 distinguishes a daily cadence (refresh-token expired) from a weekly cadence (everything else). Every sweep here is an idempotent threshold `DELETE`, so running the 90-day and 30-day sweeps **daily** instead of weekly is exactly as correct — it simply removes the newly-aged-out rows each day and removes a second Cloud Scheduler job. The worker runs **all** sweeps on every invocation; the operator provisions a single daily schedule.

*Alternative considered:* a `?scope=daily|weekly` query parameter with two Cloud Scheduler jobs, faithful to the docs split. Rejected for the MVP as needless operational surface (two jobs, a branch in the handler) for zero correctness gain; it can be added later without a spec change if a sweep ever becomes heavy enough to warrant a rarer cadence. This deliberate simplification is recorded here and reconciled against `docs/05` §112 (the daily/weekly wording describes Scheduler cadence, not a correctness constraint).

### D3 — No per-deletion audit rows
Unlike `privacy-flip-worker` (which writes one `admin_actions_log` row per flip because a privacy downgrade is a user-visible state change), routine retention purges are system hygiene with no user-facing semantics, and `docs/05` calls for none. The worker's accountability surface is the single structured INFO log line carrying per-sweep counts + duration — no `admin_actions_log` writes, no `system` sentinel actor needed.

### D4 — Layering, JDBC discipline, no migration
Follow `docs/11` §3.1 layering: a thin `XxxRoutes` (OIDC gate + parse + respond) → an `XxxService`/worker (sweep orchestration) → a JDBC repository (the `DELETE` SQL). All JDBC runs on the pool-bounded dispatcher (`docs/11` §3.2 — `Dispatchers.IO.limitedParallelism(maxPoolSize)` via DI, never raw `Dispatchers.IO`), matching every existing repository. Each sweep is an independent single-statement `DELETE` (unrelated tables, no audit rows to keep atomic), executed sequentially; the handler aggregates the per-sweep `executeUpdate()` counts. No cross-table transaction is required or desirable — a failure in one sweep should not roll back a successful sibling sweep's reclaimed rows.

### D5 — Standards conformance (anti-patchwork)
This change introduces **no new pattern**. It builds on the existing Pattern-Registry patterns: backend layering (`docs/11` §3.1, Routes→Service→Repository), JDBC/connection discipline (`docs/11` §3.2, pool-bounded dispatcher), the shipped internal-worker convention (own-subtree OIDC gate + classified-500 + single structured run log) established by `suspension-unban-worker`/`privacy-flip-worker`, and the internal-endpoint OIDC auth (`internal-endpoint-auth` spec). Naming follows the sibling workers (`docs/11` Naming coherence): a `RetentionCleanup{Routes,Worker,Repository}` triad mirroring `PrivacyFlipWorker{,Route}`. No `docs/11` § Pattern Registry amendment is needed.

## Risks / Trade-offs

- **A misconfigured threshold deletes live data** → Thresholds are fixed literals from `docs/05` (`1 day`/`90 days`/`30 days`), covered by per-sweep boundary tests (a row just inside the window survives; a row just past it is deleted). No threshold is client- or query-supplied.
- **Large first run on an aged table holds locks / spikes load** → Each sweep is index-served (`expires_at`/`created_at`/`last_seen_at` indexes exist) and runs on the bounded DB dispatcher, so it cannot exhaust the Hikari pool. Pre-launch tables are tiny; if a future run ever needs batching (`DELETE … LIMIT`/`ctid` chunking), that is a follow-up — noted, not built now (no silent cap).
- **Running weekly-cadence sweeps daily (D2)** → strictly more frequent, smaller deletes; the only cost is one extra index scan/day on tables that are already scanned by app traffic. Acceptable.
- **Deferred sweeps look "covered" once this ships** → mitigated by encoding both deferrals as explicit spec requirements with negative-guard scenarios (the worker does NOT touch `admin_webauthn_challenges` / `moderation_queue` / `reports`) + tracking `follow-up` issues, so the gap is visible, not implied.

## Migration Plan

1. Merge → `main` auto-deploys staging. The endpoint is inert until a Cloud Scheduler job calls it.
2. Operator provisions one daily Cloud Scheduler job hitting `POST /internal/cleanup` with a Google OIDC identity token (audience = the internal-endpoint OIDC audience), mirroring the existing unban / privacy-flip / hard-delete schedules. No new secret slots.
3. Pre-archive: staging branch deploy + a smoke call (unauthenticated → `401`; authenticated → `200` with counts) per project.md § Staging deploy timing.
4. **Rollback:** pause/delete the Cloud Scheduler job (worker goes inert) and/or revert the PR. No schema to undo; deletions are not reversible, but every deleted row was already past its written retention window.

## Open Questions

- None blocking. (Batched/chunked deletion for very large tables is explicitly a future follow-up, not an open question for this change.)
