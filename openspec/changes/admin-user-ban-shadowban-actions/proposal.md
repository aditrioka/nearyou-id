## Why

The admin user-moderation surface can suspend (7-day), unban, and warn — but an admin **cannot permanently ban** a user from the user page, can only shadow-ban a user **indirectly via the report queue**, and has **no way to reverse a shadow-ban at all** (`unban` clears `is_banned` only, never `is_shadow_banned`). The `admin-user-moderation` spec already names this gap explicitly: *"Standalone permanent-ban creation and shadow ban remain deferred follow-ups that will extend this capability with further ADDED requirements"* (follow-ups [#283](https://github.com/aditrioka/nearyou-id/issues/283) + [#284](https://github.com/aditrioka/nearyou-id/issues/284)). For a pre-launch 18+ location-based social app, direct permanent-ban and shadow-ban of bad actors from the user page is table-stakes moderation/safety capability.

## What Changes

- **Add three user-page moderation actions** on the existing `admin-user-moderation` capability, mirroring the shipped `suspend`/`unban` route structure (CSRF-gated, role-gated, malformed-`{id}`-safe, atomic state+audit(+notification) commit, audit attributed to the human admin):
  - `POST /admin/users/{id}/ban` — **permanent ban** (`is_banned = TRUE, suspended_until = NULL`), owner/admin only, rate-limited, sanitized `account_action_applied` notification, audit `user_banned`.
  - `POST /admin/users/{id}/shadow-ban` — **shadow ban** (`is_shadow_banned = TRUE`), all write roles, rate-limited, **no notification** (invisible by design), audit `user_shadow_banned`.
  - `POST /admin/users/{id}/shadow-unban` — **un-shadow-ban** (`is_shadow_banned = FALSE`), restorative companion that closes the reversal gap, all write roles, **not** rate-limited, no notification, audit `user_shadow_unbanned`.
- **Reuse the shipped enforcement helpers** (`applyPermanentBan` / `applyShadowBan` / `insertBanNotification` from the report-queue path) so there is exactly **one** ban / shadow-ban behavior across both entry points — no divergent second implementation.
- **Extend the destructive-action rate-limit set**: count `user_banned` + `user_shadow_banned` toward the per-admin 20/hour cap; `user_shadow_unbanned` is restorative and is **not** counted (mirrors `unban`).
- **Surface the new controls** on the `/admin/users/{id}` profile page (`hx-confirm` on every destructive action), reflecting current `is_banned` / `is_shadow_banned` state, redlined to the admin mockup board.
- **Close the refresh-path enforcement gap** so a permanent ban actually takes effect within ≤15 min (one access-token TTL) instead of being bypassable for up to 30 days: `POST /api/v1/auth/refresh` re-checks account state and denies a banned (or soft-deleted) owner a new access token, mirroring the per-request `AuthPlugin` gate it currently skips. This also closes the same pre-existing bypass for time-bound suspensions. (Surfaced by the proposal-phase security-lens review; operator chose to fix it in-scope rather than defer.)
- **No Flyway migration** — `admin_actions_log.action_type` is a free `VARCHAR(64)` and the `is_banned` / `suspended_until` / `is_shadow_banned` columns already exist.

## Capabilities

### New Capabilities
<!-- None — all deltas extend existing capabilities. -->

### Modified Capabilities
- `admin-user-moderation`: ADD three state-changing actions (permanent ban, shadow ban, un-shadow-ban) — routes, role-gating, CSRF, eligibility guards, atomic audit (+ ban notification), and the new `user_banned` / `user_shadow_banned` / `user_shadow_unbanned` audit action types.
- `admin-destructive-action-rate-limit`: MODIFY the destructive set to additionally count `user_banned` + `user_shadow_banned` (direct-`action_type` arm); `user_shadow_unbanned` stays uncounted.
- `admin-user-management`: MODIFY the `/admin/users/{id}` profile page to surface the ban / shadow-ban / shadow-unban controls (state-reflecting, `hx-confirm`).
- `auth-session`: ADD a refresh-endpoint account-state guard so `POST /api/v1/auth/refresh` denies a banned / soft-deleted owner a new access token (closes the permanent-ban refresh bypass; also covers suspension).

## Impact

- **Code**: `admin/routes/AdminUserModerationRoute.kt` (3 new routes), `admin/moderation/UserModerationRepository.kt` (3 new transactional methods reusing extracted ban/shadow helpers), `admin/auth/AdminAuditLogger.kt` (3 new action-type log methods), `admin/ratelimit/DestructiveActionRateLimiter.kt` (destructive-set extension), the admin user-profile Pebble template (controls), `AdminModule.kt` wiring if needed, and `auth/routes/AuthRoutes.kt` (`POST /api/v1/auth/refresh` account-state guard).
- **APIs**: 3 new authenticated admin POST routes under `/admin/users/{id}/`. The public `POST /api/v1/auth/refresh` gains an account-state denial (banned → 403 `account_banned`, soft-deleted → 401 `token_revoked`) — no change for healthy accounts. No mobile client change required (the offender-facing "account banned" surfaces already exist per `docs/03`).
- **Schema**: none (no migration).
- **Patterns**: reuses the existing admin backend-layering + audit + role-gate + CSRF + rate-limit patterns, and the existing `AuthPlugin` account-state-gate predicate (the refresh guard mirrors it) — **no new `docs/11` Pattern-Registry pattern introduced**.
- **Docs reconciliation**: `docs/06` § Suspension vs Ban prescribes a `token_version` bump + refresh-token deletion for the *ordinary* ban; the **shipped** suspend/unban/report-queue-ban deliberately do neither (`token_version` bump is reserved for the CSAM path per `docs/07`). This change mirrors shipped behavior for the column writes and instead closes the actual session-continuation hole at the refresh endpoint; the stale `docs/06` ordinary-ban prescription is tracked for a separate doc-reconciliation follow-up (not amended here).
