# logout-revocation — design

## Context

Backend logout today (`AuthRoutes.kt`): `POST /api/v1/auth/logout` → `RefreshTokenService.revokeSingle(userId, rawToken)` (hash-lookup, ownership check, `revoked_at` stamp); `POST /api/v1/auth/logout-all` → `revokeAll(userId)` = `tokens.deleteAllForUser(userId)` + `users.incrementTokenVersion(userId)` as two auto-commit statements. Neither touches `user_fcm_tokens`.

Mobile logout today (`SettingsViewModel.confirmLogout()`): `tokenStore.clear()` + `_loggedOut` → `replaceAll` to sign-in. No server call — currently *mandated* by the `mobile-settings` spec ("Logout SHALL require no server call").

`user_fcm_tokens` already has the UNIQUE `(user_id, platform, token)` index and a documented multi-owner deletion-path pattern (on-send-prune → `fcm-push-dispatch`; stale-cleanup → `scheduled-retention-cleanup`). `FcmTokenRegistrar` on mobile is stateless (re-registers on every session-active transition), so deleting rows server-side needs no mobile-side cache reset — the next sign-in re-registers naturally.

## Goals / Non-Goals

**Goals:**

- Logout (single + all) stops pushes to the signed-out account on that device / all devices (#225).
- Mobile logout revokes the refresh token server-side so an exfiltrated copy dies at logout, not at 30-day expiry (#268).
- Offline/failed-call logout still signs the user out locally — never trap the user behind a network error.

**Non-Goals:**

- No `token_version` bump on single-device logout (would kill the user's *other* sessions; the ≤15-min access token expiring naturally is the accepted residue — pinned as a defer-as-requirement scenario).
- No new `DELETE /api/v1/user/fcm-token` endpoint (#225 offered it as an alternative; the optional `fcm_token` field on the existing logout call is one round-trip and needs no extra mobile orchestration).
- No stale-token GC changes (existing on-send-prune + 30-day worker stay the backstop).
- No admin surface (nothing to moderate/observe beyond existing audit trails).

## Decisions

**D1 — `fcm_token` rides the existing logout request, optional.** `LogoutRequest` gains `fcm_token: String?` (`@SerialName("fcm_token")`, default null — older clients keep working). Alternative (separate `DELETE /api/v1/user/fcm-token` + client calls two endpoints) rejected: two round-trips, a second failure mode, and the delete must happen even when the client dies mid-logout — bundling it with the revoke is atomic from the client's perspective.

**D2 — Single-device logout deletes by `(user_id, token)`, not `(user_id, platform, token)`.** The request carries no `platform` field; a DELETE on `user_id + token` hits at most the caller's own rows (cross-platform token-string collision for the *same user* is not a real scenario, and over-deleting one's own row is harmless — re-registered on next sign-in). Keeps the wire format minimal. The delete is NOT gated on the refresh token being found/valid: an already-rotated-away refresh token must still stop the pushes.

**D3 — `logout-all` becomes one transaction.** Refresh-token family delete + `token_version` bump + `DELETE FROM user_fcm_tokens WHERE user_id = ?` run on a single JDBC connection with `autoCommit=false` (the established `SignupService`/`CreatePostService` pattern), replacing today's two auto-commit statements. Rationale (#225's explicit ask): a partial failure that revokes sessions but leaves push rows would silently reproduce the bug this change fixes. Single-device logout keeps two independent idempotent statements (revoke-by-hash needs service-level logic; there is no cross-statement invariant — a failed FCM delete leaves a row the GC paths already cover).

**D4 — Mobile calls the server BEFORE wiping, best-effort, then always wipes.** `confirmLogout()` order: (1) read refresh token from `SecureTokenStore` and current FCM token from `FcmTokenProvider` (nullable — no token ⇒ field omitted); (2) `POST /api/v1/auth/logout` via a new `AuthApiClient.logout()` (the Bearer must still exist, hence before the wipe); (3) `tokenStore.clear()` + `_loggedOut = true` unconditionally — any exception/non-2xx from (2) is swallowed (token-free diagnostic log only). No retry/backoff: a missed revoke degrades to today's behavior, and the wipe must not wait.

**D5 — FCM delete methods live on `FcmTokenRepository`.** `deleteByUserAndToken(userId, token)` + `deleteAllForUser(userId)` (the latter used inside D3's transaction via the shared connection). No new repository class; the table's write owner already exists.

## Risks / Trade-offs

- **[Exfiltrated *access* token survives single logout ≤15 min]** → accepted + spec-pinned (defer-as-requirement); `logout-all` is the immediate-kill path (token_version bump).
- **[Logout during an expired-access window can orphan one rotated refresh token]** → if the Bearer is expired at logout time, the shared client's Auth plugin first refreshes (rotating the refresh token), then retries the logout with the ORIGINAL body — the server revokes the pre-rotation token while the freshly-rotated one lives to its 30-day TTL. Nobody holds it post-wipe (it was written to the store and immediately wiped), so exploitability is ~nil; accepted under the best-effort posture. Escalation path if ever needed: revoke by family server-side instead of by token.
- **[Best-effort mobile call means a failed revoke is silent]** → degrades exactly to today's shipped behavior; refresh token also dies naturally in ≤30 days; diagnostic log line for observability.
- **[Longer logout tap-to-signin latency (one network call)]** → call runs in the existing `viewModelScope.launch`; failure path is bounded by the client's existing timeout config. UX shows the existing confirm-dialog flow; no spinner work needed for MVP.
- **[Older mobile builds send no `fcm_token`]** → field optional; their rows keep flowing to the existing GC paths — no regression.

## Migration Plan

No Flyway migration. Backend deploys first (accepts old + new request shape); mobile follows (sends the new field). Rollback = revert commit; no data shape changed.

## Open Questions

(none)
