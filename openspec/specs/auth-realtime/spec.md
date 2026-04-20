# Auth — Realtime

Defines the Supabase-compatible HS256 token issuance contract used by the chat WSS path. The endpoint sits behind the standard auth middleware and exchanges a Ktor RS256 access token for a short-lived HS256 token signed with the Supabase project's JWT secret.

## Requirements

### Requirement: HS256 realtime-token endpoint

`GET /api/v1/realtime/token` SHALL be authenticated (requires a valid Ktor RS256 access token). It MUST return a Supabase-compatible HS256 JWT with claims `{ sub, role: "authenticated", iat, exp }`, TTL 1 hour, signed with the secret resolved by `secretKey(env, "supabase-jwt-secret")`.

#### Scenario: Authenticated caller receives token
- **WHEN** a request with a valid Ktor access token calls the endpoint
- **THEN** the response is HTTP 200 with body `{ "token": "<jws>", "expires_in": 3600 }` and the JWS verifies under HS256 with the configured Supabase secret

#### Scenario: Claims correct
- **WHEN** the returned token is decoded
- **THEN** its claims contain `sub` matching the caller's user id, `role == "authenticated"`, and `exp - iat == 3600`

### Requirement: Unauthenticated calls rejected

The endpoint MUST require authentication; an unauthenticated request SHALL receive HTTP 401.

#### Scenario: No bearer token
- **WHEN** a request without an Authorization header calls the endpoint
- **THEN** the response is HTTP 401

### Requirement: HS256 secret resolved through secretKey helper

The signing key SHALL be obtained via `secretKey(env, "supabase-jwt-secret")` so the staging vs production prefix is honored. Hardcoded secret strings MUST NOT appear in the codebase.

#### Scenario: Source code search
- **WHEN** searching the backend source for the literal string `"supabase-jwt-secret"`
- **THEN** the only occurrence is the call to `secretKey(env, "supabase-jwt-secret")`
