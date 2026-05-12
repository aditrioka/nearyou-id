# observability-otel-foundation Specification

## Purpose

The observability-otel-foundation capability scaffolds the `:infra:otel` module and wires OpenTelemetry tracing into `:backend:ktor` with auto-instrumentation for the Ktor HTTP server, the JDK / CIO HTTP client, Postgres JDBC via HikariCP, and Redis Lettuce. It defines the mandatory spans and attributes (including the `UserIdHasher` that hashes user UUIDs to 16 hex chars before they can be attached to spans), the forbidden-attributes contract that defends against PII leakage, W3C Trace Context propagation on outbound HTTP, and a `ParentBased(TraceIdRatioBased(0.1))` production sampler with a no-op exporter fallback when secrets are absent. Force-keep promotion (100% errors + 100% slow) is deferred to a follow-up change that deploys an OTel Collector for tail sampling.
## Requirements
### Requirement: `:infra:otel` module is the sole owner of the OTel SDK + vendor exporter

A new Gradle module `:infra:otel` SHALL be created under `infra/otel/` (alongside `:infra:fcm`, `:infra:oidc`, `:infra:redis`, `:infra:supabase`). All OTel SDK imports (`io.opentelemetry.sdk.*`, `io.opentelemetry.exporter.otlp.*`, library-specific instrumentation packages) and all vendor-specific code (Grafana Cloud Tempo endpoint, OTLP token plumbing) SHALL live entirely inside `:infra:otel`. The `:backend:ktor` module SHALL depend on `:infra:otel` and SHALL NOT carry any direct `io.opentelemetry.*` import outside of standard OTel API types (`io.opentelemetry.api.trace.Span`, `io.opentelemetry.api.trace.Tracer`) used at consumption points (the manual `withSpan` helper). Business modules (`:core:domain`, `:core:data`, `:shared:*`) SHALL NOT depend on `:infra:otel` — they remain pure-Kotlin / interface-only.

This module-shape requirement enables the Open Decision #12 vendor swap (Grafana Cloud → Honeycomb / Cloud Trace) as a within-`:infra:otel` config change without touching `:backend:ktor` or business modules.

#### Scenario: Module exists at the canonical path with the canonical name
- **WHEN** the project structure is inspected
- **THEN** `infra/otel/build.gradle.kts` exists AND the module is included in `settings.gradle.kts` as `:infra:otel`

#### Scenario: Backend depends on `:infra:otel`, not a vendor SDK directly
- **WHEN** `:backend:ktor`'s `build.gradle.kts` dependencies are inspected
- **THEN** `:infra:otel` is listed as a project dependency AND no `io.opentelemetry.exporter.otlp.*` or `io.grafana.*` declaration appears in `:backend:ktor`

#### Scenario: Business modules carry no OTel import
- **WHEN** the source files of `:core:domain` and `:core:data` are scanned for `import io.opentelemetry.`
- **THEN** zero matches are found

#### Scenario: Business modules carry no `:infra:otel` Gradle dependency
- **WHEN** `:core:domain`'s `build.gradle.kts` AND `:core:data`'s `build.gradle.kts` `dependencies { ... }` blocks are inspected
- **THEN** no `project(":infra:otel")` declaration appears in either AND no `implementation(libs.opentelemetry.*)` BOM-managed declaration appears (this catches a regression where someone adds the dependency edge without writing an `import io.opentelemetry.*` statement that the source-file scenario above would flag)

### Requirement: `OtelBootstrap.start(env, secretResolver)` initializes the SDK at Ktor startup, idempotent and exception-safe

The `:infra:otel` module SHALL expose a single startup entrypoint `OtelBootstrap.start(env: String, secretResolver: SecretResolver)` that initializes the OTel `SdkTracerProvider`, configures the per-env exporter, registers the global `OpenTelemetry` instance, and wires auto-instrumentation for HikariCP / Lettuce / outbound HTTP clients. The `env` parameter is the same `String` value (`"dev"` / `"staging"` / `"production"`) that Application.kt reads from `KTOR_ENV` today; the `secretResolver` is the existing `SecretResolver` interface consumed by Application.kt for every other secret. The function SHALL be idempotent — a second call within the same JVM SHALL be a no-op that logs an INFO line and returns. The function SHALL NOT throw on misconfiguration; instead, the bootstrap SHALL fall back to the no-op exporter shape (per the Exporter secret absence requirement) and log a single INFO line.

`Application.module()` in `:backend:ktor` SHALL invoke `OtelBootstrap.start(env, secretResolver)` exactly once before any other module init (so subsequent module inits get auto-instrumented).

#### Scenario: First call initializes the SDK
- **WHEN** `OtelBootstrap.start(env, secretResolver)` runs for the first time in the JVM
- **THEN** the global `OpenTelemetry` instance is registered AND a `Tracer` named `"id.nearyou.backend"` is obtainable AND auto-instrumentation hooks for HikariCP / Lettuce / the outbound HTTP client are installed

#### Scenario: Second call is a no-op
- **WHEN** `OtelBootstrap.start(env, secretResolver)` is invoked twice in the same JVM
- **THEN** the second call returns immediately AND emits an INFO log with `event="otel_bootstrap_already_initialized"` AND does not re-register the SDK

#### Scenario: Exporter misconfiguration does not crash the application
- **GIVEN** the exporter endpoint URL resolves to an unreachable host
- **WHEN** `OtelBootstrap.start(env, secretResolver)` is invoked
- **THEN** the function returns normally AND emits an INFO log AND the no-op exporter shape is active AND `Application.module()` continues startup unblocked

### Requirement: Sampling profile per environment — no force-keep promotion in this change

The sampling profile SHALL be selected by the `env` String value passed to `OtelBootstrap.start(env, secretResolver)`:
- `"dev"` AND `"staging"` → `Sampler.alwaysOn()` (head 100%) per the Phase 2 §14 benchmark requirement at [`docs/05-Implementation.md:2042`](../../../../../docs/05-Implementation.md).
- `"production"` → `Sampler.parentBased(Sampler.traceIdRatioBased(0.1))` (10% base ratio). **No force-keep promotion of error or slow spans is implemented in this change.** The canonical "100% errors + 100% slow" target from the production sampling profile is deferred to a focused follow-up change that deploys an OTel Collector (the only correct way to preserve trace_id linkage on tail-sampled force-keep). Until that follow-up ships, MVP production accepts that 90% of all traces drop AND that 90% of error / slow traces also drop; structured JSON logging at 100% retention via Cloud Logging continues to be the authoritative incident-replay surface.

The 10%-base ratio MUST be configurable via a single constant in `:infra:otel` (so a future tuning change is a one-line edit).

#### Scenario: Dev environment samples at 100%
- **GIVEN** `env = "dev"`
- **WHEN** `OtelBootstrap.start(env, secretResolver)` initializes the SDK
- **THEN** the configured root `Sampler` reports `SamplingDecision.RECORD_AND_SAMPLE` for every synthetic root span tested

#### Scenario: Staging environment samples at 100% (parity with dev)
- **GIVEN** `env = "staging"`
- **WHEN** `OtelBootstrap.start(env, secretResolver)` initializes the SDK
- **THEN** the configured root `Sampler` reports `SamplingDecision.RECORD_AND_SAMPLE` for every synthetic root span tested (equivalent to dev — Phase 2 §14 benchmark needs full traces) AND the sampler is NOT the production ratio-based sampler (defense against a copy-paste regression that would silently demote staging to the prod 10%-base shape)

#### Scenario: Production environment samples at 10% base
- **GIVEN** `env = "production"` AND a synthetic root span with `http.status_code = 200` AND `duration_ms = 100`
- **WHEN** the sampler is invoked across 1000 trace-id seeds
- **THEN** between 5% and 15% of the seeds yield `SamplingDecision.RECORD_AND_SAMPLE` (statistical tolerance for a 10% target)

#### Scenario: Production sampler does NOT force-keep an error span (deferred to Collector follow-up)
- **GIVEN** `env = "production"` AND a synthetic root span tagged `http.status_code = 500` AND a trace-id seed that falls in the 90% drop window
- **WHEN** the span ends
- **THEN** the span is NOT exported (the base 10% ratio applies; force-keep promotion is NOT implemented in this change). Locks the deferral: a future regression that adds force-keep at the SDK level (which would lose trace_id linkage) would surface in this scenario as a behavioral change.

#### Scenario: Production sampler does NOT force-keep a slow span (deferred to Collector follow-up)
- **GIVEN** `env = "production"` AND a synthetic root span with `http.status_code = 200` AND `duration_ms = 800` AND a trace-id seed that falls in the 90% drop window
- **WHEN** the span ends
- **THEN** the span is NOT exported. Same rationale as above; force-keep promotion is the `observability-otel-collector-tail-sampling` follow-up's scope.

#### Scenario: Production sampler drops a fast healthy span outside the base ratio
- **GIVEN** `env = "production"` AND a synthetic root span with `http.status_code = 200` AND `duration_ms = 50` AND a trace-id seed that falls in the 90% drop window
- **WHEN** the span ends
- **THEN** the span is NOT exported (base ratio rejected)

### Requirement: OTLP exporter target is sourced via `secretKey(env, ...)` slot-name derivation + `SecretResolver.resolve(...)` value lookup, with a clean no-op fallback

The exporter target SHALL be sourced via the existing two-step pattern at [`backend/ktor/.../config/Secrets.kt`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/config/Secrets.kt): (1) the slot NAME is derived via `secretKey(env, "otel-grafana-otlp-endpoint")` and `secretKey(env, "otel-grafana-otlp-token")` (which simply prefixes `staging-` for the staging env); (2) the slot VALUE is fetched via `secretResolver.resolve(slotName)` (which returns `String?` from GCP Secret Manager). This matches every other secret in `:backend:ktor` and respects the `SecretKeyHelperRule` Detekt rule. The `SecretResolver` is passed into `OtelBootstrap.start(env, secretResolver)` by `Application.module()`.

When EITHER `SecretResolver.resolve(...)` returns `null`, `OtelBootstrap` SHALL configure a `LoggingSpanExporter` (DEBUG severity, dropped by default Logback config — effectively no-op for production logging volume) and emit exactly one INFO startup log line: `event="otel_exporter_disabled" reason="<endpoint_missing|token_missing>" sampling_profile="<profile_name>"`. The application start-up SHALL NOT block, fail, or retry. **Deterministic precedence**: the implementation SHALL check the endpoint slot first; if endpoint is null, emit `reason="endpoint_missing"` and return without checking the token. Only if endpoint is present is the token checked.

The OTLP token VALUE SHALL NEVER appear in any log line, span attribute, span name, or HTTP request body produced by `:backend:ktor` outside the OTLP exporter's own outbound HTTPS request to the endpoint.

#### Scenario: Endpoint absent → no-op exporter + single INFO line with `endpoint_missing`
- **GIVEN** `secretResolver.resolve(secretKey(env, "otel-grafana-otlp-endpoint"))` returns null
- **WHEN** `OtelBootstrap.start(env, secretResolver)` runs
- **THEN** exactly one INFO log line is emitted with `event="otel_exporter_disabled"` AND `reason="endpoint_missing"` AND the `LoggingSpanExporter` is the configured exporter

#### Scenario: Token absent → no-op exporter + single INFO line with `token_missing`
- **GIVEN** `secretResolver.resolve(secretKey(env, "otel-grafana-otlp-endpoint"))` returns a non-null endpoint AND `secretResolver.resolve(secretKey(env, "otel-grafana-otlp-token"))` returns null
- **WHEN** `OtelBootstrap.start(env, secretResolver)` runs
- **THEN** exactly one INFO log line is emitted with `event="otel_exporter_disabled"` AND `reason="token_missing"`

#### Scenario: Both secrets absent → exactly one INFO line with `endpoint_missing` (deterministic precedence)
- **GIVEN** both `secretResolver.resolve(...)` lookups for endpoint AND token return null
- **WHEN** `OtelBootstrap.start(env, secretResolver)` runs
- **THEN** exactly ONE INFO log line is emitted (NOT two — endpoint check short-circuits before token check) AND `reason="endpoint_missing"` (deterministic precedence: endpoint is checked first)

#### Scenario: Both secrets present → live OTLP exporter wired
- **GIVEN** both `secretResolver.resolve(...)` lookups return non-null values
- **WHEN** `OtelBootstrap.start(env, secretResolver)` runs
- **THEN** the OTLP/HTTP exporter is wired to the resolved endpoint AND no `event="otel_exporter_disabled"` line is emitted

#### Scenario: OTLP token VALUE never appears in logs
- **GIVEN** the resolved token VALUE `T` AND a test that captures all log lines emitted during `OtelBootstrap.start(env)`
- **WHEN** the captured log messages are scanned for `T` (substring match)
- **THEN** `T` does NOT appear in any captured log line

#### Scenario: OTLP token VALUE never appears in span attributes, events, or names
- **GIVEN** the resolved OTLP token VALUE `T` is configured at startup AND a test SpanRecorder captures every span exported during a representative request flow (server span + outbound HTTP spans + manual `withSpan` sites)
- **WHEN** all captured spans' names, attribute values, and event attribute values are scanned for `T` (substring match)
- **THEN** `T` does NOT appear in any captured span attribute value, event attribute value, or span name (the OTLP exporter's outbound HTTPS Authorization header is the ONLY sanctioned location for the token VALUE)

### Requirement: `user.id` span attribute SHALL be SHA-256 truncated, never raw

`:infra:otel` SHALL expose `UserIdHasher.hash(userId: UUID): String` that returns the first 16 hex characters of `SHA-256(userId.bytes)` (16 hex = 64-bit truncated digest). Every site that sets the `user.id` span attribute SHALL go through this helper. Setting `user.id` directly with a raw UUID string is forbidden.

The truncation length and digest function are fixed (changing them is an explicit follow-up change requiring a separate proposal). The shape mirrors the existing token-correlation-id pattern from [`internal-endpoint-auth/spec.md`](../../../../specs/internal-endpoint-auth/spec.md) ("first 16 hex chars of `SHA-256(raw token bytes)`"), keeping operator mental models unified.

#### Scenario: Hash is deterministic
- **GIVEN** a UUID `U`
- **WHEN** `UserIdHasher.hash(U)` is invoked twice
- **THEN** both calls return the identical 16-character hex string

#### Scenario: Hash differs between distinct UUIDs
- **GIVEN** two distinct UUIDs `U1 != U2`
- **WHEN** `UserIdHasher.hash(U1)` and `UserIdHasher.hash(U2)` are computed
- **THEN** the two return values differ

#### Scenario: Hash output is exactly 16 hex characters
- **GIVEN** any UUID
- **WHEN** `UserIdHasher.hash(uuid)` is invoked
- **THEN** the return value matches the regex `^[0-9a-f]{16}$`

### Requirement: Mandatory span attributes SHALL be present on canonical span surfaces

Every Ktor server span (root span for an inbound HTTP request) SHALL carry these attributes when applicable:
- `http.method` (auto-instrumentation; standard W3C semconv).
- `http.route` (Ktor route pattern, e.g., `/api/v1/posts/{post_id}/like` — NOT the raw URL with the path-param value substituted).
- `http.status_code` (auto-instrumentation).
- `endpoint` (alias for `http.route`, for query-by-endpoint convenience in Grafana Tempo).
- `user.id` (set via `UserIdHasher.hash(...)`) **when the request is authenticated against a `UserPrincipal`-backed identity (i.e., a row in the `users` table)**. The attribute SHALL NOT be set for `/internal/*` requests authenticated via Cloud Scheduler service-account OIDC — those requests have no `users` row to hash. Use `service.account.id` (next bullet) instead.
- `service.account.id` (set via `ServiceAccountIdHasher.hash(...)`) **when the request is authenticated via the `InternalEndpointAuth` Ktor plugin (i.e., a verified Cloud Scheduler service-account OIDC token)**. The attribute value is the first 16 hex characters of `SHA-256(claims.sub.toByteArray(StandardCharsets.UTF_8))` — the OIDC `sub` claim, hashed. The attribute SHALL NOT be set on requests that fail verification (401-rejected) AND SHALL NOT be set on non-`/internal/*` routes (which use UserPrincipal-backed auth and populate `user.id` instead). The attribute SHALL NOT be set on vendor-webhook routes that opt out of the OIDC plugin (those have no verified `sub` claim). Setting the raw `sub` claim directly with any attribute key (including `service.account.id`, `jwt.sub`, `principal`, `actor`, etc.) is forbidden — only the hashed-via-helper form is sanctioned. The helper `ServiceAccountIdHasher` lives in `:infra:otel` as a sibling of `UserIdHasher` and `IpHasher`; the full contract — including the deterministic, distinct-output, exact 16-hex-shape, and `require(sub.isNotBlank())` guarantees — lives in [`internal-endpoint-auth/spec.md`](../internal-endpoint-auth/spec.md) § "Requirement: `/internal/*` server spans carry `service.account.id` principal-correlation attribute".
- `cloud.region` — OTel semconv name; sourced from the GCP metadata server at `http://metadata.google.internal/computeMetadata/v1/instance/region` (called once at `OtelBootstrap.start(...)` with a 500ms timeout; the resolved value is cached as a resource attribute on the `SdkTracerProvider`, so every span gets it without per-span lookup). When the metadata server is unreachable (local dev outside Cloud Run, network failure), the attribute defaults to `"unknown"`. The canonical doc at [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md) currently uses the shorthand `geo.cloud_region`; this spec uses the OTel semconv name `cloud.region` to align with standard tooling and to avoid a future "block all `geo.*` attributes" lint false-positive — a follow-up entry in [`FOLLOW_UPS.md`](../../../../../FOLLOW_UPS.md) tracks the canonical-doc amendment.

Every Postgres JDBC span SHALL carry:
- `db.system = "postgresql"` (auto-instrumentation).
- `db.statement` — parameterized only. Raw values MUST be stripped via the JDBC instrumentation's `setStatementSanitizationEnabled(true)` setting.

Every Redis Lettuce span SHALL carry:
- `db.system = "redis"` (auto-instrumentation).
- `db.operation` (e.g., `"EVALSHA"`, `"GET"`).
- `db.connection_string` MUST be omitted OR sanitized — the Lettuce auto-instrumentation's default `db.connection_string` value carries the Redis URI in the form `redis://user:password@host:port/db`. The Redis password is a secret per the `secretKey(env, ...)` posture; emitting it as a span attribute is forbidden. The implementation MUST configure Lettuce telemetry to either drop the attribute or strip the userinfo portion before export.

Every Realtime publish span (the `chat.realtime.publish` manual-span site) SHALL carry:
- `supabase.realtime.channel` — the channel name (e.g., `chat:{conversation_id}`). The conversation_id UUID portion is acceptable in span attributes: it is a primary key, not user-PII, and it is the natural correlation key for trace-by-conversation queries in Grafana Tempo. Raw `user_id` UUIDs remain forbidden per the forbidden-attributes contract.

Every manual `withSpan(name, attributes)` invocation SHALL include any caller-provided attributes verbatim; the helper SHALL NOT mutate, drop, or rename caller-provided attributes.

#### Scenario: Server span carries route pattern, not raw URL
- **WHEN** a request `GET /api/v1/posts/abc-123/like` is processed
- **THEN** the resulting Ktor server span has attribute `http.route = "/api/v1/posts/{post_id}/like"` AND `endpoint = "/api/v1/posts/{post_id}/like"` AND no attribute carries the literal string `"abc-123"`

#### Scenario: Server span carries hashed user.id when authenticated against a UserPrincipal
- **GIVEN** an authenticated request whose principal user id is UUID `U` AND the route is a `/api/v1/*` UserPrincipal-backed endpoint
- **WHEN** the server span is exported
- **THEN** the `user.id` attribute equals `UserIdHasher.hash(U)` (the 16-hex truncated form) AND the span carries no attribute equal to the raw UUID string of `U` AND the span does NOT carry `service.account.id`

#### Scenario: Server span carries hashed service.account.id when authenticated via OIDC service-account
- **GIVEN** an authenticated request whose verified OIDC `sub` claim is `S` AND the route is a `/internal/*` endpoint mounted under the `InternalEndpointAuth` plugin
- **WHEN** the server span is exported
- **THEN** the `service.account.id` attribute equals `ServiceAccountIdHasher.hash(S)` (the 16-hex truncated form) AND the span carries no attribute equal to the raw `S` string AND the span does NOT carry `user.id`

#### Scenario: 401-rejected `/internal/*` request span carries no principal-correlation attribute
- **GIVEN** a request to `POST /internal/unban-worker` with an invalid OIDC token (signature failure, audience mismatch, or expired)
- **WHEN** the plugin returns `401 Unauthorized` AND the server span is exported
- **THEN** the server span carries `http.status_code = 401` AND does NOT carry `service.account.id` AND does NOT carry `user.id` (no principal-correlation surface for failed-verification requests; the attacker's attempted principal MUST NOT enrich the trace surface)

#### Scenario: JDBC span carries parameterized db.statement
- **GIVEN** the JDBC instrumentation is initialized with `setStatementSanitizationEnabled(true)`
- **WHEN** a query `SELECT id FROM posts WHERE author_id = '<uuid>' AND created_at > '2026-04-01'` runs
- **THEN** the resulting span attribute `db.statement` equals `"SELECT id FROM posts WHERE author_id = ? AND created_at > ?"` AND the literal `<uuid>` and date string do NOT appear

### Requirement: Forbidden span attributes — defense-in-depth for PII

NO span produced by `:backend:ktor` SHALL ever carry these attribute keys or values:
- The raw `user_id` UUID (in any attribute, including custom names like `user_uuid`, `principal`, `actor`, etc.). Use `UserIdHasher.hash(...)` instead.
- The raw client IP read from `CF-Connecting-IP` or `X-Forwarded-For`. The sanctioned anonymization shape is `IpHasher.hash(ip)` (16-hex truncated SHA-256, exported from `:infra:otel`) — used by IP-axis rate-limit Redis keys per [`rate-limit-infrastructure/spec.md`](../rate-limit-infrastructure/spec.md). Direct embedding of the raw IP in any span attribute, log field, or Redis key segment is forbidden; the hashed form is the only sanctioned way for IP-derived values to surface in telemetry.
- The OTel HTTP server / network semconv peer-identity attributes — both OLD-semconv names (`client.address`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `http.client_ip`) AND NEW-semconv names (`client.address` unchanged, `network.peer.address`, `network.peer.port`). The OTel Java 2.x instrumentation migrated to the new HTTP semconv (verified at staging soak: `opentelemetry-ktor-3.0:2.25.0-alpha` emits `network.peer.address`); both name sets MUST be stripped to handle BOM upgrades / instrumentation alternates. When running behind Cloudflare these would carry the Cloudflare-edge peer IP (NOT the real client IP, which is sourced via `CF-Connecting-IP`); when on Cloud Run direct URL these carry the internal load-balancer link-local IP — neither shape is acceptable per project posture. The implementation MUST suppress these keys via the SDK pipeline (the canonical mechanism is a `SpanExporter` decorator that strips forbidden keys before delegate export — see [`infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt`](../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt)).
- Raw JWT tokens, raw refresh tokens, raw API bearer tokens, raw OAuth client secrets, JWKS contents, raw Supabase service role key.
- Raw JWT claims (`sub`, `aud`, `iss`, custom claims). The truncated SHA-256 token correlation id pattern from [`internal-endpoint-auth/spec.md:18`](../../../../specs/internal-endpoint-auth/spec.md) is the only sanctioned anonymization shape for token-related identifiers.
- Raw `actual_location` GIS coordinates from `posts`. Even `display_location`-derived numbers are not currently sanctioned for span attributes (the spatial-fuzzing invariant covers all telemetry surfaces). Forbid any attribute key matching `*location*`, `*lat*`, `*lng*`, `*coord*` unless explicitly sanctioned.
- Raw post `content`, raw chat message `content`, raw search query strings (e.g., the `q` query parameter on the search endpoint) — span attribute surface is observable to operators with read access; these surfaces are user-private content.
- Plaintext password fields (defensive — none are accepted by the current API but the rule preempts a future regression).
- Raw Redis cluster credentials. The Lettuce auto-instrumentation's `db.connection_string` attribute MUST be sanitized to strip the `userinfo` portion of the Redis URI (the password) — see the Mandatory span attributes requirement above.

This requirement is enforced via three defense-in-depth layers:
1. **Runtime stripping** at the SDK export pipeline via `ForbiddenAttributeStripper.kt` for auto-instrumentation peer-identity attrs the developer didn't write.
2. **Compile-time lint** at developer-written call sites via `OtelForbiddenAttributeRule` (see § "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes" below).
3. **Integration-test sentinel-string regression** at staging for end-to-end coverage of the high-velocity categories (post content, chat content, peer-IP, raw-IP-in-Lua-key, bearer token, JWT claim, search query, Redis password) — see the per-category scenarios below.

#### Scenario: Setting raw user_id on a span is a code-review blocker
- **GIVEN** a hypothetical PR adds `Span.setAttribute("user.id", userId.toString())` (raw UUID)
- **WHEN** a reviewer applies this requirement
- **THEN** the change is rejected; the PR must use `UserIdHasher.hash(userId)` instead

#### Scenario: No raw post content appears in any span
- **GIVEN** a `POST /api/v1/posts` request whose body content is `"sentinel-post-content-DO-NOT-LEAK"` AND a test that captures all spans emitted during the request
- **WHEN** the captured spans' attributes are scanned for the literal `"sentinel-post-content-DO-NOT-LEAK"` (recursive substring match across all attribute values)
- **THEN** the literal does NOT appear in any span attribute

#### Scenario: No raw chat message content appears in any span
- **GIVEN** a `POST /api/v1/chat/{conversation_id}/messages` request whose body content is `"sentinel-chat-content-DO-NOT-LEAK"` AND a test that captures all spans emitted during the request
- **WHEN** the captured spans' attributes are scanned for the literal `"sentinel-chat-content-DO-NOT-LEAK"`
- **THEN** the literal does NOT appear

#### Scenario: No raw client IP / peer IP appears in any server span
- **GIVEN** a request arriving with `CF-Connecting-IP: 1.2.3.4` AND the upstream peer IP (Cloudflare edge) being some address `E` AND the OTel HTTP server instrumentation configured with the project's attribute-suppression for the OLD-semconv keys (`client.address` / `net.peer.ip` / `net.peer.port` / `net.sock.peer.addr` / `http.client_ip`) AND the NEW-semconv keys (`client.port` / `network.peer.address` / `network.peer.port`)
- **WHEN** the resulting Ktor server span is exported
- **THEN** none of the keys `client.address`, `client.port`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `http.client_ip`, `network.peer.address`, `network.peer.port` appear on the span AND no attribute value equals `"1.2.3.4"` or the peer IP `E`

#### Scenario: No raw client IP appears in Lua key on EVALSHA span
- **GIVEN** a request arriving with `CF-Connecting-IP: 1.2.3.4` that triggers an IP-axis rate-limit check (e.g., on `/health/live`) AND a test SpanRecorder captures the Lettuce `EVALSHA` span emitted by the rate-limit Lua call
- **WHEN** the captured span's `db.statement` attribute is scanned for the literal `"1.2.3.4"` (substring match, dotted-quad)
- **THEN** the literal does NOT appear AND the `db.statement` value carries the hashed form `{ip:[0-9a-f]{16}}` instead (output of `IpHasher.hash("1.2.3.4")`)

#### Scenario: No raw bearer token appears in any span
- **GIVEN** an authenticated request whose `Authorization: Bearer <token>` header value is the well-known sentinel `"sentinel-bearer-token-DO-NOT-LEAK"` AND a test SpanRecorder captures every span emitted during the request
- **WHEN** the captured spans' attribute values, event attribute values, and span names are scanned for the literal sentinel
- **THEN** the literal does NOT appear

#### Scenario: No raw JWT claim appears in any span
- **GIVEN** an authenticated request whose JWT carries `sub = <UUID>` AND `aud = "api.nearyou.id"` AND a test SpanRecorder captures every span emitted during the request
- **WHEN** the captured spans' attribute values and event attribute values are scanned for the literal raw `<UUID>` and the literal `"api.nearyou.id"` (in any attribute key — `jwt.sub`, `jwt.aud`, custom keys)
- **THEN** the raw `<UUID>` does NOT appear in any attribute value (the `user.id` attribute uses the truncated `UserIdHasher.hash(...)` form) AND no span carries an attribute key named `jwt.sub`, `jwt.aud`, `jwt.iss`, or any other raw-claim attribute

#### Scenario: No raw search query appears in any span
- **GIVEN** an authenticated `GET /api/v1/search?q=<sentinel>` request where `<sentinel>` is the well-known string `"sentinel-search-query-DO-NOT-LEAK"` AND a test SpanRecorder captures every span emitted during the request
- **WHEN** the captured spans' attribute values are scanned for the literal sentinel
- **THEN** the literal does NOT appear in any attribute (the server span's `http.route` is the route pattern `"/api/v1/search"` — query strings are not part of the route pattern)

#### Scenario: No raw Redis password appears in `db.connection_string` (Lettuce auto-instrumentation)
- **GIVEN** the production Lettuce client is configured with a Redis URI `redis://default:<password>@<host>:<port>/0` AND `<password>` is a sentinel `"sentinel-redis-password-DO-NOT-LEAK"` AND a test SpanRecorder captures the `EVALSHA` / `GET` / `PING` Lettuce spans
- **WHEN** the captured spans' attribute values (including `db.connection_string` if emitted) are scanned for the literal sentinel
- **THEN** the literal does NOT appear (the Lettuce telemetry is configured to drop or sanitize the userinfo portion of the URI)

### Requirement: W3C Trace Context propagation on outbound HTTP from `:backend:ktor` (excluding FCM)

Every outbound HTTP request initiated by `:backend:ktor` via the JDK / CIO HTTP client SHALL carry the W3C `traceparent` header (and `tracestate` when non-empty) populated from the active `Context`. This applies to Supabase REST calls, Supabase Realtime broadcast publish, and (when shipped) the Resend wrapper. Auto-instrumentation handles propagation for these surfaces.

**FCM Admin SDK propagation is explicitly OUT of scope** for this change. The Firebase Admin SDK uses its own internal HTTP transport, and surfacing `FirebaseOptions.Builder.setHttpTransport(...)` for OTel injection requires refactoring `:infra:fcm`'s public API. Per design § D8, this is deferred to the `observability-otel-fcm-traceparent` follow-up; this change ships only the LOCAL `withSpan("fcm.dispatch", ...)` wrap (per the modified `fcm-push-dispatch` spec) without cross-service propagation. The chat-send → FCM-dispatch trace will end at the FCM dispatch local span until the follow-up ships.

#### Scenario: Outbound JDK HTTP client carries `traceparent`
- **GIVEN** an active span context AND any JDK-HTTP-client outbound from `:backend:ktor`
- **WHEN** the outbound request is captured at the test boundary
- **THEN** the request headers include `traceparent` matching the active context's trace id and span id

#### Scenario: Supabase Realtime broadcast publish carries `traceparent`
- **GIVEN** an active span context inside the chat send handler AND a `ChatRealtimeClient.publish(...)` call wrapped by `:infra:otel`'s `withSpan("chat.realtime.publish", ...)` helper
- **WHEN** the Supabase Realtime publish HTTP request to the configured Supabase endpoint is captured at the test boundary
- **THEN** the request headers include `traceparent` matching the active context (this is the only currently-shipped manual-span site requiring verified outbound propagation; the `SupabaseBroadcastChatClient` HTTP transport uses the auto-instrumented JDK client — propagation is auto, but the assertion locks the contract)

#### Scenario: Supabase REST call carries `traceparent`
- **GIVEN** an active span context inside an authenticated request handler AND a Supabase REST request from `:backend:ktor`
- **WHEN** the outbound Supabase REST request is captured at the test boundary
- **THEN** the request headers include `traceparent`

#### Scenario: FCM Admin SDK send does NOT carry `traceparent` (deferred to follow-up)
- **GIVEN** an active span context AND a `FirebaseMessaging.send(message)` invocation wrapped by `:infra:otel`'s `withSpan("fcm.dispatch", ...)` helper
- **WHEN** the FCM Admin SDK's outbound HTTPS request is captured at the test boundary
- **THEN** the request headers do NOT include `traceparent` injected by `:infra:otel` (the `firebase-admin` HTTP transport is not wired through the OTel instrumentation in this change). Locks the deferral; the follow-up `observability-otel-fcm-traceparent` will modify the fcm-push-dispatch spec to flip this scenario.

### Requirement: `withSpan` helper is the canonical manual-span surface

`:infra:otel` SHALL expose a single function `withSpan(name: String, attributes: Map<String, Any> = emptyMap(), block: () -> T): T` that:
- Creates a span with the given name and parent context.
- Applies the provided attributes verbatim (after the forbidden-attributes contract is honored at the call site).
- Records exceptions thrown from `block()` via `Span.recordException(...)` and `Span.setStatus(StatusCode.ERROR)`.
- Re-throws the original exception (no swallowing) — exception transparency is mandatory so error-handling logic at call sites is unchanged.
- Closes the span on block exit (success OR exception) via try/finally.

Manual span sites (Realtime publish, FCM dispatch, rate-limit Lua call, any future custom span) SHALL go through this helper. Direct `tracer.spanBuilder(...)` use is allowed only inside `:infra:otel` itself.

#### Scenario: Block returns normally → span ends with OK status
- **GIVEN** a `withSpan("test.op")` invocation whose block returns a value `V`
- **WHEN** the helper completes
- **THEN** the helper returns `V` AND a span named `"test.op"` is captured with status OK

#### Scenario: Block throws Exception subclass → recorded and re-thrown
- **GIVEN** a `withSpan("test.op")` invocation whose block throws `IllegalStateException("boom")`
- **WHEN** the helper completes
- **THEN** the helper re-throws `IllegalStateException("boom")` (caller observes the original exception) AND a span named `"test.op"` is captured with status ERROR AND `Span.recordException` captured the thrown exception

#### Scenario: Block throws CancellationException (coroutine cancellation) → recorded and re-thrown
- **GIVEN** a `withSpan("test.op")` invocation whose block throws `kotlinx.coroutines.CancellationException("cancelled by caller")` (reachable when an outer coroutine cancels the request scope mid-block — relevant for chat send handlers per `chat-realtime-broadcast` design)
- **WHEN** the helper completes
- **THEN** the helper re-throws `CancellationException` (NOT swallowed — coroutine cancellation must propagate or the parent scope hangs) AND a span named `"test.op"` is captured with status ERROR AND `Span.recordException` captured the cancellation

#### Scenario: Block throws Throwable subclass (e.g., Error) → span ends via try/finally
- **GIVEN** a `withSpan("test.op")` invocation whose block throws `OutOfMemoryError("simulated")` (a `java.lang.Error` subclass — `Throwable` not `Exception`)
- **WHEN** the helper completes
- **THEN** the helper re-throws `OutOfMemoryError` AND the span is closed via try/finally (`Span.end()` runs even though the throwable is not an Exception subclass) AND no span resource leak occurs

#### Scenario: Nested `withSpan` calls produce parent-child relationship
- **GIVEN** a nested invocation `withSpan("outer") { withSpan("inner") { ... } }` AND a SpanRecorder captures both spans
- **WHEN** both spans complete
- **THEN** the captured `inner` span's parent context references the captured `outer` span's span id (the inner span sees the outer as parent in the trace tree)

#### Scenario: Caller-provided attributes appear verbatim
- **GIVEN** a `withSpan("test.op", mapOf("foo" to "bar", "n" to 42))` invocation
- **WHEN** the span is exported
- **THEN** the span carries attribute `foo="bar"` AND `n=42`

### Requirement: `IpHasher` SHALL anonymize client IP for span-attribute and rate-limit-key surfaces

`:infra:otel` SHALL expose `IpHasher.hash(ip: String): String` that returns the first 16 hex characters of `SHA-256(ip.toByteArray(StandardCharsets.UTF_8))` (16 hex = 64-bit truncated digest). Every site that constructs an IP-axis Redis key for the `RateLimiter.tryAcquireByKey` overload SHALL go through this helper. Embedding the raw IPv4 dotted-quad or IPv6 colon-delimited literal in the Lua key (which surfaces in Tempo `db.statement` span attributes on the Lettuce `EVALSHA` span AND in the `key=` field of structured logs mandated by [`rate-limit-infrastructure/spec.md`](../rate-limit-infrastructure/spec.md)) is forbidden.

The truncation length and digest function are fixed (changing them is an explicit follow-up change requiring a separate proposal). The shape mirrors `UserIdHasher` ("first 16 hex chars of `SHA-256(...)`"), keeping the operator mental model unified across user/IP/token correlation IDs.

`IpHasher.hash` MUST `require(ip.isNotBlank())` defensively — blank input is a `clientIp` extraction regression and silently collapsing disparate requests to a single shared rate-limit bucket would invert the intent of the limiter. The fail-fast guard makes regressions in `ClientIpExtractor` immediately observable.

`IpHasher` accepts whatever non-blank literal string the caller supplies (the `clientIp` request-context value from `ClientIpExtractor`). It does NOT normalize IPv6 forms (`::1` vs `0:0:0:0:0:0:0:1` vs `2001:DB8::1` vs `2001:db8::1` all hash differently) AND it does NOT trim whitespace (`"1.2.3.4 "` vs `"1.2.3.4"` hash differently — `ClientIpExtractor` is the canonical trim site). Cloudflare's emission shape is deterministic per request-edge, so two semantically-equivalent forms reaching the helper from the same client is not a real-world concern. If a future IPv6 audit shows form-drift causing rate-limit-bypass, normalization can be added without breaking the shape contract.

#### Scenario: Hash is deterministic
- **GIVEN** an IP literal `I` (e.g., `"1.2.3.4"`)
- **WHEN** `IpHasher.hash(I)` is invoked twice
- **THEN** both calls return the identical 16-character hex string

#### Scenario: Hash differs between distinct IPs
- **GIVEN** two distinct IP literals `I1 != I2` (e.g., `"1.2.3.4"` and `"5.6.7.8"`)
- **WHEN** `IpHasher.hash(I1)` and `IpHasher.hash(I2)` are computed
- **THEN** the two return values differ

#### Scenario: Hash output is exactly 16 hex characters
- **GIVEN** any non-blank IP literal
- **WHEN** `IpHasher.hash(ip)` is invoked
- **THEN** the return value matches the regex `^[0-9a-f]{16}$`

#### Scenario: Hash output is exactly 16 hex characters across many random IPv4 addresses
- **GIVEN** 1000 randomly-generated IPv4 literals (each `<a>.<b>.<c>.<d>` with `0 <= a,b,c,d <= 255`)
- **WHEN** `IpHasher.hash(ip)` is invoked for each
- **THEN** every return value matches the regex `^[0-9a-f]{16}$`

#### Scenario: IPv6 input produces 16-hex output
- **GIVEN** the IPv6 literal `"2001:db8::1"`
- **WHEN** `IpHasher.hash(ip)` is invoked
- **THEN** the return value matches the regex `^[0-9a-f]{16}$` (no IPv6-specific path; same shape as IPv4)

#### Scenario: Blank input fails fast
- **GIVEN** a blank IP literal (`""`, `" "`, `"\t"`, or any string where `ip.isBlank()` is true)
- **WHEN** `IpHasher.hash(ip)` is invoked
- **THEN** an `IllegalArgumentException` is thrown (via `require(ip.isNotBlank())`) — defensive guard against a regression in `ClientIpExtractor` that would otherwise collapse disparate requests to one bucket

#### Scenario: Whitespace is not trimmed
- **GIVEN** the IP literal `"1.2.3.4"` and the same value with trailing whitespace `"1.2.3.4 "`
- **WHEN** `IpHasher.hash` is invoked on each
- **THEN** the two return values differ (no implicit trim — `ClientIpExtractor` is the canonical trim site, the hasher is byte-exact on its input)

#### Scenario: IPv6 case sensitivity is preserved
- **GIVEN** the IPv6 literal `"2001:DB8::1"` and its lowercase form `"2001:db8::1"`
- **WHEN** `IpHasher.hash` is invoked on each
- **THEN** the two return values differ (no IPv6 normalization — the design explicitly defers normalization to a future change if needed)

### Requirement: `OtelForbiddenAttributeRule` fences forbidden span-attribute writes

The repo SHALL ship a custom Detekt rule `OtelForbiddenAttributeRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`. The rule MUST fire on any Kotlin string-literal (`KtStringTemplateExpression`) whose source text contains one of the forbidden-attribute-key tokens OR matches one of the sensitive-value regex patterns enumerated below, unless the containing file is allowlisted (§ "Allowlist for `OtelForbiddenAttributeRule`") or the enclosing declaration is annotated `@AllowForbiddenSpanAttribute("<reason>")` (§ "`@AllowForbiddenSpanAttribute` annotation bypasses the rule").

**Tier 1 — forbidden-attribute-key literals (anywhere)**:

The rule MUST fire on any string literal exactly equal to one of the following 21 keys. The list is a SUPERSET of the runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` enumeration in [`infra/otel/.../ForbiddenAttributeStripper.kt`](../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) MINUS one documented carve-out (`"user_id"`):

**Group A — `ForbiddenAttributeStripper.FORBIDDEN_KEYS` mirror minus the `user_id` carve-out (10)**:
- HTTP client-identity semconv: `client.address`, `client.port`, `http.client_ip`
- New OTel-Java-2.x peer semconv: `network.peer.address`, `network.peer.port`
- Old OTel-Java-1.x peer semconv: `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`
- User-id typo-defensive variants (carve-out applied): `user_uuid`, `user.uuid` (the sanctioned key is `user.id` with `UserIdHasher.hash(...)` consumption)

`"user_id"` from `FORBIDDEN_KEYS` is INTENTIONALLY EXCLUDED from Group A because it appears in 12 production paths today as a SQL column name (`rs.getObject("user_id", UUID::class.java)`), `@SerialName` JSON key, and Ktor route parameter (`call.parameters["user_id"]`) — semantically unrelated to OTel attribute writes. A lint exact-match on `"user_id"` would produce ~12 false positives with no canonical fix. The runtime stripper at `ForbiddenAttributeStripper.FORBIDDEN_KEYS` continues to handle emitted `"user_id"` attributes defensively at export time, AND the integration-test sentinel scenario "No raw user_id appears in any span" covers value-side leakage. See [`design.md`](../../../changes/otel-attribute-lint-rule/design.md) § Decision 3 for the full rationale + the deferred follow-up that would re-introduce `"user_id"` under PSI-context-restricted Mode A enforcement.

**Group B — symmetric typo-defensive underscore variants for HTTP / network semconv keys (8)**:
- `client_address`, `client_port`, `http_client_ip` (underscore variants of Group A's HTTP client-identity keys)
- `network_peer_address`, `network_peer_port` (underscore variants of the new peer semconv)
- `net_peer_ip`, `net_peer_port`, `net_sock_peer_addr` (underscore variants of the old peer semconv)

**Group C — JWT-claim attribute keys (3)**:
- `jwt.sub`, `jwt.aud`, `jwt.iss` (per canonical spec § "Forbidden span attributes" bullet 5 — raw JWT claims forbidden on any span)

These keys SHALL NEVER appear as Kotlin string literals outside the path allowlist (next requirement). The Group A subset MUST satisfy the relationship `lint Group A.containsAll(FORBIDDEN_KEYS - {"user_id"})` — the synchronization-guard test (§ "Detekt test coverage" item 11) asserts this with a failure message naming both any missing key AND the carve-out rationale.

**Tier 2 — sensitive-value regex patterns (anywhere)**:

The rule MUST fire on any string literal matching one of these high-confidence sensitive-value regex patterns:
- `\-{5}BEGIN [A-Z ]+PRIVATE KEY\-{5}` — RSA / EC / Ed25519 PEM private key marker. Never legitimate in source code outside test fixtures.
- `eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.` — JWT shape (base64url header `eyJ...` + `.` + base64url payload `eyJ...` + trailing `.`).
- `redis://[^:]+:[^@/]+@` — Redis URI with explicit userinfo (password embedded).
- `"kty"\s*:\s*"RSA"\s*,?\s*"n"\s*:` — JWKS RSA-key JSON shape (presence of `"kty": "RSA"` followed by `"n":` modulus). Specific enough to avoid false-positives on legitimate JSON-with-`kty` mentions.

#### Scenario: Group A Tier 1 key literal fires (exact mirror of FORBIDDEN_KEYS)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"client.address"` (e.g., as `span.setAttribute("client.address", ...)`)
- **THEN** `OtelForbiddenAttributeRule` reports a code smell on that literal

#### Scenario: Group B Tier 1 key literal fires (typo-defensive underscore variant)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"client_address"` (underscore variant) — a likely typo-bypass attempt of `client.address`
- **THEN** the rule fires (the literal IS in Tier 1 Group B)

#### Scenario: Group C Tier 1 key literal fires (JWT-claim attribute)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"jwt.sub"` (e.g., as a setAttribute key)
- **THEN** the rule fires (raw JWT claims on spans are forbidden per canonical spec § "Forbidden span attributes" bullet 5)

#### Scenario: User-id typo variant `user_uuid` literal fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"user_uuid"` (Group A typo-defensive variant)
- **THEN** the rule fires

#### Scenario: `user_id` literal does NOT fire (carve-out)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"user_id"` (e.g., `rs.getObject("user_id", UUID::class.java)` or `call.parameters["user_id"]`)
- **THEN** the rule does NOT fire (Group A explicitly excludes `"user_id"` — see Tier 1 description above; runtime stripper handles defensively at export, sentinel-string regression scenario covers value-side leakage)

#### Scenario: Tier 1 forbidden-attribute key literal in a `mapOf(...)` passed to `withSpan(...)` fires
- **WHEN** a non-allowlisted Kotlin file contains `withSpan("foo", mapOf("network.peer.address" to clientAddr)) { ... }`
- **THEN** the rule fires on the `"network.peer.address"` literal (`withSpan`'s `mapOf` keys are checked as string literals)

#### Scenario: Tier 2 sensitive-value pattern fires on PEM marker
- **WHEN** a non-allowlisted Kotlin file contains a string literal containing `-----BEGIN RSA PRIVATE KEY-----`
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on JWT-shaped literal
- **WHEN** a non-allowlisted Kotlin file contains a string literal `"eyJhbGciOiJSUzI1NiI.eyJzdWIiOiJ4eHgi.signature"` (illustrative three-segment JWT shape)
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on Redis URI with userinfo
- **WHEN** a non-allowlisted Kotlin file contains a string literal `"redis://default:my-redis-password@redis.example:6379/0"`
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on JWKS RSA shape
- **WHEN** a non-allowlisted Kotlin file contains a string literal containing `{"kty":"RSA","n":"...","e":"AQAB"}` (JWKS RSA-key JSON shape)
- **THEN** the rule fires

#### Scenario: Sanctioned `UserIdHasher.hash` consumption does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `span.setAttribute("user.id", UserIdHasher.hash(userId))`
- **THEN** the rule does NOT fire (the literal `"user.id"` is NOT in Tier 1 — Tier 1 Group A catches typo variants `user_id` / `user_uuid` / `user.uuid`; `user.id` is the sanctioned key paired with the `UserIdHasher.hash(...)` helper consumption)

#### Scenario: Unrelated string literal does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `val msg = "Processing request"` or `"INSERT INTO posts (...) VALUES (...)"`
- **THEN** the rule does NOT fire

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `OtelForbiddenAttributeRule`

### Requirement: Allowlist for `OtelForbiddenAttributeRule`

The rule SHALL NOT fire in any of these allowed contexts:

1. **Any test source path**: files whose `virtualFilePath` contains `/src/test/` (mirrors `RedisHashTagRule` / `CoordinateJitterRule` precedent). Test fixtures across the codebase legitimately contain raw fixtures (`{ip:1.2.3.4}` for limiter behavior tests at `infra/redis/src/test/`, regex-string canonical-shape assertions at `backend/ktor/src/test/`, etc.).
2. **`:infra:otel` module main sources**: files whose `virtualFilePath` contains `/infra/otel/src/main/`. This module enumerates forbidden keys as DATA (the `FORBIDDEN_KEYS` Set in `ForbiddenAttributeStripper.kt`).
3. **`:lint:detekt-rules` module main sources**: files whose `virtualFilePath` contains `/lint/detekt-rules/src/main/`. The rule itself necessarily contains the forbidden patterns as DATA / REGEX CONSTANTS.
4. **Annotation bypass**: enclosing declaration (function, class, property — or any ancestor declaration) annotated `@AllowForbiddenSpanAttribute(reason: String)` with a non-empty, non-blank reason string. Empty-string or whitespace-only-reason silent bypass (`@AllowForbiddenSpanAttribute("")`, `@AllowForbiddenSpanAttribute("   ")`) is forbidden — the rule MUST fire if the reason is empty or only whitespace (mirroring `RedisHashTagRule`'s `@AllowRawRedisKey` `isNotBlank()` precedent at `RedisHashTagRule.kt:134`).

All four allowlist gates MUST support the detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror the approach in `BlockExclusionJoinRule.isAllowedPath`).

#### Scenario: `/src/test/` source allowlist suppresses
- **WHEN** a file under any `/src/test/` path contains a literal `"{scope:health}:{ip:1.2.3.4}"` (raw IP) OR `"client.address"` (Tier 1 Group A key) OR `"-----BEGIN RSA PRIVATE KEY-----"` (Tier 2 PEM)
- **THEN** the rule does NOT fire on any of those literals

#### Scenario: `:infra:otel` main source passes
- **WHEN** a file under `/infra/otel/src/main/kotlin/.../ForbiddenAttributeStripper.kt` contains a `Set` literal enumerating `"client.address"`, `"net.peer.ip"`, etc.
- **THEN** the rule does NOT fire on any of those literals

#### Scenario: `:lint:detekt-rules` main source passes
- **WHEN** a file under `/lint/detekt-rules/src/main/kotlin/.../OtelForbiddenAttributeRule.kt` contains the regex constants enumerating the forbidden keys + value patterns
- **THEN** the rule does NOT fire

#### Scenario: `@AllowForbiddenSpanAttribute` on function with non-empty reason suppresses
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("admin span exempt — see Decision N in design.md")` contains `span.setAttribute("client.address", ...)` (a Tier 1 forbidden key)
- **THEN** the rule does NOT fire on that literal

#### Scenario: `@AllowForbiddenSpanAttribute` on enclosing class suppresses
- **WHEN** a class annotated `@AllowForbiddenSpanAttribute("admin telemetry escape hatch")` contains a method with `span.setAttribute("net.peer.ip", ...)`
- **THEN** the rule does NOT fire

#### Scenario: `@AllowForbiddenSpanAttribute("")` (empty reason) still fires
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("")` contains `"client.address"`
- **THEN** the rule reports a code smell on that literal (empty-reason silent bypass is rejected — `isNotBlank()` precedent)

#### Scenario: `@AllowForbiddenSpanAttribute("   ")` (whitespace-only reason) still fires
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("   ")` (only whitespace) contains `"client.address"`
- **THEN** the rule reports a code smell on that literal (whitespace-only-reason silent bypass is rejected — same `isNotBlank()` precedent)

#### Scenario: `@AllowForbiddenSpanAttribute("x")` (single non-blank char) passes
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("x")` (single non-blank char) contains `"client.address"`
- **THEN** the rule does NOT fire (non-blank is sufficient; the rule's job is to require a reason exists, not to assess its quality)

#### Scenario: Synthetic-file-harness via package-FQN fallback
- **WHEN** the detekt-test `lint(String)` synthetic harness loads a fixture whose package FQN starts with `id.nearyou.lint.detekt` AND the fixture's content has no `virtualFilePath`
- **THEN** the rule treats the fixture as an allowlisted source (package-FQN-fallback precedent)

### Requirement: Detekt test coverage for `OtelForbiddenAttributeRule`

`lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt` SHALL cover, at minimum:

1. **Tier 1 Group A positive-fail**: each of the 10 Group A keys triggers from a synthetic non-allowlisted file (one test per key — `client.address`, `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `user_uuid`, `user.uuid`). PLUS one explicit positive-pass test for `"user_id"` (the carve-out): rule does NOT fire on a fixture `rs.getObject("user_id", UUID::class.java)` to validate the carve-out behavior.
2. **Tier 1 Group B positive-fail**: each of the 8 underscore-variant typo-defensive keys triggers (e.g., `"client_address"`, `"network_peer_address"`).
3. **Tier 1 Group C positive-fail**: each of the 3 JWT-claim keys triggers (`"jwt.sub"`, `"jwt.aud"`, `"jwt.iss"`).
4. **Tier 1 positive-pass**: `:infra:otel/src/main/` source path allowlist suppresses every Tier 1 key; `:lint:detekt-rules/src/main/` source path allowlist suppresses every Tier 1 key; `/src/test/` path allowlist suppresses across the board.
5. **Tier 2 positive-fail**: each of the 4 Tier 2 regex patterns fires from a synthetic non-allowlisted file (PEM marker; JWT three-segment shape; Redis URI with userinfo; JWKS RSA shape).
6. **Tier 2 false-positive negative tests**: legitimate strings that look near a Tier 2 pattern but should NOT fire — e.g., `"eyJfoo"` alone (single segment, not JWT) → no fire; `"redis://host:6379/0"` (no userinfo) → no fire; `"-----BEGIN PUBLIC KEY-----"` (PUBLIC not PRIVATE) → no fire.
7. **Sanctioned `UserIdHasher.hash` consumption positive-pass**: `span.setAttribute("user.id", UserIdHasher.hash(userId))` does NOT fire.
8. **Allowlist by path**: `:infra:otel/src/main/` path → no fire; `:lint:detekt-rules/src/main/` path → no fire; `/src/test/` path → no fire on Tier 1/Tier 2 literals; arbitrary `:backend:ktor/src/main/` path → fires.
9. **Annotation bypass with non-empty reason**: `@AllowForbiddenSpanAttribute("reason")` on function suppresses; on enclosing class suppresses.
10. **Empty-reason / whitespace-only-reason annotation still fires**: `@AllowForbiddenSpanAttribute("")` does NOT suppress; `@AllowForbiddenSpanAttribute("   ")` does NOT suppress; `@AllowForbiddenSpanAttribute("\t")` does NOT suppress.
11. **Synchronization guard test**: a test asserting the rule's Tier 1 Group A list `containsAll (FORBIDDEN_KEYS - {"user_id"})` — superset relationship with one documented carve-out (regression guard against silent drift). Test failure message MUST name both the missing key(s) AND the carve-out rationale, so the implementer adding to `FORBIDDEN_KEYS` knows whether the new key belongs in Tier 1 or is a similar carve-out case.
12. **Synthetic-file-harness package-FQN fallback**: package `id.nearyou.lint.detekt.*` is treated as allowlisted source.
13. **Composition with existing rules**: a fixture that contains both `"actual_location"` (triggering `CoordinateJitterRule`) and `"client.address"` (triggering `OtelForbiddenAttributeRule`) produces exactly 2 findings, one per rule (no double-counting, no cross-suppression). A fixture with `"rate:health:{ip:1.2.3.4}"` triggers both `RedisHashTagRule` (legacy prefix) and `OtelForbiddenAttributeRule` IP-axis mode independently — 2 findings.
14. **Unrelated string literals**: a non-allowlisted file containing `"Processing request"` / `"INSERT INTO posts ..."` / `"SELECT * FROM users WHERE id = ?"` does NOT fire.
15. **NearYouRuleSetProvider registration**: explicit fixture asserting the rule appears in the returned `RuleSet`.

#### Scenario: Test class exists and passes
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every scenario above corresponds to at least one test case AND all cases pass

### Requirement: Detekt run against the backend codebase remains green after rule activation

As of this change, `./gradlew detekt` SHALL pass on the backend + infra + lint modules with `OtelForbiddenAttributeRule` registered and active. Every existing `Span.setAttribute(...)` / `withSpan(...)` / IP-axis Redis-key literal call site (sanctioned via `UserIdHasher.hash` / `IpHasher.hash` already, or living in `/src/test/` paths) MUST pass the rule. If any pre-existing call site fires the rule, the implementation MUST either fix the call site (preferred — convert to canonical helper consumption) OR add the appropriate path allowlist / annotation. The implementation MUST audit:

- Production `Span.setAttribute(...)` writers (today: 1 — `AuthPlugin.kt:115`, sanctioned).
- Production `withSpan(name, mapOf(...))` writers (today: 2 — `ChatRoutes.kt:354` with safe keys, `FcmDispatcher.kt:159` with safe keys).
- Production `AttributesBuilder.put(...)` writers (today: in `:infra:otel/src/main/` — allowlisted by path).
- Production `tryAcquireByKey(...)` first-arg literals (today: 1 — `HealthRoutes.kt:166-170`, sanctioned via `IpHasher.hash`).

All four surfaces today pass the rule by construction. The audit is a task-level check; the rule MUST not fire on any of them.

#### Scenario: Detekt green post-merge
- **WHEN** running `./gradlew detekt` after this change merges
- **THEN** the command exits 0 with no `OtelForbiddenAttributeRule` findings

