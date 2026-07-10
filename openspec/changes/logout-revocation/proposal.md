# logout-revocation

## Why

Two tracked pre-launch gaps make logout weaker than users (and the security model) assume, both filed from review engagements and combined here per operator direction ([#225](https://github.com/aditrioka/nearyou-id/issues/225) + [#268](https://github.com/aditrioka/nearyou-id/issues/268)):

1. **FCM tokens survive logout** (#225). `POST /api/v1/auth/logout` / `logout-all` revoke refresh tokens only — nothing deletes the caller's `user_fcm_tokens` rows. On a shared device (explicitly supported by the fcm-token-registration family-shared rationale), the signed-out account's pushes keep arriving indefinitely: the token stays FCM-valid (same app instance ⇒ no `UNREGISTERED` prune) and the 30-day stale-cleanup worker only catches rows whose `last_seen_at` goes quiet. iOS alert pushes render OS-side ("<actor> mengirim pesan") before any client check could suppress them.
2. **Mobile logout never tells the server** (#268). Settings "Keluar" is a pure client-side token wipe (`SecureTokenStore.clear()` + `replaceAll` to sign-in) — the `mobile-settings` spec currently *requires* no server call. A wiped-but-exfiltrated refresh token therefore stays valid for up to 30 days.

Fixing (2) gives the mobile app a real logout call; fixing (1) makes that call also stop the pushes. Shipping them together closes the full loop in one vertical slice (docs/12).

## What Changes

- **Backend — `POST /api/v1/auth/logout`**: `LogoutRequest` gains an optional `fcm_token` field. When present, the endpoint deletes the caller's `user_fcm_tokens` row(s) for that token (in addition to revoking the supplied refresh token, unchanged).
- **Backend — `POST /api/v1/auth/logout-all`**: deletes ALL of the caller's `user_fcm_tokens` rows in the same transaction as the refresh-token family deletion + `token_version` bump.
- **Mobile — Settings logout**: `confirmLogout()` stops being a pure client-side wipe. Before clearing `SecureTokenStore`, it best-effort calls `POST /api/v1/auth/logout` with the stored refresh token + the device's current FCM token (from `FcmTokenProvider`). The local wipe + `replaceAll` re-route ALWAYS proceed regardless of the server call's outcome (offline logout must never trap the user).
- **Deliberate deferral (defer-as-requirement)**: single-device logout does NOT bump `users.token_version` — the ≤15-minute access token lives until natural expiry (bumping would kill the user's other devices' sessions). `logout-all` already bumps and remains the "kick everything now" path.

No schema migration: `user_fcm_tokens` and its `(user_id, platform, token)` UNIQUE index already support the logout DELETE as an index seek.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `auth-session`: the "Logout endpoints" requirement is extended — `logout` accepts an optional `fcm_token` and deletes the matching `user_fcm_tokens` row(s); `logout-all` deletes all of the caller's `user_fcm_tokens` rows atomically with the refresh-token family deletion. A defer-as-requirement scenario pins the no-`token_version`-bump-on-single-logout decision.
- `fcm-token-registration`: adds the **logout deletion path** as a third documented `user_fcm_tokens` lifecycle-deletion contract (owned by `auth-session`), alongside the existing on-send-prune (`fcm-push-dispatch`) and stale-cleanup (`scheduled-retention-cleanup`) paths.
- `mobile-settings`: the "Logout clears the token store and routes to sign-in" requirement flips from "Logout SHALL require no server call" to "Logout SHALL best-effort call the server logout endpoint (refresh token + current FCM token) before the wipe; the wipe and re-route SHALL NOT be blocked by server-call failure."

## Impact

- **Backend**: `AuthRoutes.kt` (`LogoutRequest` + both logout handlers), `RefreshTokenService` (or a thin logout service seam) gains the FCM-delete + transactional `revokeAll`; `FcmTokenRepository` gains `deleteByUserAndToken` / `deleteAllForUser`. Tests in `:backend:ktor:test` (DB-tagged routes tests).
- **Mobile**: `SettingsViewModel.confirmLogout()` + a small logout API client seam (Koin-wired); `FcmTokenProvider` reuse. Unit tests (Robolectric/JVM). No UI change beyond the existing confirm dialog — not screenshot-gated, but logout flow re-verified.
- **Specs/docs**: delta specs for the three capabilities above; `docs/05` § Session Management revocation-latency table gains the FCM row.
- **Issues closed**: [#225](https://github.com/aditrioka/nearyou-id/issues/225), [#268](https://github.com/aditrioka/nearyou-id/issues/268) (PR body repeats `Closes` per issue).
