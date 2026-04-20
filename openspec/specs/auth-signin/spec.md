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

If the matched user has `is_banned = TRUE`, the response SHALL be HTTP 403 with code `account_banned`. No tokens MUST be issued.

#### Scenario: Banned user attempts sign-in
- **WHEN** the matched user has `is_banned = TRUE`
- **THEN** the response is HTTP 403 code `account_banned` and no row is inserted into `refresh_tokens`

### Requirement: device_fingerprint_hash recorded but not required

The optional `device_fingerprint_hash` field on the request body SHALL be persisted to `refresh_tokens.device_fingerprint_hash` when present. It MUST NOT be required for sign-in to succeed (attestation lands later).

#### Scenario: Fingerprint passed
- **WHEN** the request body includes `device_fingerprint_hash`
- **THEN** the corresponding `refresh_tokens` row's `device_fingerprint_hash` equals the supplied value

#### Scenario: Fingerprint absent
- **WHEN** the request body omits `device_fingerprint_hash`
- **THEN** sign-in still succeeds and the row's `device_fingerprint_hash` is NULL

