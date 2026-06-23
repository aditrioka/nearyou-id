# Auth — Session

## Purpose

Defines the refresh-token storage shape, rotation logic with a 30-second overlap window, family revocation on reuse detection, and the logout / logout-all behaviors.

See `docs/05-Implementation.md § Session Management` for background, including the revocation-latency table.
## Requirements
### Requirement: Refresh-token storage shape

Refresh tokens SHALL be persisted in the `refresh_tokens` table with one row per issuance. Each row MUST hold `id`, `family_id`, `user_id`, `token_hash` (SHA-256 of the raw token bytes), `created_at`, `expires_at`, and nullable `used_at` / `last_used_at` / `revoked_at`. The raw token MUST NOT be persisted.

#### Scenario: Token hashed at rest
- **WHEN** a refresh token is issued and inserted
- **THEN** the row's `token_hash` equals SHA-256(raw token bytes) and the raw token does not appear in any other column

#### Scenario: TTL is 30 days
- **WHEN** a refresh token is issued at time `t`
- **THEN** the row's `expires_at` equals `t + 30 days` (with second-level tolerance)

### Requirement: Rotation with 30-second overlap

`POST /api/v1/auth/refresh` SHALL accept a refresh token, mark it `used_at = NOW()`, and issue a new refresh token with the same `family_id` plus a new access token. A token that has already been used MAY be presented again within a 30-second overlap window without triggering reuse detection.

#### Scenario: First rotation succeeds
- **WHEN** a valid unused refresh token is presented
- **THEN** the response is HTTP 200 with new `access_token` and `refresh_token`, and the old row's `used_at` is set

#### Scenario: Re-presented within overlap
- **WHEN** the same refresh token is presented again ≤ 30 seconds after first use
- **THEN** rotation succeeds (idempotent for the overlap window)

### Requirement: Reuse detection revokes the family

When a refresh token is presented after the 30-second overlap has elapsed AND the row's `used_at` is non-null, the server SHALL: (a) mark every row sharing the same `family_id` as `revoked_at = NOW()`, (b) increment `users.token_version` for the owning user, and (c) respond HTTP 401 with code `token_reuse_detected`.

#### Scenario: Reuse outside overlap
- **WHEN** a refresh token is re-presented > 30 seconds after first use
- **THEN** the response is HTTP 401 code `token_reuse_detected`, every row in the same `family_id` has `revoked_at` set, and `users.token_version` has incremented by 1

### Requirement: Logout endpoints

`POST /api/v1/auth/logout` (authenticated, accepts a refresh token in the body) SHALL revoke that single refresh token. `POST /api/v1/auth/logout-all` (authenticated) SHALL delete every refresh token for the calling user AND increment `users.token_version`.

#### Scenario: Single-device logout
- **WHEN** a user calls `POST /api/v1/auth/logout` with a valid refresh token
- **THEN** that token's row is `revoked_at`-stamped, but other tokens for the same user remain active

#### Scenario: Global logout
- **WHEN** a user calls `POST /api/v1/auth/logout-all`
- **THEN** every `refresh_tokens` row for that user is deleted AND `users.token_version` increments by 1

### Requirement: Token-version increment kicks all sessions

Any operation that increments `users.token_version` SHALL invalidate every previously-issued access token for that user no later than the next request (because the access-token middleware checks the version against the DB on every call).

#### Scenario: Version bump invalidates outstanding access token
- **WHEN** a user with an unexpired access token is the subject of a `token_version` increment
- **THEN** the next request bearing that access token receives HTTP 401 code `token_revoked`

### Requirement: Refresh denies a banned or soft-deleted account

`POST /api/v1/auth/refresh` SHALL re-load the token owner's current account state and SHALL NOT issue a new access token when that account is in a session-terminating state — mirroring the per-request `AuthPlugin` account-state gate so a refresh cannot mint a fresh access token for an account that every authenticated route would already 403. Specifically, after the presented refresh token is validated, when the owning user is:
- soft-deleted (`deleted_at IS NOT NULL`) → the response SHALL be HTTP 401 with code `token_revoked` and no new access token;
- banned (`is_banned = TRUE`, covering both a permanent ban with `suspended_until IS NULL` and an active time-bound suspension) → the response SHALL be HTTP 403 with code `account_banned` and no new access token.

This closes the gap whereby a permanently-banned user — whose ban (mirroring the shipped suspend / report-queue ban) bumps no `token_version` and deletes no refresh token — could otherwise rotate a fresh ~15-minute access token repeatedly until the 30-day refresh-token TTL elapsed. The implementation SHALL ensure a denied refresh leaves the caller no usable new access token (and SHOULD avoid handing back a usable rotated refresh token to a denied caller). A not-found owner continues to return HTTP 401 `token_revoked` as today.

#### Scenario: A permanently-banned owner cannot refresh

- **GIVEN** a user with a valid unused refresh token whose account has since been permanently banned (`is_banned = TRUE`, `suspended_until IS NULL`)
- **WHEN** the user calls `POST /api/v1/auth/refresh` with that refresh token
- **THEN** the response SHALL be HTTP 403 with code `account_banned` AND no new access token SHALL be issued

#### Scenario: A suspended owner cannot refresh

- **GIVEN** a user with a valid unused refresh token whose account is under an active 7-day suspension (`is_banned = TRUE`, `suspended_until` in the future)
- **WHEN** the user calls `POST /api/v1/auth/refresh` with that refresh token
- **THEN** the response SHALL be HTTP 403 with code `account_banned` AND no new access token SHALL be issued

#### Scenario: A soft-deleted owner cannot refresh

- **GIVEN** a user with a valid unused refresh token whose account has been soft-deleted (`deleted_at IS NOT NULL`)
- **WHEN** the user calls `POST /api/v1/auth/refresh` with that refresh token
- **THEN** the response SHALL be HTTP 401 with code `token_revoked` AND no new access token SHALL be issued

#### Scenario: An active account still refreshes successfully

- **GIVEN** a user with a valid unused refresh token whose account is active (`is_banned = FALSE`, `deleted_at IS NULL`)
- **WHEN** the user calls `POST /api/v1/auth/refresh` with that refresh token
- **THEN** the response SHALL be HTTP 200 with a new `access_token` and `refresh_token` (the rotation behavior is unchanged for healthy accounts)

