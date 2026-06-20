## Why

The account right-to-erasure (UU PDP) pipeline is fully built **except** for operator oversight: the `deletion_requests` table (V27), the user-initiated request → 30-day grace → cancel lifecycle (`account-deletion`), and the daily hard-delete worker (`account-hard-delete-worker`) all ship today, but an admin has **no surface** to see which accounts are pending hard-delete, how long until each executes, or to honor a support request to expedite one ("just delete me now, don't make me wait the 30 days"). Today that requires raw SQL against production. The Hard Delete Queue is a documented Core Feature ([`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features → "Hard Delete Queue") and admin mockup board frame 15 (`/admin/deletion-requests`) — it is the last unbuilt account-lifecycle admin surface.

## What Changes

- **New admin read surface** `GET /admin/deletion-requests` — keyset-paginated (soonest-deadline-first), filterable (`q` username/UUID + `source`), count-summarized table of accounts pending hard-delete (`deletion_requests` rows with `executed_at IS NULL AND cancelled_at IS NULL`), with HTMX render + plain-`GET` no-JS fallback. The 6th instance of the established read-only-admin-viewer pattern (after rejected-identifiers, block-registry, privacy-flip-monitor, subscription-grace-monitor, …). Columns per frame 15: User (deep-link to `/admin/users?q=`), Requested (UTC), Scheduled hard-delete (UTC), Countdown, source badge.
- **New mutating admin action** `POST /admin/deletion-requests/{id}/expedite` — brings `scheduled_hard_delete_at` forward to `NOW()` so the **existing** daily hard-delete worker executes the erasure on its next run (the admin *schedules*; the worker *executes* — no synchronous cascade in the route). owner/admin-role + CSRF-gated, **required reason**, `hx-confirm` guard, one immutable `admin_actions_log` row (`action_type = 'deletion_request_expedited'`), rate-limited on a distinct trailing-hour counter. Unlike the grace-monitor's no-op bookkeeping expedite, this is a **real, irreversible-accelerating** mutation — `before_state`/`after_state` differ on `scheduled_hard_delete_at`.
- **Zero new migration.** The list is served by the **existing** partial index `deletion_requests_scheduled_idx`; `admin_actions_log.action_type` is free-text `VARCHAR(64)` (no CHECK), so the new literal needs no schema change.
- **No new substrate** — reuses the Ktor admin module's Pebble + HTMX + vendored-CSS UI pattern, the `admin-destructive-action-rate-limit` ledger mechanism, and the JDBC layering already in the module. No `libs.versions.toml` touch.

## Capabilities

### New Capabilities
- `admin-hard-delete-queue`: An authenticated admin surface listing accounts pending hard-delete (`deletion_requests`, served by the existing partial index, no migration), keyset-paginated/filterable/count-summarized with identity-only PII discipline and HTML escaping; plus a role-gated, CSRF-protected, rate-limited, reason-required **expedite** action that brings a pending deletion's `scheduled_hard_delete_at` forward for the existing worker to execute, recording one immutable `admin_actions_log` row, and rejecting any target outside the pending/future population.

### Modified Capabilities
<!-- None. This change consumes deletion_requests data and the unchanged daily-worker scan;
     it does not alter the requirements of account-deletion or account-hard-delete-worker.
     Expedite mutates only the scheduled_hard_delete_at value the worker already keys on. -->

## Impact

- **New code** in `:backend:ktor` `admin` package: a new `deletionqueue` route + service + repository (mirroring `subscriptiongrace`), one Pebble template (frame 15), wiring into `AdminModule`. No changes to the deletion worker, the account-deletion flow, or the `deletion_requests` schema.
- **Reused infra**: `admin-destructive-action-rate-limit` (distinct expedite counter), `admin_sessions` CSRF, `admin_actions_log` append-only audit, the existing `deletion_requests_scheduled_idx` index.
- **Data exposure**: identity + deletion-lifecycle fields only (username, user id, `requested_at`, `scheduled_hard_delete_at`, `source`, expedite metadata) — no location/email/DOB.
- **Operational**: gives the operator a UU-PDP right-to-erasure oversight window + a manual expedite lever; replaces a raw-SQL-on-production path. If admin static assets are touched, the `htmx.min.js.SHA256SUMS` CI integrity check must be re-pinned.
