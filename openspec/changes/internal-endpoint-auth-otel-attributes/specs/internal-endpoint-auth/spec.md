## ADDED Requirements

### Requirement: `/internal/*` server spans carry `service.account.id` principal-correlation attribute

Every successful `/internal/*` request authenticated via the `InternalEndpointAuth` plugin SHALL produce a Ktor server span whose attributes include `service.account.id`. The attribute value SHALL be the result of `ServiceAccountIdHasher.hash(verifiedToken.subject)` — the first 16 hex characters of `SHA-256(sub.toByteArray(StandardCharsets.UTF_8))` (16 hex = 64-bit truncated digest), where `verifiedToken.subject` is the OIDC `sub` claim extracted from the verified bearer token.

The attribute is set on the active server span AFTER successful OIDC verification (signature + audience + expiry per the existing "Requirement: `/internal/*` routes require Google OIDC bearer token" requirement) AND BEFORE handler dispatch. Setting the attribute on a request that fails verification (401-rejected) is forbidden — failed-verification requests SHALL produce server spans carrying `http.route` + `http.status_code = 401` only, with no principal-correlation attribute (an attacker's failed request MUST NOT enrich the trace surface with their attempted principal).

The attribute write SHALL be best-effort: if the OTel SDK is uninitialised at the request time (e.g., test contexts, request preceding the OTel bootstrap), the write SHALL silently no-op and MUST NOT block auth verification or handler dispatch. This mirrors the `user.id` writer's best-effort posture from `observability-otel-foundation`.

The raw OIDC `sub` claim value SHALL NEVER appear on any span attribute, attribute key, or span name — only the hashed form via `ServiceAccountIdHasher.hash(...)` is sanctioned. Setting `service.account.id` (or any sibling key like `jwt.sub`, `principal`, `actor`) directly with the raw `sub` string is forbidden by the existing `OtelForbiddenAttributeRule` Tier 1 Group C entry on `jwt.sub` plus the canonical "raw JWT claims forbidden on spans" requirement at [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Forbidden span attributes".

The new helper `ServiceAccountIdHasher` SHALL be exported from `:infra:otel` as a sibling of [`UserIdHasher`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/UserIdHasher.kt) and [`IpHasher`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/IpHasher.kt). The helper signature is `fun hash(sub: String): String` returning `^[0-9a-f]{16}$`. The helper SHALL `require(sub.isNotBlank())` defensively — a blank `sub` is a verifier-flow regression that MUST surface as an exception rather than collapse to a deterministic single-bucket hash.

#### Scenario: Successful `/internal/*` request produces span with hashed service.account.id
- **GIVEN** the `OtelBootstrap` is initialised AND the `InternalEndpointAuth` plugin is mounted on `/internal/unban-worker`
- **WHEN** a request to `POST /internal/unban-worker` presents a valid Cloud-Scheduler-issued OIDC token whose `sub` claim is `nearyou-cloud-scheduler@example.iam.gserviceaccount.com` AND verification succeeds
- **THEN** the resulting Ktor server span's attributes include `service.account.id` whose value equals `ServiceAccountIdHasher.hash("nearyou-cloud-scheduler@example.iam.gserviceaccount.com")` (a 16-hex lowercase string matching `^[0-9a-f]{16}$`)

#### Scenario: Successful request span MUST NOT carry the raw sub claim
- **GIVEN** the same setup as the success scenario above
- **WHEN** the server span is exported AND every attribute value is scanned for the literal `nearyou-cloud-scheduler@example.iam.gserviceaccount.com`
- **THEN** the literal string does NOT appear in any attribute value, attribute key, or span name (the only sanctioned anonymisation shape is the 16-hex hash)

#### Scenario: 401-rejected request span carries no principal-correlation attribute
- **GIVEN** the `InternalEndpointAuth` plugin is mounted
- **WHEN** a request to `POST /internal/unban-worker` is sent with an invalid OIDC token (signature failure, audience mismatch, or expired) AND the plugin returns `401 Unauthorized`
- **THEN** the resulting server span carries `http.status_code = 401` AND does NOT carry `service.account.id` AND does NOT carry `user.id` AND does NOT carry any attribute whose value resembles a hashed identifier (no principal-correlation surface for failed-verification requests)

#### Scenario: `service.account.id` is mutually exclusive with `user.id`
- **GIVEN** the `InternalEndpointAuth` plugin is the active auth plugin on `/internal/*` routes (it is the OIDC service-account auth, NOT the UserPrincipal-backed auth used by `/api/v1/*` routes)
- **WHEN** a successful `/internal/*` request produces a server span
- **THEN** the span carries `service.account.id` AND does NOT carry `user.id` (a `/internal/*` request never has a `UserPrincipal`-backed identity to populate `user.id`)

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
- **THEN** the helper throws `IllegalArgumentException` (the `require(sub.isNotBlank())` defensive guard fires; collapsing all blank-sub requests to a single shared bucket would invert the per-principal correlation purpose)

#### Scenario: Best-effort write silently no-ops when OTel SDK uninitialised
- **GIVEN** a `testApplication { ... }` block that mounts `/internal/*` routes WITHOUT initialising `OtelBootstrap`
- **WHEN** a request to `/internal/unban-worker` presents a valid OIDC token and verification succeeds
- **THEN** the handler dispatches normally AND no exception is propagated by the attribute-write code path AND the response is the handler's normal success body (the missing OTel SDK does NOT block auth or handler logic)

#### Scenario: Vendor-webhook routes opt out and produce no service.account.id
- **GIVEN** `POST /internal/revenuecat-webhook` is mounted with vendor-specific Bearer + HMAC auth (NOT the OIDC plugin) per the existing "Plugin is mounted on the `/internal/*` subtree, with vendor-webhook opt-out" requirement
- **WHEN** a successful request to `/internal/revenuecat-webhook` is processed
- **THEN** the server span carries `http.route = "/internal/revenuecat-webhook"` + `http.status_code = 200` AND does NOT carry `service.account.id` (the OIDC plugin never ran; there is no verified `sub` claim to hash) AND does NOT carry `user.id`
