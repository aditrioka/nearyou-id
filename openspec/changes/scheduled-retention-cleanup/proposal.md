## Why

Three tables grow without bound today: `refresh_tokens`, `notifications`, and `user_fcm_tokens`. Their retention policies are written into the canonical spec — refresh tokens purge daily/weekly (`docs/05` §112), notifications auto-purge at 90 days (`docs/05` §582), stale FCM tokens drop weekly at 30 days (`docs/05` §1120) — but the worker that enforces them, the `/internal/cleanup` Cloud Scheduler endpoint named in all three sections, was never built. The result is a standing UU-PDP data-minimization gap (personal data retained past its policy window), a security liability (expired/stale refresh tokens linger as attack surface), and unbounded DB-cost growth. This is the last unbuilt backend retention sweep from Phase 3.5.

## What Changes

- **New `POST /internal/cleanup` internal worker** — a Cloud-Scheduler-invoked, OIDC-gated endpoint that runs bulk retention `DELETE`s and returns per-sweep row counts. It reuses the shipped internal-worker pattern (`privacy-flip-worker`, `suspension-unban-worker`): OIDC verifier mounted on the worker's **own** `/cleanup` route subtree only (never the shared `/internal` node, so it cannot capture the sibling shared-secret-authed `/internal/revenuecat-webhook`); idempotent; `200` with a per-sweep count body; exactly one structured INFO log line per run; `401` on a missing/invalid OIDC token (deletes nothing); classified `500` (`timeout`/`connection_refused`/`unknown`) on failure without leaking the raw exception.
- **Three in-scope retention sweeps** (all target tables + indexes already exist — **no Flyway migration**):
  - `refresh_tokens`: delete expired (`expires_at < NOW() - INTERVAL '1 day'`) and stale (`last_used_at < NOW() - INTERVAL '90 days'`), including already-revoked rows.
  - `notifications`: purge `created_at < NOW() - INTERVAL '90 days'` (all types, no special-casing).
  - `user_fcm_tokens`: delete stale `last_seen_at < NOW() - INTERVAL '30 days'`.
- **Explicitly NOT in scope (already shipped):** the FCM on-send `404/410` (`UNREGISTERED`/`SENDER_ID_MISMATCH`) immediate single-token delete already runs in `infra/fcm/FcmDispatcher.kt` via `UserFcmTokenReader.deleteTokenIfStale` (the `fcm-push-dispatch` spec). This change adds only the **scheduled bulk** stale sweep, not the event-driven send-path delete.
- **Explicitly deferred** (captured as scope-boundary requirements so a follow-up has something to MODIFY): WebAuthn challenge cleanup (`admin_webauthn_challenges`, `docs/05` §705 — deferred until the multi-admin WebAuthn period, since nothing writes that table yet) and moderation-queue/reports 1-year archival (`docs/08` Phase 3.5 item 12 — a distinct archival concern on a 1-year horizon).

## Capabilities

### New Capabilities
- `scheduled-retention-cleanup`: the `/internal/cleanup` OIDC-gated scheduled worker that enforces the refresh-token, notifications, and FCM-token retention windows via bulk `DELETE` sweeps, returns per-sweep counts, emits one structured run log, and explicitly bounds out the deferred WebAuthn-challenge and moderation/reports-archival sweeps.

### Modified Capabilities
<!-- None. The FCM on-send 404/410 delete already lives in fcm-push-dispatch and is not modified here; the deferred WebAuthn/moderation sweeps are bounded by this new capability's own requirements, not changes to existing specs. -->

## Impact

- **Code:** new worker route + JDBC repository under `:backend:ktor` (`internal/` route subtree + a retention-cleanup repository on the pool-bounded DB dispatcher, `docs/11` §3.2); wiring into `Application.kt`'s `/internal/*` block alongside the existing workers. No mobile, no admin-UI, no infra-module changes.
- **Schema:** none — reuses `refresh_tokens_expires_idx` (V2), `notifications_user_all_idx` (V10), `user_fcm_tokens_last_seen_idx` (V14).
- **Ops:** one new Cloud Scheduler job (operator-provisioned) hitting `/internal/cleanup` with a Google OIDC identity token, mirroring the existing unban / privacy-flip / hard-delete schedules. Secrets/auth reuse the internal-endpoint OIDC audience binding — no new secret slots.
- **Specs:** new `scheduled-retention-cleanup` capability spec.
