# auth-session — delta (logout-revocation)

## MODIFIED Requirements

### Requirement: Logout endpoints

`POST /api/v1/auth/logout` (authenticated, accepts a refresh token in the body) SHALL revoke that single refresh token. The request body MAY additionally carry an optional `fcm_token` field; when present, the endpoint SHALL delete the caller's `user_fcm_tokens` row(s) matching `(user_id, token)` — regardless of whether the supplied refresh token was found or still valid (an already-rotated refresh token MUST still stop pushes to the device). `POST /api/v1/auth/logout-all` (authenticated) SHALL, in a single database transaction: delete every refresh token for the calling user, increment `users.token_version`, AND delete every `user_fcm_tokens` row for the calling user.

#### Scenario: Single-device logout
- **WHEN** a user calls `POST /api/v1/auth/logout` with a valid refresh token
- **THEN** that token's row is `revoked_at`-stamped, but other tokens for the same user remain active

#### Scenario: Single-device logout deletes the supplied FCM token row
- **GIVEN** a user with a `user_fcm_tokens` row for token `T` (and another row for token `U`)
- **WHEN** the user calls `POST /api/v1/auth/logout` with a valid refresh token and `fcm_token = T`
- **THEN** the row for `T` is deleted AND the row for `U` remains

#### Scenario: FCM row deleted even when the refresh token is stale
- **GIVEN** a user whose supplied refresh token has already been rotated away (not found by hash)
- **WHEN** the user calls `POST /api/v1/auth/logout` with that stale refresh token and `fcm_token = T`
- **THEN** the response is HTTP 204 AND the caller's `user_fcm_tokens` row for `T` is deleted

#### Scenario: Logout without fcm_token deletes no FCM rows
- **WHEN** a user calls `POST /api/v1/auth/logout` with a valid refresh token and no `fcm_token` field
- **THEN** the refresh token is revoked AND the user's `user_fcm_tokens` rows are unchanged

#### Scenario: Global logout
- **WHEN** a user calls `POST /api/v1/auth/logout-all`
- **THEN** every `refresh_tokens` row for that user is deleted AND `users.token_version` increments by 1 AND every `user_fcm_tokens` row for that user is deleted

#### Scenario: Single-device logout does not bump token_version (deliberate deferral)
- **WHEN** a user calls `POST /api/v1/auth/logout` (with or without `fcm_token`)
- **THEN** `users.token_version` is unchanged — the device's unexpired access token lives until natural expiry (≤ access-token TTL); immediate all-session invalidation remains `logout-all`'s job
