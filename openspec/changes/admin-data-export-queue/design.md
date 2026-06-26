## Context

The `account-data-export` producer (shipped) backs the UU-PDP right to data portability: `POST`/`GET /api/v1/account/export` enqueue/read, and an OIDC-authed Cloud-Scheduler worker (`/internal/data-export-worker`, `DataExportWorker.execute()`) drains `pending` rows of `data_export_requests` (V30) through a per-request pipeline — claim (`pending → processing`, affected-rows guard), gather the docs/06 §350 scope matrix, serialize a JSON+CSV ZIP, upload to R2, presign a 24h GET URL, set `ready` (+ `r2_object_key` + `download_expires_at`), emit the durable `data_export_ready` notification, then a best-effort Resend email. A gather/upload failure is **fail-soft**: the row goes `failed` (+ non-PII `error` + `attempt_count++`), no `ready`/notification/email.

docs/07 § Admin Panel specifies an admin **Data Export Queue** (status orchestration + manual re-trigger) but it was **deferred to [#361](https://github.com/aditrioka/nearyou-id/issues/361)** — "the producer adds no `/admin/*` route." This change ships that deferred admin layer. The canonical visual is admin mockup **frame 16** (`dev/mockups/nearyou-admin-mockup.html`): `GET /admin/data-exports · POST …/{id}/trigger`.

Two shipped admin surfaces are direct precedents to mirror, so this consumes the existing skeleton rather than inventing a parallel one:
- **`admin-hard-delete-queue`** (frame 15) — `AdminDeletionQueueRoute` + `DeletionQueueRepository` + `DeletionQueueExpediteRateLimiter`: a read-only keyset list **plus** a worker-deferred write (`POST …/{id}/expedite`, owner/admin + CSRF + required reason + `hx-confirm` + **distinct 10/admin/hr** counter + one immutable audit row + benign rejection for ineligible rows). The closest analog.
- **`admin-subscription-grace-monitor`** (frame 18) — `AdminSubscriptionGraceRoute` + `SubscriptionGraceRepository` + `GraceExpediteActionRateLimiter`: the same read+write idioms (keyset, `q`/`status` filters, count summary, HTML-escape, plain-`GET` fallback, distinct rate-limit counter).

## Goals / Non-Goals

**Goals:**
- A read surface (`GET /admin/data-exports`) operators use to see every export request's identity + lifecycle state, with status/user filtering — for UU-PDP 7-day-SLA accountability + support triage.
- A recovery action (`POST /admin/data-exports/{id}/trigger`) that re-runs a stalled/failed export **through the existing producer pipeline** (no second export path), audit-logged + rate-limited.
- Strict identity-only PII: the panel shows status, never the archive contents / object key / signed URL.
- Zero schema change; mirror the established admin route/repo/rate-limit/template skeleton.

**Non-Goals:**
- The operational-dashboard "exports/hour" widget (#303 deferred-widgets territory).
- Any change to the user-facing `/api/v1/account/export` contract or the mobile Settings "Unduh Data Saya" entry ([#362](https://github.com/aditrioka/nearyou-id/issues/362)).
- The Amplitude funnel embed.
- A bulk "trigger all" action — single-row only (rate-limit + audit clarity).

## Standards conformance (docs/11 Pattern Registry)

- **Backend layering** — route → repository → `DataSource`; no business logic in the route. Follows the shipped **admin-surface pattern**: `admin/routes/Admin<Surface>Route.kt` (auth/CSRF/role gating, parameter parsing, HTMX-vs-plain render) + `admin/<surface>/<Surface>Repository.kt` (parameterized keyset reads + the mutation helper) + `admin/ratelimit/<Action>RateLimiter.kt` (audit-trail-as-ledger counter) + a Pebble template, all wired in `AdminModule.kt`. Audit via the shared `AdminAuditLogger` (one immutable `admin_actions_log` row per write).
- **Rate-limit pattern** — the **audit-trail-as-ledger** limiter (COUNT over `admin_actions_log` in the trailing hour for this admin + action type), exactly as `DeletionQueueExpediteRateLimiter` / `RejectedIdentifierClearRateLimiter`. A **distinct 10/admin/hr** bucket, independent of the 20/hr destructive budget.
- **No new pattern** is introduced for any Pattern-Registry concern → **no docs/11 § Pattern Registry amendment** required.

## Cross-layer scope (docs/12)

- **Layers spanned:** backend **admin** only. Admin surfaces are operator-only, server-rendered (Pebble + HTMX) — there is no mobile/other-client layer for an admin capability.
- This change **closes** the previously-deferred admin layer of the shipped `account-data-export` producer (docs/12 §2 cohesion). It introduces **no** new deferred layer of its own — the admin surface is complete here.
- The MODIFIED `account-data-export` delta is the producer-side seam the admin trigger consumes (the single-request entry point), keeping the two layers on one export path.

## Decisions

### Decision 1 — Trigger mechanism: re-enqueue + drive the existing single-request pipeline (NOT re-enqueue-only)

**Chosen:** `POST …/{id}/trigger` re-enqueues the row to `pending` where needed, then **synchronously drives the worker's per-request pipeline for that one `id`**, reusing the exact producer code path. Concretely, `DataExportWorker`'s private `processOne(requestId)` is promoted to a reusable single-request seam (e.g. `processSingle(id)`) called by **both** the batch `execute()` loop and the admin trigger — one export path, two callers.

- Triggerable states: **`failed`** (re-enqueue `→ pending`, then drive) and **`pending`** (already enqueued — drive directly; `claimPending` claims `pending → processing` and runs).
- Non-triggerable → benign no-op (no mutation, no audit row): **`processing`** (in-flight), **`ready`** (valid link), **`expired`** (the user can self-serve a fresh request via the app — `expired` is not `active`, so `POST /api/v1/account/export` creates a new one).
- The re-enqueue UPDATE is conditional + honors the existing one-active partial UNIQUE index (`data_export_requests_one_active_idx`, `pending|processing`): if the user already has another active row, the unique-violation is caught and mapped to "already active, no-op" (no exception surfaced, idempotent — mirrors hard-delete's "rejected for already-executed/cancelled/already-due/unknown").

**Alternative considered — re-enqueue-only ("admin schedules, worker executes", the hard-delete-queue precedent):** flip `failed → pending` and let the next scheduled `/internal/data-export-worker` run reprocess it; no producer change. **Rejected** because it does not satisfy frame-16's intent. The mockup shows "Trigger job" on a **QUEUED (`pending`)** row, and the banner states the action is "dipakai bila scheduler gagal" (used when the scheduler fails). Re-enqueue-only is (a) a **no-op on an already-`pending` row** (the QUEUED case the mockup explicitly targets) and (b) **useless when the Cloud Scheduler itself is down** — exactly the failure the operator is recovering from. Reusing the single-request pipeline delivers immediately and still reuses 100% of the producer logic (anti-patchwork). The hard-delete precedent's "schedule-only" shape fits *timestamp* actions (advance a deadline); a data-export re-run is genuinely "run the job now," so the precedent's read-surface idioms transfer but its write *mechanism* does not.

**Trade-off:** the trigger does the producer's heavy per-request work (gather + ZIP + R2 upload) **inside the admin HTTP request**, so the response is slower than a bookkeeping write. Bounded acceptable because admin triggers are manual, single-row, and rate-limited (10/admin/hr); HTMX shows an in-flight state. See Risks.

### Decision 2 — Status vocabulary mapping (DB → UI)

The 5 DB statuses surface as frame-16 labels: `pending → QUEUED`, `processing → RUNNING`, `ready → DELIVERED`, `expired → EXPIRED`, `failed → FAILED`. The "Delivered via" cell shows `data_export_ready` notification + email only for `ready`; `—` otherwise. The mapping lives in the route/template, not the DB.

### Decision 3 — Read path: keyset over the existing index, no migration

The list is keyset-paginated newest-`requested_at`-first, served by the existing `data_export_requests_user_recent_idx (user_id, requested_at DESC)` for per-user lookups and a plain `requested_at DESC, id` keyset for the global list; `status` filter narrows in-memory-free via SQL predicate. `q` resolves a username/UUID against `users`. **No new index, no migration** — `admin_actions_log.action_type` is a free `VARCHAR(64)` (verified: V16; only the unrelated `appeals` table CHECK-constrains an `action_type`), so the new `data_export_triggered` string adds no schema.

### Decision 4 — Audit + authz shape (mirror the precedents)

CSRF token verified **before** the role check (mismatch → 403 + `admin_csrf_violation` audit, no mutation); then `role IN ('owner','admin')`; then required-reason validation; then the rate-limit acquire; then the conditional re-enqueue + single-request drive + exactly one `admin_actions_log` row (`action_type='data_export_triggered'`, before/after status in `before_state`/`after_state`) — the audit write + the state transition in one transaction. A rejected (non-triggerable / rate-limited / one-active-conflict) trigger writes **no** audit row.

## Risks / Trade-offs

- **[Heavy work in the admin request]** The trigger runs gather+ZIP+R2 upload synchronously. → Mitigation: single-row + 10/admin/hr rate limit bound load; HTMX renders an in-flight state and swaps the row on completion; the operation is the same per-request cost the worker already pays. If a future need arises for fire-and-forget, the `processSingle` seam can be dispatched onto the worker dispatcher without changing the contract.
- **[R2 unconfigured in some envs]** `ObjectStoreUnconfiguredException` is already handled fail-soft by the pipeline (row → `failed`). → The trigger surfaces the resulting `FAILED` state to the operator (honest), rather than masking it.
- **[Double-run race]** An operator trigger landing while the scheduled worker also picks the row up. → Mitigation: `claimPending`'s `pending → processing` affected-rows guard already serializes; the second claimant gets `SKIPPED` (no double-process) — the existing producer invariant, inherited unchanged.
- **[PII leak via the panel]** Rendering the object key or signed URL would expose the user's archive. → Mitigation: the repository read projection **excludes** `r2_object_key` / `download_expires_at` value rendering; only status + identity are surfaced; HTML-escape all user-derived text.

## Migration Plan

No DB migration. Deploy is additive (new admin routes + template + `AdminModule` wiring + the `processSingle` seam promotion). Rollback = revert the PR; the producer worker is unaffected (the promoted seam is internal). On archive, reconcile docs/07 § Data Export Queue (DEFERRED → shipped) and close #361.

## Open Questions

- **Triggerable on `expired`?** Currently **no** (the user self-serves a fresh request; `expired` is not `active`). If support data shows operators frequently re-running expired exports on a user's behalf, a follow-up can add `expired` to the triggerable set (the re-enqueue path already supports it, gated only by the one-active index). Deferred to keep the action tightly scoped to the mockup-shown intent (QUEUED/FAILED recovery).
