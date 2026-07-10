# logout-revocation — tasks

## 1. Backend — FCM delete + logout endpoints

- [x] 1.1 `FcmTokenRepository`: add `deleteByUserAndToken(userId, token)` and `deleteAllForUser(userId)` (the latter callable on a caller-supplied `Connection` for D3's transaction), plus repository-level DB tests
- [x] 1.2 `LogoutRequest`: add optional `fcm_token: String?` (`@SerialName("fcm_token")`, default null)
- [x] 1.3 `POST /api/v1/auth/logout`: after `revokeSingle`, when `fcm_token` present delete the caller's matching `user_fcm_tokens` row(s); delete happens even when the refresh token is stale/not found (spec scenario)
- [x] 1.4 `POST /api/v1/auth/logout-all`: replace the two auto-commit statements with ONE transaction (autoCommit=false pattern) covering refresh-token family delete + `token_version` bump + `DELETE user_fcm_tokens WHERE user_id`
- [x] 1.5 Route tests (`AuthRoutesTest` or sibling, DB-tagged): the four new/changed scenarios — fcm row deleted (single), other-token row survives, stale-refresh-token still deletes + 204, no-fcm_token leaves rows, logout-all deletes all rows + bumps token_version, single logout does NOT bump token_version

## 2. Mobile — Settings logout server call

- [x] 2.1 `AuthApiClient`: add `logout(refreshToken, fcmToken?)` posting `POST /api/v1/auth/logout` (Bearer attached by the shipped Auth plugin); returns success/failure as a value, never throws to the caller
- [x] 2.2 `SettingsViewModel.confirmLogout()`: read refresh token + `FcmTokenProvider.currentToken()`, call `logout()` best-effort BEFORE `tokenStore.clear()`; wipe + `_loggedOut` fire unconditionally; Koin wiring for the new dependencies (fail-safe resolution so existing screen tests stay green)
- [x] 2.3 Unit tests: confirm-logout issues the request before the wipe (with + without an FCM token), failure path still wipes + routes, cancel issues no request

## 3. Specs / docs / lifecycle

- [x] 3.1 `docs/05-Implementation.md` § Session Management: add the FCM-row column/note to the revocation-latency table
- [x] 3.2 `openspec validate logout-revocation --strict` green; full pre-push gate (`ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + mobile unit tests)
- [x] 3.3 PR body: evidence + `Closes #225` and `Closes #268` (repeat the keyword per issue); keep title/body current at each phase boundary
