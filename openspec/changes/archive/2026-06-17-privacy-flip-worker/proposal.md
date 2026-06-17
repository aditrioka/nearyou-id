## Why

Premium users can opt their profile private (`private_profile_opt_in`); when Premium lapses they must be downgraded to public, but only after a **72-hour grace window** with a warning (UU PDP privacy-downgrade flow, `docs/02` § "Privacy Downgrade Flow (Premium to Free)", Phase 4 item #6). Today nothing sets `users.privacy_flip_scheduled_at`: the shipped `subscription-billing-webhook` **defers** the scheduling, no worker applies an elapsed flip, and the already-shipped admin `privacy-flip-monitor` surface is therefore permanently empty. This change implements the missing scheduling + acting worker, completing the premium-downgrade privacy path and giving the admin monitor real data.

## What Changes

- On a RevenueCat **`EXPIRATION`** event, the webhook handler — in its existing single transaction — additionally schedules a privacy flip **only for users with `private_profile_opt_in = TRUE`**: `privacy_flip_scheduled_at = COALESCE(privacy_flip_scheduled_at, NOW() + INTERVAL '72 hours')` (idempotent — a re-delivered or second `EXPIRATION` must not push the deadline later), emits a `privacy_flip_warning` in-app notification (`body_data = {privacy_flip_scheduled_at}`), and dispatches the matching FCM push post-commit. Non-private users are unaffected (status → `free` only). The existing `subscription_expired` notification continues to fire for everyone.
- On an **`INITIAL_PURCHASE` / `RENEWAL`** (Premium re-activation) the handler additionally **clears** `privacy_flip_scheduled_at = NULL` in the same UPDATE — re-subscribing within the window cancels the pending flip (unconditional + idempotent).
- A new hourly internal worker **`POST /internal/privacy-flip-worker`** applies elapsed flips: for every user past the deadline it sets `private_profile_opt_in = FALSE` + clears `privacy_flip_scheduled_at`, writing one immutable `admin_actions_log` audit row per flip attributed to the seeded `system` sentinel actor. Idempotent (a re-run matches zero already-cleared rows) and atomic (a failed audit write rolls back the flip).
- **No Flyway migration.** All schema already exists: `privacy_flip_scheduled_at` + `users_privacy_flip_idx` (V2), the `privacy_flip_warning` notification type in the V10 catalog CHECK, the seeded `system` actor (V18), and `admin_actions_log.action_type` as a plain `VARCHAR(64)` with no enum CHECK (V16).

## Capabilities

### New Capabilities
- `privacy-flip-worker`: The hourly internal worker (`POST /internal/privacy-flip-worker`, OIDC-gated on its own subtree) that flips elapsed-grace private profiles to public, clears the scheduled timestamp, busts no cache (profile reads are uncached today), and writes a per-flip `system_privacy_flip_applied` audit row attributed to the system sentinel actor — idempotent and atomic, mirroring the shipped `suspension-unban-worker` internal-worker pattern.

### Modified Capabilities
- `subscription-billing-webhook`: The `EXPIRATION` transition now also schedules the 72h privacy flip (private users only) + emits the `privacy_flip_warning` notification; the `INITIAL_PURCHASE` / `RENEWAL` transition now also clears any pending flip on re-activation. The shipped "72h privacy-flip scheduling is deferred" requirement is flipped from deferred → implemented (RENAMED + MODIFIED).

## Impact

- **Code:** `backend/ktor` `subscription/SubscriptionService.kt` + `SubscriptionEventRepository.kt` (status-apply extended to schedule/clear + conditional warning emit); new `privacy-flip-worker` route + worker (mirrors `admin/UnbanWorkerRoute.kt` + `SuspensionUnbanWorker.kt`); mounted under `route("/internal")` in `Application.kt` with `InternalEndpointAuth` on its own `/privacy-flip-worker` subtree.
- **Behavior already in place (NOT modified):** the "effectively still private during the 72h window" read short-circuit already exists in `JdbcUserProfileReader.kt` (`OR privacy_flip_scheduled_at > now()`) — this change only makes the column get set/cleared. The shipped admin `privacy-flip-monitor` begins showing real rows.
- **Ops:** a new Cloud Scheduler job (hourly) targets `/internal/privacy-flip-worker` with an OIDC identity token (same shape as the daily `unban-worker` job) — config, not code in this change.
- **Out of scope (still deferred to their own changes):** the time-based grace-elapse downgrade worker (`premium_billing_retry` → `free`), referral `GRANT` entitlement stacking, and any future profile read-cache bust wiring.
