## MODIFIED Requirements

### Requirement: Banned and suspended users blocked

The same middleware SHALL also reject requests whose subject user has `is_banned = TRUE` (HTTP 403, code `account_banned`) or whose `suspended_until` is in the future (HTTP 403, code `account_suspended`).

**Single carve-out — the ban-exempt appeal realm.** The backend SHALL also provide one dedicated, separately-named authenticated realm (the "appeal" realm) that performs the full JWT signature and `token_version` revocation check and populates the `UserPrincipal`, but does NOT apply the `is_banned` / `suspended_until` 403 short-circuit. This realm MUST be mounted on ONLY the appeal-submission and own-appeal-status routes (per the `content-moderation-appeal` capability), so an actioned user can contest the action. Every other authenticated route continues to use the standard realm and its 403 short-circuit unchanged. The shared per-request `users`-row SELECT and `token_version` check are factored so both realms reuse them, differing only in whether the `is_banned` / `suspended_until` gate short-circuits.

#### Scenario: Banned user
- **WHEN** the subject user has `is_banned = TRUE` and a request with an otherwise-valid JWT arrives
- **THEN** the response is HTTP 403, code `account_banned`

#### Scenario: Active suspension
- **WHEN** the subject user has `suspended_until > NOW()`
- **THEN** the response is HTTP 403, code `account_suspended`

#### Scenario: Ban-exempt appeal realm authenticates a banned subject
- **GIVEN** a route mounted under the appeal realm
- **WHEN** a request arrives from a subject with `is_banned = TRUE` and a JWT whose `token_version` matches `users.token_version`
- **THEN** authentication succeeds (no `account_banned` / `account_suspended` short-circuit) and `call.principal()` returns the populated `UserPrincipal`

#### Scenario: Ban-exempt realm still enforces token revocation
- **WHEN** a request to an appeal-realm route arrives with a JWT whose `token_version` is older than `users.token_version`
- **THEN** the response is HTTP 401, code `token_revoked` (the realm relaxes ONLY the ban gate, not the revocation check)
