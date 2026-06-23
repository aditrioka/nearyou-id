## MODIFIED Requirements

### Requirement: Banned user blocked at sign-in

If the matched user has `is_banned = TRUE`, the response SHALL be HTTP 403 with code `account_banned`, and no normal access token or refresh token MUST be issued (no row is inserted into `refresh_tokens`). To preserve the user's right to contest the action (the `content-moderation-appeal` capability), the 403 response body SHALL additionally carry a short-lived **limited-scope appeal token**: an RS256 JWT bearing the user's `sub`, a `token_version` equal to the user's current `users.token_version`, a `scope` claim of `"appeal"`, and a TTL of at most 1 hour. This appeal token grants access ONLY to the ban-exempt appeal realm (per `auth-jwt`) — it is NOT a normal access token and confers no other authenticated access. This requirement applies equally to a 7-day-suspended user (`is_banned = TRUE`, `suspended_until > NOW()`) and a permanently-banned user (`suspended_until IS NULL`); both receive the appeal token, even though the mobile MVP surfaces the in-app appeal form to suspended users only (permanent bans use the support path per `mobile-appeal`).

#### Scenario: Banned user receives an appeal token, not normal tokens
- **WHEN** the matched user has `is_banned = TRUE`
- **THEN** the response is HTTP 403 code `account_banned`, no row is inserted into `refresh_tokens`, and the body carries an `appeal_token`

#### Scenario: Appeal token is limited-scope and current-version
- **WHEN** an `appeal_token` is issued to a banned or suspended user
- **THEN** it is an RS256 JWT carrying `scope = "appeal"`, a `token_version` equal to the user's current `users.token_version`, and a TTL of at most 1 hour

#### Scenario: Appeal token does not grant normal access
- **WHEN** an `appeal_token` is presented to any standard (non-appeal) authenticated route
- **THEN** the request is rejected with HTTP 401 (the standard realm refuses `scope = "appeal"` tokens — see `auth-jwt`), so the limited token confers no normal access
