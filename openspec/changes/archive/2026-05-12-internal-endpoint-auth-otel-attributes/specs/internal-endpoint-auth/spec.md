## ADDED Requirements

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
