# internal-endpoint-auth Specification

## Purpose

The internal-endpoint-auth capability defines the OIDC-bearer-token authentication contract that gates every `/internal/*` route invoked by Cloud Scheduler or another GCP service account. The Ktor plugin verifies the bearer token's signature against the Google JWKS, checks expiry, and matches the `aud` claim against the configured audience (the deployed service URL); missing, invalid, or wrong-audience tokens are rejected `401`. The plugin is composable so future endpoints with vendor-specific auth (RevenueCat webhook, CSAM webhook) can opt out, and it is the canonical pattern reused by every scheduled worker (suspension unban, privacy-flip, hard-delete, FCM cleanup, notifications purge, and more).
## Requirements
### Requirement: `/internal/*` routes require Google OIDC bearer token

The Ktor backend SHALL gate every route mounted under the `/internal/*` route subtree behind a Ktor plugin (`InternalEndpointAuth`) that verifies a Google OIDC bearer token before dispatch. A request reaching an `/internal/*` route MUST present an `Authorization: Bearer <token>` header whose value is a Google-issued OIDC JWT. Missing, malformed, or non-bearer Authorization headers MUST short-circuit the request with HTTP `401 Unauthorized` before any handler logic runs.

The plugin MUST verify three properties on every request:

1. **Signature**: the JWT signature MUST validate against Google's published JWKS at `https://www.googleapis.com/oauth2/v3/certs`. JWKS responses MUST be cached with rotation-aware refresh — when a token's `kid` header references a key not present in cache, the verifier MUST force one JWKS refresh before rejecting.
2. **Audience**: the JWT `aud` claim MUST equal the configured audience value, supplied via plain Ktor `application.conf` config (key `oidc.internalAudience`, resolved from the `INTERNAL_OIDC_AUDIENCE` environment variable). The audience is the deployed Cloud Run service URL — a public, non-secret value — and therefore is read via plain Ktor config rather than the project's `secretKey(env, name)` helper, which is reserved for genuine secret material. See the "Configured audience is required at boot" requirement below for boot-time validation.
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
- **WHEN** a request presents a Google-signed JWT whose `aud` claim is `https://example.com/other-service` and the configured `oidc.internalAudience` is `https://api-staging.nearyou.id`
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

### Requirement: `/internal/*` server spans carry `service.account.id` principal-correlation attribute

Every successful `/internal/*` request authenticated via the `InternalEndpointAuth` plugin SHALL produce a Ktor server span whose attributes include `service.account.id`. The attribute value SHALL be the result of `ServiceAccountIdHasher.hash(claims.sub)` — the first 16 hex characters of `SHA-256(sub.toByteArray(StandardCharsets.UTF_8))` (16 hex = 64-bit truncated digest), where `claims.sub` is the OIDC `sub` claim extracted from the verified bearer token (concretely: `claims.sub` is the `String`-valued `sub` field of the `VerifiedClaims` data class returned by `OidcTokenVerifier.verify(...)` and stored at [`InternalEndpointAuth.kt:79`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/internal/InternalEndpointAuth.kt) into the `OidcSubjectKey: AttributeKey<String>` — there is no `Principal` type for OIDC service-account auth in this codebase).

The attribute is set on the active server span AFTER successful OIDC verification (signature + audience + expiry per the existing "Requirement: `/internal/*` routes require Google OIDC bearer token" requirement) AND BEFORE handler dispatch — concretely: the call is wired immediately after the `call.attributes.put(OidcSubjectKey, claims.sub)` line in `InternalEndpointAuth.kt`. Setting the attribute on a request that fails verification (401-rejected) is forbidden — failed-verification requests SHALL produce server spans (when Ktor's instrumentation produces one — see scenario "401-rejected request span behaviour" below) carrying `http.route` + `http.status_code = 401` only, with no principal-correlation attribute (an attacker's failed request MUST NOT enrich the trace surface with their attempted principal).

The attribute write SHALL be best-effort and SHALL be wrapped in a `try { ... } catch (_: Throwable) { ... }` block (mirror the precedent at [`AuthPlugin.kt:113-120`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) byte-for-byte): if (a) the OTel SDK is uninitialised at the request time (e.g., test contexts, request preceding the OTel bootstrap — `Span.current()` returns the no-op span), OR (b) the helper throws `IllegalArgumentException` on a blank `sub` (the existing verifier at [`GoogleOidcTokenVerifier.kt:90`](../../../../../infra/oidc/src/main/kotlin/id/nearyou/app/infra/oidc/GoogleOidcTokenVerifier.kt) substitutes `decoded.subject ?: ""` when the OIDC token has a missing `sub` claim, so blank-sub is REACHABLE in production — not a verifier-flow regression), the write SHALL silently no-op and MUST NOT block auth verification or handler dispatch. The blank-sub case results in NO `service.account.id` attribute on the span (the principal is unidentifiable; this is the correct outcome — no telemetry surface for an unidentifiable principal).

The raw OIDC `sub` claim value SHALL NEVER appear on any span attribute, attribute key, or span name — only the hashed form via `ServiceAccountIdHasher.hash(...)` is sanctioned. Setting `service.account.id` (or any sibling key like `jwt.sub`, `principal`, `actor`) directly with the raw `sub` string is forbidden by the existing `OtelForbiddenAttributeRule` Tier 1 Group C entry on `jwt.sub` plus the canonical "raw JWT claims forbidden on spans" requirement at [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Forbidden span attributes".

The new helper `ServiceAccountIdHasher` SHALL be exported from `:infra:otel` as a sibling of [`UserIdHasher`](../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt) and [`IpHasher`](../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt). The helper signature is `fun hash(sub: String): String` returning `^[0-9a-f]{16}$`. The helper SHALL `require(sub.isNotBlank())` defensively — a blank `sub` is a verifier-flow regression that MUST surface as an exception rather than collapse to a deterministic single-bucket hash.

#### Scenario: Successful `/internal/*` request produces span with hashed service.account.id
- **GIVEN** the `OtelBootstrap` is initialised AND the `InternalEndpointAuth` plugin is mounted on `/internal/unban-worker`
- **WHEN** a request to `POST /internal/unban-worker` presents a valid Cloud-Scheduler-issued OIDC token whose verifier-extracted `claims.sub` is `"105329845711234567890"` (the 21-digit numeric form Google OIDC emits for SA `sub` claims) AND verification succeeds
- **THEN** the resulting Ktor server span's attributes include `service.account.id` whose value equals `ServiceAccountIdHasher.hash("105329845711234567890")` (a 16-hex lowercase string matching `^[0-9a-f]{16}$`)

#### Scenario: Successful request span MUST NOT carry the raw sub claim (sentinel-string scan)
- **GIVEN** the same setup as the success scenario above
- **WHEN** the server span is exported AND every attribute value, attribute key, and span name is scanned for the literal `"105329845711234567890"`
- **THEN** the literal string does NOT appear anywhere on the span (the only sanctioned anonymisation shape is the 16-hex hash). This is a sentinel-string regression scan analogous to the precedent at [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "OTLP token VALUE SHALL NOT appear in any span attribute".

#### Scenario: 401-rejected request span behaviour
- **GIVEN** the `InternalEndpointAuth` plugin is mounted
- **WHEN** a request to `POST /internal/unban-worker` is sent with an invalid OIDC token (signature failure, audience mismatch, or expired) AND the plugin short-circuits with `401 Unauthorized` via `respondText(...)` in the `CallSetup` hook BEFORE `InternalEndpointAuth.kt:79`'s `call.attributes.put(OidcSubjectKey, claims.sub)` runs
- **THEN** the `service.account.id` writer never executes (the writer is wired AFTER the `OidcSubjectKey` put, which never runs on 401-rejected paths) AND if Ktor's instrumentation produces a server span for the 401 response, that span does NOT carry `service.account.id` AND does NOT carry `user.id` (no principal-correlation surface for failed-verification requests). Whether Ktor's `KtorServerTelemetry` emits a server span for an early-`respondText`-in-`CallSetup`-rejected request depends on instrumentation hook ordering — the test verifies the negative property (attribute absence) without asserting span existence one way or the other.

#### Scenario: Server-span-level mutual exclusion with `user.id` (server span only — child spans not constrained)
- **GIVEN** the `InternalEndpointAuth` plugin is route-scoped on `/internal/*` (NOT installed on `/api/v1/*`); the `AuthPlugin` (UserPrincipal-backed) is installed on `/api/v1/*` (NOT installed on `/internal/*`); structurally the two plugins cannot fire on the same Ktor server span
- **WHEN** a successful `/internal/*` request produces a Ktor server span
- **THEN** the server span carries `service.account.id` AND does NOT carry `user.id` (a `/internal/*` request never has a `UserPrincipal`-backed identity to populate `user.id`). NOTE: this requirement applies to the SERVER span only. Child spans created by handlers (e.g., a future `/internal/*` worker that hashes a target user ID via `UserIdHasher.hash(...)` for per-target correlation on a child span) MAY independently carry `user.id` on those child spans without violating this requirement — the mutual-exclusion contract is span-by-span, not request-by-request.

#### Scenario: Converse mutual exclusion — `/api/v1/*` server span never carries `service.account.id`
- **GIVEN** a `/api/v1/*` route mounted with the `AuthPlugin` UserPrincipal-backed auth (NOT the `InternalEndpointAuth` plugin); the `OidcSubjectKey` is therefore never set on this call
- **WHEN** a successful authenticated `/api/v1/*` request produces a Ktor server span
- **THEN** the server span carries `user.id` (per `observability-otel-foundation` § Mandatory span attributes) AND does NOT carry `service.account.id` (no OIDC verification ran; the writer's structural gate fails)

#### Scenario: ServiceAccountIdHasher output is deterministic
- **GIVEN** a non-blank string `S`
- **WHEN** `ServiceAccountIdHasher.hash(S)` is invoked twice
- **THEN** both calls return the identical 16-character hex string

#### Scenario: ServiceAccountIdHasher hash differs between distinct inputs
- **GIVEN** two distinct non-blank strings `S1 != S2`
- **WHEN** `ServiceAccountIdHasher.hash(S1)` and `ServiceAccountIdHasher.hash(S2)` are computed
- **THEN** the two return values differ (with overwhelming probability — collision is bounded by the 64-bit truncated SHA-256 collision space, ≈1.8e19)

#### Scenario: ServiceAccountIdHasher output shape is exactly 16 lowercase hex chars
- **GIVEN** any non-blank string
- **WHEN** `ServiceAccountIdHasher.hash(s)` is invoked
- **THEN** the return value matches the regex `^[0-9a-f]{16}$`

#### Scenario: ServiceAccountIdHasher rejects blank input fail-fast
- **GIVEN** a blank string (empty, single space, or all-whitespace)
- **WHEN** `ServiceAccountIdHasher.hash(blank)` is invoked
- **THEN** the helper throws `IllegalArgumentException` (the `require(sub.isNotBlank())` defensive guard fires; collapsing all blank-sub requests to a single shared bucket would invert the per-principal correlation purpose). Note: this throw is REACHABLE in production because the existing verifier at [`GoogleOidcTokenVerifier.kt:90`](../../../../../infra/oidc/src/main/kotlin/id/nearyou/app/infra/oidc/GoogleOidcTokenVerifier.kt) substitutes `decoded.subject ?: ""` for missing `sub`. The writer's `try/catch (_: Throwable)` block (mirror of the `AuthPlugin.kt:113-120` precedent) gracefully swallows this throw — see scenario "Best-effort write silently no-ops on helper throw" below.

#### Scenario: Best-effort write silently no-ops when OTel SDK uninitialised
- **GIVEN** a `testApplication { ... }` block that mounts `/internal/*` routes WITHOUT initialising `OtelBootstrap`
- **WHEN** a request to `/internal/unban-worker` presents a valid OIDC token and verification succeeds
- **THEN** the handler dispatches normally AND no exception is propagated by the attribute-write code path AND the response is the handler's normal success body (the missing OTel SDK causes `Span.current()` to return the no-op span; the no-op span's `setAttribute` is a defensive no-op per OTel SDK contract; auth + handler logic proceed unblocked)

#### Scenario: Best-effort write silently no-ops on helper throw
- **GIVEN** an OTel pipeline AND an `InternalEndpointAuth` request whose OIDC token has a verifier-extracted `sub` value of `""` (blank — reachable per `GoogleOidcTokenVerifier.kt:90`'s `decoded.subject ?: ""` substitution)
- **WHEN** the writer invokes `ServiceAccountIdHasher.hash("")` AND the helper throws `IllegalArgumentException` per its `require(sub.isNotBlank())` guard
- **THEN** the writer's `try { ... } catch (_: Throwable) { ... }` wrapper (mirror of `AuthPlugin.kt:113-120`) swallows the throw silently AND the auth gate proceeds AND the handler dispatches normally AND the resulting server span has NO `service.account.id` attribute (the principal is unidentifiable; absence is the correct outcome)

#### Scenario: Best-effort write silently no-ops on SpanProcessor failure (FailingSpanProcessor regression)
- **GIVEN** an OTel pipeline configured with the `FailingSpanProcessor` test fixture from [`infra/otel/.../SpanRecorder.kt`](../../../../../infra/otel/src/testFixtures/kotlin/id/nearyou/app/infra/otel/testing/SpanRecorder.kt) (the same fixture used by the `fcm-push-dispatch` "Span recording failure does not block dispatch" scenario) AND a successful `InternalEndpointAuth` request
- **WHEN** the writer invokes `Span.current().setAttribute("service.account.id", ServiceAccountIdHasher.hash(claims.sub))` AND the active SpanProcessor throws on attribute set
- **THEN** the writer's `try { ... } catch (_: Throwable) { ... }` wrapper swallows the throw AND the auth gate + handler proceed unblocked (regression coverage that locks the silent-fail posture against actively-throwing telemetry pipelines, NOT just the no-op-span path)

#### Scenario: Vendor-webhook routes opt out and produce no service.account.id
- **GIVEN** [`POST /internal/apple/s2s-notifications`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AppleS2SRoutes.kt) (the canonical existing `/internal/*` vendor-webhook route — confirmed not to install `InternalEndpointAuth`; it has its own Apple S2S signature verification per the existing "Plugin is mounted on the `/internal/*` subtree, with vendor-webhook opt-out" requirement)
- **WHEN** a successful request to `/internal/apple/s2s-notifications` is processed
- **THEN** the server span carries `http.route = "/internal/apple/s2s-notifications"` + `http.status_code = 200` AND does NOT carry `service.account.id` (the `InternalEndpointAuth` plugin never ran on this route; `OidcSubjectKey` is never set; the writer's structural gate fails) AND does NOT carry `user.id` (no UserPrincipal-backed auth either)

