## MODIFIED Requirements

### Requirement: Banned and suspended users blocked

The same middleware SHALL also reject requests whose subject user has `is_banned = TRUE` (HTTP 403, code `account_banned`) or whose `suspended_until` is in the future (HTTP 403, code `account_suspended`).

**Single carve-out — the ban-exempt appeal realm.** The backend SHALL also provide one dedicated, separately-named authenticated realm (the "appeal" realm) that performs the full JWT signature and `token_version` revocation check, rejects a soft-deleted (`deleted_at IS NOT NULL`) or unknown subject, and populates the `UserPrincipal`, but does NOT apply the `is_banned` / `suspended_until` 403 short-circuit. This realm MUST be mounted on ONLY the appeal-submission and own-appeal-status routes (per the `content-moderation-appeal` capability), so an actioned user can contest the action. The shared per-request `users`-row SELECT and `token_version` check are factored so both realms reuse them, differing only in whether the `is_banned` / `suspended_until` gate short-circuits.

**Limited-scope tokens are confined to the appeal realm.** The limited-scope appeal token issued at sign-in (per `auth-signin`) carries `scope = "appeal"`. Every standard (non-appeal) authenticated realm MUST reject any token bearing `scope = "appeal"` with HTTP 401 `token_revoked`, so a limited appeal token can never reach a normal authenticated route. The appeal realm itself does not require a particular `scope` (a normal-scope token reaching it simply authenticates and is then handled by the route — e.g. a non-banned caller receives `409 no_actionable_moderation`); the confinement is enforced on the standard side. As before, missing/invalid-signature requests never reach either validate block (the underlying JWT provider rejects them with 401, no `UserPrincipal`).

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

#### Scenario: Standard realm rejects a limited appeal-scope token
- **WHEN** a token bearing `scope = "appeal"` is presented to any standard (non-appeal) authenticated route
- **THEN** the response is HTTP 401, code `token_revoked`, and no normal access is granted

#### Scenario: Appeal realm rejects an unauthenticated request
- **WHEN** a request to an appeal-realm route arrives with no `Authorization` header, or a JWT whose signature does not verify
- **THEN** the response is HTTP 401 and no `UserPrincipal` is populated (the realm authenticates a *valid* token; it does not authenticate an absent or forged one)

#### Scenario: Appeal realm rejects a soft-deleted subject
- **WHEN** a request to an appeal-realm route arrives from a subject whose `users.deleted_at IS NOT NULL`, even with a matching `token_version`
- **THEN** the request is rejected (a deleted account cannot submit or read appeals; the `deleted_at` gate is retained, only the ban gate is relaxed)
