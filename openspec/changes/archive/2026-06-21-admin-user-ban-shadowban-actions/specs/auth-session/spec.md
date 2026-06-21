## ADDED Requirements

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
