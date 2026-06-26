## MODIFIED Requirements

### Requirement: Banned user blocked at sign-in

If the matched user has `is_banned = TRUE`, the response SHALL be HTTP 403 with code `account_banned`, and no normal access token or refresh token MUST be issued (no row is inserted into `refresh_tokens`). To preserve the user's right to contest the action (the `content-moderation-appeal` capability), the 403 response body SHALL additionally carry a short-lived **limited-scope appeal token**: an RS256 JWT bearing the user's `sub`, a `token_version` equal to the user's current `users.token_version`, a `scope` claim of `"appeal"`, and a TTL of at most 1 hour. This appeal token grants access ONLY to the ban-exempt appeal realm (per `auth-jwt`) — it is NOT a normal access token and confers no other authenticated access.

This requirement applies equally to a 7-day-suspended user (`is_banned = TRUE`, `suspended_until > NOW()`) and a permanently-banned user (`suspended_until IS NULL`); both receive the appeal token. To let the client route the two states differently (the in-app appeal form for a suspension vs the support path for a permanent ban, per `mobile-appeal`), the 403 response body SHALL additionally carry a `suspended_until` field echoing the matched user's `users.suspended_until` column: **`null`** for a permanently-banned user, and the **ISO-8601 timestamp** of the suspension expiry for a suspended user. The response code stays `account_banned` for both states (the distinction is carried by `suspended_until`, not a separate code), preserving the appeal-token contract above. The `suspended_until` value is a moderation-action expiry already exposed to the actioned user via the appeal flow; it carries no other user's data and no spatial/PII payload.

#### Scenario: Banned user receives an appeal token, not normal tokens
- **WHEN** the matched user has `is_banned = TRUE`
- **THEN** the response is HTTP 403 code `account_banned`, no row is inserted into `refresh_tokens`, and the body carries an `appeal_token`

#### Scenario: Appeal token is limited-scope and current-version
- **WHEN** an `appeal_token` is issued to a banned or suspended user
- **THEN** it is an RS256 JWT carrying `scope = "appeal"`, a `token_version` equal to the user's current `users.token_version`, and a TTL of at most 1 hour

#### Scenario: Appeal token does not grant normal access
- **WHEN** an `appeal_token` is presented to any standard (non-appeal) authenticated route
- **THEN** the request is rejected with HTTP 401 (the standard realm refuses `scope = "appeal"` tokens — see `auth-jwt`), so the limited token confers no normal access

#### Scenario: Suspended user's 403 carries the suspension expiry
- **WHEN** the matched user has `is_banned = TRUE` AND `suspended_until > NOW()`
- **THEN** the 403 `account_banned` body's `suspended_until` field is the ISO-8601 timestamp of the user's `suspended_until`, and an `appeal_token` is present

#### Scenario: Permanently-banned user's 403 carries a null expiry
- **WHEN** the matched user has `is_banned = TRUE` AND `suspended_until IS NULL`
- **THEN** the 403 `account_banned` body's `suspended_until` field is `null`, and an `appeal_token` is present
