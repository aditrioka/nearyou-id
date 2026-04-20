## ADDED Requirements

### Requirement: Signup endpoint contract

`POST /api/v1/auth/signup` SHALL accept a JSON body `{ "provider": "google" | "apple", "id_token": string, "date_of_birth": string (ISO-8601 date, e.g. "1995-03-14"), "device_fingerprint_hash": string? }`. On success it SHALL return `{ "access_token": string, "refresh_token": string, "expires_in": 900 }` with HTTP 201. The endpoint MUST be unauthenticated (no Bearer token required).

#### Scenario: Body shape accepted
- **WHEN** an unauthenticated client calls the endpoint with the four documented fields and a valid payload
- **THEN** the request reaches the handler (no 400 from shape validation) and proceeds through the signup pipeline

#### Scenario: Malformed DOB
- **WHEN** the `date_of_birth` field is missing, empty, or does not parse as ISO-8601 YYYY-MM-DD
- **THEN** the response is HTTP 400 with code `invalid_request`

#### Scenario: Missing provider or id_token
- **WHEN** `provider` or `id_token` is missing
- **THEN** the response is HTTP 400 with code `invalid_request`

### Requirement: Provider ID-token verification at signup

The endpoint SHALL reuse the same `ProviderIdTokenVerifier` contract as sign-in (shared JWKS cache, same `aud` / `iss` / `exp` validation rules). A failed verification MUST produce HTTP 401 with code `invalid_id_token` and NO writes to any table.

#### Scenario: Invalid signature
- **WHEN** an `id_token` whose signature does not verify against the provider JWKS is submitted
- **THEN** the response is HTTP 401 with code `invalid_id_token` AND no rows are inserted in `users` or `rejected_identifiers`

#### Scenario: Wrong audience
- **WHEN** the `id_token` is otherwise valid but its `aud` is not in the configured allow-list
- **THEN** the response is HTTP 401 with code `invalid_id_token`

### Requirement: Rejected-identifier pre-check

Before parsing `date_of_birth`, the endpoint MUST hash the verified provider `sub` (SHA-256) and check `rejected_identifiers` for a row matching `(identifier_hash, identifier_type)`. If a row exists, the endpoint SHALL return HTTP 403 with code `user_blocked` and the canonical blocked body (see Requirement: privacy-preserving blocked body). No writes MUST occur and the DOB MUST NOT be further processed.

#### Scenario: Previously rejected identifier retries
- **WHEN** the hashed provider subject + type is already present in `rejected_identifiers`
- **THEN** the response is HTTP 403 code `user_blocked` AND no row is inserted into `users` AND no additional row is inserted into `rejected_identifiers`

#### Scenario: Pre-check runs before DOB parsing
- **WHEN** the identifier is already in `rejected_identifiers` AND the supplied DOB is malformed
- **THEN** the response is HTTP 403 code `user_blocked` (the blocked check wins over the DOB format check)

### Requirement: User-exists collision returns 409

If the verified provider subject hashes to a value that already matches an existing `users` row (either `google_id_hash` or `apple_id_hash`), the endpoint SHALL return HTTP 409 with code `user_exists`. Callers SHOULD redirect to `/api/v1/auth/signin`. No writes MUST occur.

#### Scenario: Provider account already has a user
- **WHEN** the hashed provider subject already appears in `users.google_id_hash` (or `apple_id_hash`)
- **THEN** the response is HTTP 409 code `user_exists` and no new `users` row is inserted

### Requirement: Privacy-preserving blocked body

Both the rejected-identifier pre-check path AND the under-18 rejection path (see age-gate capability) MUST return an HTTP 403 response with exactly the same body: `{ "error": "user_blocked", "message": <localized-generic-string> }`. The response bytes MUST be identical. Only server-side logs MAY differ to preserve auditability.

#### Scenario: Indistinguishable bodies
- **WHEN** two signup attempts are made — one with an already-rejected identifier, one with a fresh identifier declaring DOB under 18
- **THEN** both responses have HTTP status 403 AND the response body bytes are byte-identical

### Requirement: Atomic user creation

On the happy path, the signup handler SHALL perform username generation, invite-code-prefix derivation, and `users` INSERT inside a single DB transaction. If any step fails, the transaction MUST roll back; no partial `users` row and no leftover writes MUST persist.

#### Scenario: Transaction rollback on unique violation after retries
- **WHEN** username generation exhausts its retry budget AND the fallback `{adj}_{noun}_{uuid8hex}` INSERT itself raises `unique_violation`
- **THEN** the transaction is rolled back AND the response is HTTP 503 with code `username_generation_failed` AND no partial row exists in `users`

#### Scenario: Token issuance after COMMIT
- **WHEN** the `users` INSERT commits
- **THEN** the access token + refresh token are issued AND the refresh token row is inserted in a subsequent transaction referencing the newly created `users.id`

### Requirement: Token pair returned mirrors sign-in

On success, the issued tokens MUST be indistinguishable from sign-in output: the same RS256 access-token format (15-minute TTL, `token_version = 0`), the same refresh-token hashing + `refresh_tokens` insert rule, and the same `expires_in: 900` field.

#### Scenario: Refresh token round-trip
- **WHEN** signup succeeds and the caller submits the returned `refresh_token` to `POST /api/v1/auth/refresh` within the TTL window
- **THEN** `/refresh` returns a new access + refresh pair

### Requirement: Error taxonomy stability

The endpoint SHALL use exactly the following error codes, no others: `invalid_request`, `invalid_id_token`, `user_blocked`, `user_exists`, `username_generation_failed`. Introducing a new code in this capability requires a spec change.

#### Scenario: Code stability
- **WHEN** any non-200 response is returned from `/signup`
- **THEN** the response `error` field is one of the five listed codes
