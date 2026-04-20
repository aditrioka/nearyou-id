# Auth — JWT

Defines the RS256 access-token format issued by the backend, the JWKS publication contract, the `kid` rotation slot, and the `token_version`-based instant-revocation check applied on every authenticated request.

See `docs/05-Implementation.md § Authentication Implementation` for background.

## Requirements

### Requirement: RS256 access tokens

The backend SHALL issue access tokens signed with RS256. Each token MUST have a `kid` header identifying the signing key, and a payload containing at minimum `sub` (user UUID), `iat`, `exp`, and `token_version` (integer matching `users.token_version` at issuance time). Access-token TTL MUST be 15 minutes.

#### Scenario: Token round-trip
- **WHEN** the issuer mints a token for user `u` with version `v`, then the verifier reads it
- **THEN** verification succeeds and the parsed claims include `sub == u`, `token_version == v`, `exp - iat == 900`

#### Scenario: kid header present
- **WHEN** any access token is decoded
- **THEN** the JOSE header contains a non-empty `kid` field

#### Scenario: Expired token rejected
- **WHEN** a token whose `exp` is in the past is verified
- **THEN** verification fails with an expiration error and the call is treated as 401

### Requirement: JWKS endpoint

`GET /.well-known/jwks.json` SHALL return a JSON document listing the public key(s) currently configured for signing, in JWKS format. Each key MUST include `kty`, `kid`, `use = "sig"`, `alg = "RS256"`, `n`, and `e`. The endpoint MUST be public (no auth) and SHOULD be cacheable (`Cache-Control` set).

#### Scenario: Endpoint returns valid JWKS
- **WHEN** an unauthenticated client calls `GET /.well-known/jwks.json`
- **THEN** the response is HTTP 200, `Content-Type: application/json`, and the body parses as `{ "keys": [ { ... } ] }` with at least one key

#### Scenario: kid in response matches token header
- **WHEN** an access token is minted and the JWKS is fetched
- **THEN** the token's `kid` header matches the `kid` of one key in the JWKS response

### Requirement: Token-version revocation check

Every authenticated request SHALL re-validate the token's `token_version` claim against `users.token_version` for the same `sub`. A mismatch MUST produce HTTP 401 with error code `token_revoked`.

#### Scenario: Same version accepted
- **WHEN** a request arrives with a JWT whose `token_version` equals `users.token_version` for that user
- **THEN** the request reaches the route handler and `call.principal()` returns a `UserPrincipal` with the user's id

#### Scenario: Stale version rejected
- **WHEN** `users.token_version` has been incremented after the JWT was issued and a request arrives with the old `token_version`
- **THEN** the response is HTTP 401 with body `{ "error": { "code": "token_revoked", ... } }`

### Requirement: Banned and suspended users blocked

The same middleware SHALL also reject requests whose subject user has `is_banned = TRUE` (HTTP 403, code `account_banned`) or whose `suspended_until` is in the future (HTTP 403, code `account_suspended`).

#### Scenario: Banned user
- **WHEN** the subject user has `is_banned = TRUE` and a request with an otherwise-valid JWT arrives
- **THEN** the response is HTTP 403, code `account_banned`

#### Scenario: Active suspension
- **WHEN** the subject user has `suspended_until > NOW()`
- **THEN** the response is HTTP 403, code `account_suspended`
