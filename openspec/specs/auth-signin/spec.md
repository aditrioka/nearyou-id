# Auth — Sign-in

## Purpose

Defines the Google / Apple ID-token verification flow on `POST /api/v1/auth/signin`, the user-lookup behavior (existing-only until the age-gate change adds signup), the provider-subject hashing rule, and the device-fingerprint persistence.
## Requirements
### Requirement: Sign-in endpoint contract

`POST /api/v1/auth/signin` SHALL accept a JSON body `{ "provider": "google" | "apple", "id_token": string, "device_fingerprint_hash": string? }`. On success it SHALL return `{ "access_token": string, "refresh_token": string, "expires_in": 900 }` with HTTP 200.

#### Scenario: Body shape
- **WHEN** an unauthenticated client calls the endpoint with a valid body
- **THEN** the request reaches the handler (no 400 from shape validation)

### Requirement: Provider ID-token verification

The endpoint SHALL verify the supplied ID token cryptographically against the provider's JWKS (Google: `https://www.googleapis.com/oauth2/v3/certs`; Apple: `https://appleid.apple.com/auth/keys`). It MUST validate `aud` against an allow-list configured per environment, validate `iss`, and reject expired tokens. JWKS responses MUST be cached respecting the response's `Cache-Control: max-age=N` header when present and falling back to a 1-hour default when absent or unparseable.

#### Scenario: Invalid signature
- **WHEN** an `id_token` whose signature does not verify against the provider JWKS is submitted
- **THEN** the response is HTTP 401 with code `invalid_id_token`

#### Scenario: Wrong audience
- **WHEN** the `id_token` is otherwise valid but its `aud` is not in the configured allow-list
- **THEN** the response is HTTP 401 with code `invalid_id_token`

#### Scenario: Expired provider token
- **WHEN** the `id_token`'s `exp` is in the past
- **THEN** the response is HTTP 401 with code `invalid_id_token`

### Requirement: Provider subject is hashed before lookup

The provider's `sub` claim SHALL be SHA-256-hashed before use; lookup queries `WHERE google_id_hash = ?` or `WHERE apple_id_hash = ?` MUST use the hash, never the raw provider id. The raw `sub` MUST NOT be persisted.

#### Scenario: Hash used for lookup
- **WHEN** verification succeeds
- **THEN** the user lookup query parameter equals SHA-256 of the provider `sub`

### Requirement: Existing-user sign-in only (signup deferred)

`POST /api/v1/auth/signin` SHALL NOT create users. If the provider-subject lookup returns no row, the endpoint MUST respond HTTP 404 with code `user_not_found`. User creation is the responsibility of the distinct `POST /api/v1/auth/signup` endpoint defined in the `auth-signup` capability. Callers that receive `user_not_found` from `/signin` SHOULD retry through `/signup` after collecting the `date_of_birth` required for account creation.

#### Scenario: Unknown provider id
- **WHEN** verification succeeds but no `users` row matches the hashed provider id
- **THEN** the response is HTTP 404 with code `user_not_found`

#### Scenario: Existing user signs in
- **WHEN** verification succeeds and a `users` row matches, with `is_banned = FALSE` and `suspended_until` null/past
- **THEN** the response is HTTP 200 with new `access_token` and `refresh_token` and `expires_in == 900`

#### Scenario: Signup path is distinct
- **WHEN** a client attempts to create a new user by calling `/signin` with an unknown provider subject
- **THEN** the response is HTTP 404 `user_not_found` (not 201, not auto-creation); the correct path is `POST /api/v1/auth/signup`

### Requirement: Banned user blocked at sign-in

If the matched user has `is_banned = TRUE`, the response SHALL be HTTP 403 with code `account_banned`, and no normal access token or refresh token MUST be issued (no row is inserted into `refresh_tokens`). To preserve the user's right to contest the action (the `content-moderation-appeal` capability), the 403 response body SHALL additionally carry a short-lived **limited-scope appeal token**: an RS256 JWT bearing the user's `sub`, a `token_version` equal to the user's current `users.token_version`, a `scope` claim of `"appeal"`, and a TTL of at most 1 hour. This appeal token grants access ONLY to the ban-exempt appeal realm (per `auth-jwt`) — it is NOT a normal access token and confers no other authenticated access.

This requirement applies equally to a 7-day-suspended user (`is_banned = TRUE`, `suspended_until > NOW()`) and a permanently-banned user (`suspended_until IS NULL`); both receive the appeal token. To let the client route the two states differently (the in-app appeal form for a suspension vs the support path for a permanent ban, per `mobile-appeal`), the 403 response body SHALL additionally carry a `suspended_until` field echoing the matched user's `users.suspended_until` column: the **ISO-8601 timestamp** of the suspension expiry for a suspended user, and **no value** for a permanently-banned user. Because the shared server JSON serializer omits null-valued fields (`explicitNulls = false`), a permanent ban's body has **no `suspended_until` key at all**; the field's **presence-with-a-value signals a suspension and its absence signals a permanent ban** (an explicit `null` is treated identically to absence). The response code stays `account_banned` for both states (the distinction is carried by `suspended_until`'s presence, not a separate code), preserving the appeal-token contract above. The `suspended_until` value is a moderation-action expiry already exposed to the actioned user via the appeal flow; it carries no other user's data and no spatial/PII payload.

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

#### Scenario: Permanently-banned user's 403 carries no suspension expiry
- **WHEN** the matched user has `is_banned = TRUE` AND `suspended_until IS NULL`
- **THEN** the 403 `account_banned` body has no `suspended_until` value (the field is absent — omitted by `explicitNulls = false` — or `null`), and an `appeal_token` is present

### Requirement: device_fingerprint_hash recorded but not required

The optional `device_fingerprint_hash` field on the request body SHALL be persisted to `refresh_tokens.device_fingerprint_hash` when present. It MUST NOT be required for sign-in to succeed (attestation lands later).

#### Scenario: Fingerprint passed
- **WHEN** the request body includes `device_fingerprint_hash`
- **THEN** the corresponding `refresh_tokens` row's `device_fingerprint_hash` equals the supplied value

#### Scenario: Fingerprint absent
- **WHEN** the request body omits `device_fingerprint_hash`
- **THEN** sign-in still succeeds and the row's `device_fingerprint_hash` is NULL

