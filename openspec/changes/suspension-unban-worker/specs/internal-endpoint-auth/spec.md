## ADDED Requirements

### Requirement: `/internal/*` routes require Google OIDC bearer token

The Ktor backend SHALL gate every route mounted under the `/internal/*` route subtree behind a Ktor plugin (`InternalEndpointAuth`) that verifies a Google OIDC bearer token before dispatch. A request reaching an `/internal/*` route MUST present an `Authorization: Bearer <token>` header whose value is a Google-issued OIDC JWT. Missing, malformed, or non-bearer Authorization headers MUST short-circuit the request with HTTP `401 Unauthorized` before any handler logic runs.

The plugin MUST verify three properties on every request:

1. **Signature**: the JWT signature MUST validate against Google's published JWKS at `https://www.googleapis.com/oauth2/v3/certs`. JWKS responses MUST be cached with rotation-aware refresh — when a token's `kid` header references a key not present in cache, the verifier MUST force one JWKS refresh before rejecting.
2. **Audience**: the JWT `aud` claim MUST equal the configured audience value, supplied via Ktor environment config and resolved through the `secretKey(env, name)` helper convention. The audience name is `internal-oidc-audience` (per [`openspec/project.md`](openspec/project.md) secret-name convention). The audience value in production / staging equals the deployed Cloud Run service URL by Cloud Scheduler convention.
3. **Expiry**: the JWT `exp` claim MUST be in the future relative to the verification clock. The `iat` claim, when present, MUST NOT be in the future (with a 60-second skew tolerance to absorb clock drift).

Verification failure on any of the three properties MUST short-circuit with HTTP `401 Unauthorized`. The response body MUST NOT echo the offending token, JWT claims, signature failure detail, or any verifier exception message — only a short fixed vocabulary `error` field as documented in the response-shape requirement below.

The full original verifier exception MUST be logged at WARN with a token correlation id derived as the first 16 hex chars of `SHA-256(raw token bytes)` so operators can correlate failures with Cloud Scheduler invocation logs without ever logging JWT claims or the raw token. The `jti` claim is intentionally NOT used (logging any claim would conflict with the no-claims rule below); the truncated SHA-256 form is unconditional.

#### Scenario: Missing Authorization header is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent without any `Authorization` header
- **THEN** the response status is `401 Unauthorized` AND no handler logic runs

#### Scenario: Non-Bearer scheme is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent with `Authorization: Basic dXNlcjpwYXNz`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Malformed JWT structure is rejected
- **WHEN** a request to `POST /internal/unban-worker` is sent with `Authorization: Bearer not.a.jwt`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Invalid signature is rejected
- **WHEN** a request presents a JWT whose signature does not validate against Google's JWKS
- **THEN** the response status is `401 Unauthorized` AND the response body MUST NOT contain the offending token or the verifier's exception message

#### Scenario: Audience mismatch is rejected
- **WHEN** a request presents a Google-signed JWT whose `aud` claim is `https://example.com/other-service` and the configured `internal-oidc-audience` is `https://api-staging.nearyou.id`
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Expired token is rejected
- **WHEN** a request presents a JWT whose `exp` claim is 60 seconds in the past relative to the server clock
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Valid Cloud Scheduler token is admitted
- **WHEN** a request presents a Google-signed JWT whose signature validates, whose `aud` matches the configured audience, AND whose `exp` is in the future
- **THEN** the plugin admits the request to the route handler

#### Scenario: JWKS rotation refresh resolves the new key
- **WHEN** a request presents a JWT whose `kid` header references a key not in the JWKS cache AND that key IS present in the live Google JWKS endpoint (post-rotation)
- **THEN** the verifier forces one JWKS refresh, the cache is updated, signature verification succeeds against the refreshed key, AND the request is admitted to the route handler

#### Scenario: JWKS rotation refresh still does not resolve the kid
- **WHEN** a request presents a JWT whose `kid` header references a key not in the JWKS cache AND that key is also absent from the live Google JWKS endpoint after the forced refresh
- **THEN** the verifier returns `401` with body `{"error": "invalid_token"}` (no further refresh attempts; no infinite loop)

### Requirement: Plugin is mounted on the `/internal/*` subtree, with vendor-webhook opt-out

The plugin MUST be mounted such that every route under `/internal/*` is gated by default. Routes that require vendor-specific authentication (e.g., `/internal/revenuecat-webhook` with Bearer + HMAC; `/internal/csam-webhook` admin-triggered + HMAC) MUST opt out of this plugin and provide their own auth — those routes MUST NOT be reachable via the OIDC plugin's authenticated path.

The opt-out mechanism is implementation-flexible (separate sibling route block, per-route plugin disable, etc.) but MUST satisfy two properties:
- A route mounted with the OIDC plugin and no vendor opt-out MUST reject all requests that fail OIDC verification.
- A route mounted with vendor-specific auth MUST NOT inherit OIDC verification (so a valid OIDC token alone does not bypass vendor HMAC).

#### Scenario: New `/internal/*` route inherits OIDC by default
- **WHEN** a future change adds any new `/internal/<route>` under the OIDC-protected subtree without explicitly disabling the plugin AND a request reaches that route without a valid OIDC token
- **THEN** the response status is `401 Unauthorized`

#### Scenario: Vendor-webhook route does NOT inherit OIDC
- **WHEN** `POST /internal/revenuecat-webhook` is mounted with vendor-specific Bearer + HMAC auth AND a request to it presents a valid OIDC token but no Bearer/HMAC headers
- **THEN** the response status is `401 Unauthorized` from the vendor auth, NOT a `200` from the OIDC plugin

### Requirement: Configured audience is required at boot

Application startup SHALL read the OIDC audience configuration value from Ktor application config (e.g., `oidc.internalAudience` resolved from the `INTERNAL_OIDC_AUDIENCE` environment variable). The audience is the deployed Cloud Run service URL (a public, non-secret value) and therefore is read via plain Ktor config rather than the `secretKey(env, name)` helper, which is reserved for actual secret material.

If the value is missing, blank, or not a syntactically valid URL, application boot MUST fail fast with a descriptive error before the HTTP server begins accepting connections, AND the JVM process MUST exit with a non-zero exit code so the Cloud Run startup probe fails and traffic is not flipped to the new revision.

This matches the fail-fast pattern established by `auth.supabaseUrl` and `auth.supabaseJwtSecret` ([`Application.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) — established in `health-check-endpoints`).

#### Scenario: Boot fails on missing audience config
- **WHEN** the `oidc.internalAudience` config value is absent from the environment AND the Ktor application is started
- **THEN** application boot fails with an error message naming the missing config key AND the HTTP server does not begin accepting connections AND the JVM process exits non-zero

#### Scenario: Boot fails on blank audience config
- **WHEN** the `oidc.internalAudience` config value is the empty string
- **THEN** application boot fails fast with a descriptive error AND the JVM process exits non-zero

#### Scenario: Boot fails when OIDC verifier cannot be constructed
- **WHEN** the `OidcTokenVerifier` cannot be constructed at boot (e.g., the JWKS lib's initial fetch fails, or any other dependency wiring throws) AND the Ktor application is started
- **THEN** application boot fails fast with a descriptive error AND the `/internal/*` route subtree is NOT registered AND the JVM process exits non-zero. The route MUST NOT mount with a degraded or null verifier.

### Requirement: 401 response body uses a sanitized error vocabulary

When the plugin rejects a request with `401 Unauthorized`, the response body SHALL be a JSON object with exactly one field `error` whose value is one of a fixed short vocabulary:
- `"missing_authorization"` — no `Authorization` header
- `"invalid_scheme"` — header present but not `Bearer`
- `"invalid_token"` — JWT structurally malformed, signature invalid, or claims fail verification
- `"expired_token"` — JWT signature valid but `exp` in the past
- `"audience_mismatch"` — JWT signature valid but `aud` does not match configured audience

The response body MUST NOT contain the offending token bytes, the JWT claims, signature failure details, JWKS contents, the configured audience value, or any verifier-internal exception message. Stack traces MUST NOT appear. The original verification failure context MUST be logged at WARN with full detail for operator debugging.

#### Scenario: Sanitized response on signature failure
- **WHEN** a request presents a JWT with an invalid signature
- **THEN** the response body parses as JSON with exactly `{"error": "invalid_token"}` AND does NOT contain the offending token, the JWT claims, the JWKS, or any exception message

#### Scenario: Sanitized response on audience mismatch
- **WHEN** a request presents a JWT whose `aud` does not match the configured audience
- **THEN** the response body is `{"error": "audience_mismatch"}` AND does NOT echo the configured audience value

### Requirement: Health endpoints remain unauthenticated

The `/health/live` and `/health/ready` endpoints SHALL remain public and unauthenticated. They MUST NOT be moved under the `/internal/*` subtree, and the `InternalEndpointAuth` plugin MUST NOT apply to them. Their auth-exempt stance is the contract owned by the `health-check` capability and is preserved unchanged by this change.

#### Scenario: Health endpoints unaffected by OIDC plugin
- **WHEN** `GET /health/live` is requested without an `Authorization` header AND the `InternalEndpointAuth` plugin is installed
- **THEN** the response status is `200 OK` (the plugin does not apply to health endpoints)

#### Scenario: Health endpoints not under `/internal/*`
- **WHEN** the application is configured
- **THEN** `/health/live` and `/health/ready` are NOT mounted under the `/internal/*` route subtree
