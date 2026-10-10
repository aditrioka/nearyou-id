## MODIFIED Requirements

### Requirement: `/internal/*` routes require Google OIDC bearer token

The Ktor backend SHALL gate every route mounted under the `/internal/*` route subtree behind a Ktor plugin (`InternalEndpointAuth`) that verifies a Google OIDC bearer token before dispatch. A request reaching an `/internal/*` route MUST present an `Authorization: Bearer <token>` header whose value is a Google-issued OIDC JWT. Missing, malformed, or non-bearer Authorization headers MUST short-circuit the request with HTTP `401 Unauthorized` before any handler logic runs.

The plugin MUST verify four properties on every request:

1. **Signature**: the JWT signature MUST validate against Google's published JWKS at `https://www.googleapis.com/oauth2/v3/certs`. JWKS responses MUST be cached with rotation-aware refresh — when a token's `kid` header references a key not present in cache, the verifier MUST force one JWKS refresh before rejecting.
2. **Audience**: the JWT `aud` claim MUST equal the configured audience value, supplied via plain Ktor `application.conf` config (key `oidc.internalAudience`, resolved from the `INTERNAL_OIDC_AUDIENCE` environment variable). The audience is the deployed Cloud Run service URL — a public, non-secret value — and therefore is read via plain Ktor config rather than the project's `secretKey(env, name)` helper, which is reserved for genuine secret material. See the "Configured audience is required at boot" requirement below for boot-time validation.
3. **Expiry**: the JWT `exp` claim MUST be in the future relative to the verification clock. The `iat` claim, when present, MUST NOT be in the future (with a 60-second skew tolerance to absorb clock drift).
4. **Caller principal**: the JWT `email` claim MUST be present, the `email_verified` claim MUST be the JSON boolean `true`, AND the presented email MUST exactly equal an entry of the configured allowed-principals set (entries are lowercased when configured; the presented value is not case-folded — Google issues service-account emails in lowercase, and not folding it keeps Unicode case-folding tricks out) (see the "Allowed caller principals are configured, and an empty allowlist denies every caller" requirement below). The `sub` claim (Google's opaque numeric service-account unique id) is NOT used for this check: the email is the identity Cloud Scheduler binds via `--oidc-service-account-email`, and a `<name>@<project>.iam.gserviceaccount.com` address can only be minted by the owning project. Property 4 is evaluated only after properties 1–3 pass, so it is never reached by an unauthenticated token.

Verification failure on any of properties 1–3 MUST short-circuit with HTTP `401 Unauthorized`. A failure on property 4 alone — a genuine, unexpired, correctly-audienced Google token whose principal is not allowlisted (including a token with no `email` claim or with `email_verified` not `true`) — MUST short-circuit with HTTP `403 Forbidden` and body `{"error": "principal_not_allowed"}` before any handler logic runs: the caller is authenticated but not authorized. The response body MUST NOT echo the offending token, JWT claims, signature failure detail, or any verifier exception message — only a short fixed vocabulary `error` field as documented in the response-shape requirement below.

The full original verifier exception MUST be logged at WARN with a token correlation id derived as the first 16 hex chars of `SHA-256(raw token bytes)` so operators can correlate failures with Cloud Scheduler invocation logs without ever logging JWT claims or the raw token. The `jti` claim is intentionally NOT used (logging any claim would conflict with the no-claims rule below); the truncated SHA-256 form is unconditional. A property-4 rejection is logged the same way (`reason=principal_not_allowed`) and MUST NOT log the presented `email` or the configured allowlist.

A property-4 rejection happens inside the verifier, before the plugin stores the verified subject on the call, so it never sets `OidcSubjectKey` and never writes the `service.account.id` span attribute (see the span requirement below).

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
- **WHEN** a request presents a Google-signed JWT whose signature validates, whose `aud` matches the configured audience, whose `exp` is in the future, AND whose `email` is an allowlisted principal with `email_verified = true`
- **THEN** the plugin admits the request to the route handler

#### Scenario: Foreign service account with the correct audience is forbidden
- **WHEN** a request to `POST /internal/unban-worker` presents a Google-signed, unexpired JWT whose `aud` equals the configured audience AND whose `email` is `attacker@other-project.iam.gserviceaccount.com` (verified, but not in the allowlist)
- **THEN** the response status is `403 Forbidden` AND the body is exactly `{"error": "principal_not_allowed"}` AND no handler logic runs

#### Scenario: Token without an email claim is forbidden
- **WHEN** a request presents a Google-signed, unexpired, correctly-audienced JWT that carries no `email` claim
- **THEN** the response status is `403 Forbidden` with `{"error": "principal_not_allowed"}`

#### Scenario: Unverified email is forbidden
- **WHEN** a request presents a Google-signed, unexpired, correctly-audienced JWT whose `email` is allowlisted but whose `email_verified` claim is `false`
- **THEN** the response status is `403 Forbidden` with `{"error": "principal_not_allowed"}`

#### Scenario: Allowlist entries are case-normalised
- **WHEN** the allowlist is configured with a mixed-case address AND a valid token presents the same address in lowercase in its `email` claim
- **THEN** the plugin admits the request to the route handler

#### Scenario: A non-boolean email_verified is forbidden
- **WHEN** a request presents a Google-signed, unexpired, correctly-audienced JWT whose `email` is allowlisted but whose `email_verified` claim is the string `"true"` rather than the JSON boolean
- **THEN** the response status is `403 Forbidden` with `{"error": "principal_not_allowed"}`

#### Scenario: Authentication failures are reported before the principal check
- **WHEN** a request presents a Google-signed JWT from a non-allowlisted principal whose `aud` does NOT match the configured audience
- **THEN** the response status is `401 Unauthorized` with `{"error": "audience_mismatch"}` (property 4 is never evaluated for a token that fails properties 1–3)

#### Scenario: JWKS rotation refresh resolves the new key
- **WHEN** a request from an allowlisted principal presents a JWT whose `kid` header references a key not in the JWKS cache AND that key IS present in the live Google JWKS endpoint (post-rotation)
- **THEN** the verifier forces one JWKS refresh, the cache is updated, signature verification succeeds against the refreshed key, AND the request is admitted to the route handler

#### Scenario: JWKS rotation refresh still does not resolve the kid
- **WHEN** a request presents a JWT whose `kid` header references a key not in the JWKS cache AND that key is also absent from the live Google JWKS endpoint after the forced refresh
- **THEN** the verifier returns `401` with body `{"error": "invalid_token"}` (no further refresh attempts; no infinite loop)

### Requirement: Rejection response body uses a sanitized error vocabulary

When the plugin rejects a request, the response body SHALL be a JSON object with exactly one field `error` whose value is one of a fixed short vocabulary.

With status `401 Unauthorized`:
- `"missing_authorization"` — no `Authorization` header
- `"invalid_scheme"` — header present but not `Bearer`
- `"invalid_token"` — JWT structurally malformed, signature invalid, or claims fail verification
- `"expired_token"` — JWT signature valid but `exp` in the past
- `"audience_mismatch"` — JWT signature valid but `aud` does not match configured audience

With status `403 Forbidden`:
- `"principal_not_allowed"` — JWT signature, audience, and expiry all valid, but the caller principal fails the allowlist check (no `email` claim, `email_verified` not `true`, email not allowlisted, or the allowlist is empty)

The response body MUST NOT contain the offending token bytes, the JWT claims (including the presented `email`), signature failure details, JWKS contents, the configured audience value, the configured allowlist, or any verifier-internal exception message. Stack traces MUST NOT appear. The original verification failure context MUST be logged at WARN with full detail for operator debugging, within the no-claims logging rule.

#### Scenario: Sanitized response on signature failure
- **WHEN** a request presents a JWT with an invalid signature
- **THEN** the response body parses as JSON with exactly `{"error": "invalid_token"}` AND does NOT contain the offending token, the JWT claims, the JWKS, or any exception message

#### Scenario: Sanitized response on audience mismatch
- **WHEN** a request presents a JWT whose `aud` does not match the configured audience
- **THEN** the response body is `{"error": "audience_mismatch"}` AND does NOT echo the configured audience value

#### Scenario: Sanitized response on principal rejection
- **WHEN** a request presents an otherwise-valid JWT whose `email` is not allowlisted
- **THEN** the response status is `403` AND the body is exactly `{"error": "principal_not_allowed"}` AND does NOT contain the presented email or any allowlisted email


### Requirement: `/internal/*` server spans carry `service.account.id` principal-correlation attribute

Every successful `/internal/*` request authenticated via the `InternalEndpointAuth` plugin SHALL produce a Ktor server span whose attributes include `service.account.id`. The attribute value SHALL be the result of `ServiceAccountIdHasher.hash(claims.sub)` — the first 16 hex characters of `SHA-256(sub.toByteArray(StandardCharsets.UTF_8))` (16 hex = 64-bit truncated digest), where `claims.sub` is the OIDC `sub` claim extracted from the verified bearer token (concretely: `claims.sub` is the `String`-valued `sub` field of the `VerifiedClaims` data class returned by `OidcTokenVerifier.verify(...)` and stored at [`InternalEndpointAuth.kt:79`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/internal/InternalEndpointAuth.kt) into the `OidcSubjectKey: AttributeKey<String>` — there is no `Principal` type for OIDC service-account auth in this codebase).

The attribute is set on the active server span AFTER successful OIDC verification (signature + audience + expiry + caller principal per the existing "Requirement: `/internal/*` routes require Google OIDC bearer token" requirement) AND BEFORE handler dispatch — concretely: the call is wired immediately after the `call.attributes.put(OidcSubjectKey, claims.sub)` line in `InternalEndpointAuth.kt`. Setting the attribute on a request that fails verification (401-rejected, or 403-rejected by the caller-principal check) is forbidden — failed-verification requests SHALL produce server spans (when Ktor's instrumentation produces one — see scenario "401-rejected request span behaviour" below) carrying `http.route` + `http.status_code` (`401` or `403`) only, with no principal-correlation attribute (an attacker's failed request MUST NOT enrich the trace surface with their attempted principal).

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

#### Scenario: 403 principal-rejected request span behaviour
- **GIVEN** the `InternalEndpointAuth` plugin is mounted
- **WHEN** a request to `POST /internal/unban-worker` presents a token whose signature, audience, and expiry are valid but whose `email` is not allowlisted AND the verifier rejects it, so the plugin responds `403 Forbidden` BEFORE `call.attributes.put(OidcSubjectKey, claims.sub)` runs
- **THEN** the `service.account.id` writer never executes AND if Ktor's instrumentation produces a server span for the 403 response, that span does NOT carry `service.account.id` AND does NOT carry `user.id` (a rejected caller does not enrich the trace surface)

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

## ADDED Requirements

### Requirement: Allowed caller principals are configured, and an empty allowlist denies every caller

Application startup SHALL read the allowed-principals set from Ktor config key `oidc.allowedPrincipals`, resolved from the `INTERNAL_OIDC_ALLOWED_PRINCIPALS` environment variable: a comma-separated list of service-account emails, each entry trimmed and lowercased, blank entries dropped. Service-account emails are non-secret configuration (public-repository posture), so the value is read via plain Ktor config rather than the `secretKey(env, name)` helper — the same rationale as the OIDC audience.

An unset, blank, or all-blank-entries value resolves to an empty set, and an empty set SHALL NEVER mean "admit every caller" — this deliberately inverts the repository's fail-soft "unresolved secret = no-op" convention, because this is an authorization control:

- unless `ktor.environment` names a local environment (`test`, `dev`, `development`) — i.e. in `staging`, `production`, when unset (which counts as `production`), or for any unrecognised value — application boot SHALL fail fast with an error naming `INTERNAL_OIDC_ALLOWED_PRINCIPALS`, so a misconfigured deploy is a rejected revision rather than a running service whose workers silently `403` (an allowlist of local environments, so a typo fails closed);
- in a local environment (`test`, `dev`, `development`) boot SHALL proceed — local development and test harnesses keep booting without the variable — with a WARN line `event=internal_oidc_allowlist_empty`, and every request to an OIDC-gated `/internal/*` route, including one carrying an otherwise-valid token, is rejected `403 {"error": "principal_not_allowed"}`.

Each deployed environment SHALL set the allowlist to exactly the Cloud Scheduler service account(s) that invoke its workers; the per-environment accounts and their migration steps are operational configuration recorded in `docs/07-Operations.md` § Internal worker schedules, not in this spec.

#### Scenario: Unset allowlist in dev or test boots and denies every caller
- **WHEN** `INTERNAL_OIDC_ALLOWED_PRINCIPALS` is unset AND the application starts with `ktor.environment` = `test`
- **THEN** boot succeeds AND a WARN `event=internal_oidc_allowlist_empty` is logged AND a request to `POST /internal/unban-worker` with an otherwise-valid token is rejected `403` with `{"error": "principal_not_allowed"}`

#### Scenario: Empty allowlist fails boot outside a local environment
- **WHEN** `INTERNAL_OIDC_ALLOWED_PRINCIPALS` is unset or blank AND `ktor.environment` is `staging`, `production`, unset, or an unrecognised value such as `prod`
- **THEN** application boot fails with an error naming `INTERNAL_OIDC_ALLOWED_PRINCIPALS` before the HTTP server accepts connections

#### Scenario: Blank allowlist entries resolve to an empty, denying set
- **WHEN** `oidc.allowedPrincipals` is `" , ,"` AND `ktor.environment` is `dev`
- **THEN** the resolved allowed-principals set is empty AND the WARN `event=internal_oidc_allowlist_empty` is logged (fail-closed, not "allow all")

#### Scenario: Comma-separated allowlist is trimmed and lowercased
- **WHEN** `oidc.allowedPrincipals` is `" Scheduler-A@p.iam.gserviceaccount.com , scheduler-b@p.iam.gserviceaccount.com ,"`
- **THEN** the resolved set is exactly `{scheduler-a@p.iam.gserviceaccount.com, scheduler-b@p.iam.gserviceaccount.com}` AND no WARN is logged


## RENAMED Requirements

- FROM: `### Requirement: 401 response body uses a sanitized error vocabulary`
- TO: `### Requirement: Rejection response body uses a sanitized error vocabulary`
