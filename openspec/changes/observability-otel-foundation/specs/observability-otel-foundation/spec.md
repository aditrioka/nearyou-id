## ADDED Requirements

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

### Requirement: `OtelBootstrap.start(env)` initializes the SDK at Ktor startup, idempotent and exception-safe

The `:infra:otel` module SHALL expose a single startup entrypoint `OtelBootstrap.start(env: KtorEnv)` that initializes the OTel `SdkTracerProvider`, configures the per-env exporter, registers the global `OpenTelemetry` instance, and wires auto-instrumentation for HikariCP / Lettuce / outbound HTTP clients. The function SHALL be idempotent — a second call within the same JVM SHALL be a no-op that logs an INFO line and returns. The function SHALL NOT throw on misconfiguration; instead, the bootstrap SHALL fall back to the no-op exporter shape (per the Exporter token absence requirement) and log a single INFO line.

`Application.module()` in `:backend:ktor` SHALL invoke `OtelBootstrap.start(env)` exactly once before any other module init (so subsequent module inits get auto-instrumented).

#### Scenario: First call initializes the SDK
- **WHEN** `OtelBootstrap.start(env)` runs for the first time in the JVM
- **THEN** the global `OpenTelemetry` instance is registered AND a `Tracer` named `"id.nearyou.backend"` is obtainable AND auto-instrumentation hooks for HikariCP / Lettuce / the outbound HTTP client are installed

#### Scenario: Second call is a no-op
- **WHEN** `OtelBootstrap.start(env)` is invoked twice in the same JVM
- **THEN** the second call returns immediately AND emits an INFO log with `event="otel_bootstrap_already_initialized"` AND does not re-register the SDK

#### Scenario: Exporter misconfiguration does not crash the application
- **GIVEN** the exporter endpoint URL resolves to an unreachable host
- **WHEN** `OtelBootstrap.start(env)` is invoked
- **THEN** the function returns normally AND emits an INFO log AND the no-op exporter shape is active AND `Application.module()` continues startup unblocked

### Requirement: Sampling profile per environment matches the canonical profile

The sampling profile SHALL be selected by `KtorEnv` value:
- `dev` AND `staging` → `Sampler.alwaysOn()` (head 100%) per the Phase 2 §14 benchmark requirement at [`docs/05-Implementation.md:2042`](../../../../../docs/05-Implementation.md).
- `production` → composed sampler: `ParentBased(TraceIdRatioBased(0.1))` (10% base) + force-keep `SpanProcessor` that promotes any RecordOnly span where `http.status_code >= 500` OR `exception.escaped == true` OR (root-span only) `duration_ms > 500` is satisfied.

The composed production sampler MUST NOT export base-ratio-rejected spans where none of the force-keep predicates fire — those spans terminate with `SamplingResult.drop()` semantics post-record. The 10%-base ratio MUST be configurable via a single constant in `:infra:otel` (so a future tuning change is a one-line edit).

#### Scenario: Dev environment samples at 100%
- **GIVEN** `env = KtorEnv.dev`
- **WHEN** `OtelBootstrap.start(env)` initializes the SDK
- **THEN** the configured root `Sampler` reports `SamplingDecision.RECORD_AND_SAMPLE` for every synthetic root span tested

#### Scenario: Staging environment samples at 100% (parity with dev)
- **GIVEN** `env = KtorEnv.staging`
- **WHEN** `OtelBootstrap.start(env)` initializes the SDK
- **THEN** the configured root `Sampler` reports `SamplingDecision.RECORD_AND_SAMPLE` for every synthetic root span tested (equivalent to dev — Phase 2 §14 benchmark needs full traces) AND the sampler is NOT the production composed sampler (defense against a copy-paste regression that would silently demote staging to the prod 10%-base shape)

#### Scenario: Production environment samples at 10% base
- **GIVEN** `env = KtorEnv.production` AND a synthetic root span with `http.status_code = 200` AND `duration_ms = 100`
- **WHEN** the sampler is invoked across 1000 trace-id seeds
- **THEN** between 5% and 15% of the seeds yield `SamplingDecision.RECORD_AND_SAMPLE` (statistical tolerance for a 10% target)

#### Scenario: Production sampler force-keeps an error span
- **GIVEN** `env = KtorEnv.production` AND a synthetic root span tagged `http.status_code = 500`
- **WHEN** the span ends
- **THEN** the span is exported regardless of the trace-id seed (force-keep predicate satisfied)

#### Scenario: Production sampler force-keeps a slow span
- **GIVEN** `env = KtorEnv.production` AND a synthetic root span with `http.status_code = 200` AND a recorded duration of 800ms
- **WHEN** the span ends
- **THEN** the span is exported regardless of the trace-id seed (slow-promotion predicate satisfied: 800ms > 500ms threshold)

#### Scenario: Production sampler force-keeps an exception-escaped span at status_code 200
- **GIVEN** `env = KtorEnv.production` AND a synthetic root span with `http.status_code = 200` AND `exception.escaped = true` AND `duration_ms = 100`
- **WHEN** the span ends
- **THEN** the span is exported regardless of the trace-id seed (force-keep predicate satisfied via exception, not status code; locks the OR-shape of the predicate so a future regression making it AND would be caught)

#### Scenario: Production sampler drops a fast healthy span outside the base ratio
- **GIVEN** `env = KtorEnv.production` AND a synthetic root span with `http.status_code = 200` AND `duration_ms = 50` AND a trace-id seed that falls in the 90% drop window
- **WHEN** the span ends
- **THEN** the span is NOT exported (base ratio rejected AND no force-keep predicate fired)

### Requirement: OTLP exporter target is sourced via `secretKey(env, ...)` with a clean no-op fallback

The exporter target endpoint URL SHALL be read via `secretKey(env, "otel-grafana-otlp-endpoint")` and the bearer token via `secretKey(env, "otel-grafana-otlp-token")`. Both lookups SHALL go through the existing environment-aware `secretKey(env, name)` helper — direct secret-name reads are forbidden by the existing `SecretKeyHelperRule` Detekt rule and this requirement does not relax it.

When EITHER lookup returns `null`, `OtelBootstrap` SHALL configure a `LoggingSpanExporter` (DEBUG severity, dropped by default Logback config — effectively no-op for production logging volume) and emit exactly one INFO startup log line: `event="otel_exporter_disabled" reason="<endpoint_missing|token_missing>" sampling_profile="<profile_name>"`. The application start-up SHALL NOT block, fail, or retry.

The OTLP token VALUE SHALL NEVER appear in any log line, span attribute, span name, or HTTP request body produced by `:backend:ktor` outside the OTLP exporter's own outbound HTTPS request to the endpoint.

#### Scenario: Token absent → no-op exporter + single INFO line
- **GIVEN** `secretKey(env, "otel-grafana-otlp-token")` returns null
- **WHEN** `OtelBootstrap.start(env)` runs
- **THEN** exactly one INFO log line is emitted with `event="otel_exporter_disabled"` AND `reason="token_missing"` AND the `LoggingSpanExporter` is the configured exporter

#### Scenario: Endpoint absent → no-op exporter + single INFO line
- **GIVEN** `secretKey(env, "otel-grafana-otlp-endpoint")` returns null
- **WHEN** `OtelBootstrap.start(env)` runs
- **THEN** exactly one INFO log line is emitted with `event="otel_exporter_disabled"` AND `reason="endpoint_missing"`

#### Scenario: Both secrets absent → exactly one INFO line, deterministic precedence
- **GIVEN** both `secretKey(env, "otel-grafana-otlp-endpoint")` AND `secretKey(env, "otel-grafana-otlp-token")` return null
- **WHEN** `OtelBootstrap.start(env)` runs
- **THEN** exactly ONE INFO log line is emitted (NOT two — the implementation SHALL short-circuit on the first missing lookup) AND `reason` carries a single deterministic value (the implementation MAY choose either `"endpoint_missing"` or `"token_missing"` but the choice SHALL be stable across runs)

#### Scenario: Both secrets present → live OTLP exporter wired
- **GIVEN** both `secretKey(env, "otel-grafana-otlp-endpoint")` and `secretKey(env, "otel-grafana-otlp-token")` return non-null values
- **WHEN** `OtelBootstrap.start(env)` runs
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
- `user.id` (set via `UserIdHasher.hash(...)`) when the request is authenticated.
- `cloud.region` (OTel semconv name; read from `K_SERVICE_REGION` env var; constant per Cloud Run instance). The canonical doc at [`docs/04-Architecture.md:398`](../../../../../docs/04-Architecture.md) currently uses the shorthand `geo.cloud_region`; this spec uses the OTel semconv name `cloud.region` to align with standard tooling and to avoid a future "block all `geo.*` attributes" lint false-positive — a follow-up entry in [`FOLLOW_UPS.md`](../../../../../FOLLOW_UPS.md) tracks the canonical-doc amendment.

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

#### Scenario: Server span carries hashed user.id when authenticated
- **GIVEN** an authenticated request whose principal user id is UUID `U`
- **WHEN** the server span is exported
- **THEN** the `user.id` attribute equals `UserIdHasher.hash(U)` (the 16-hex truncated form) AND the span carries no attribute equal to the raw UUID string of `U`

#### Scenario: JDBC span carries parameterized db.statement
- **GIVEN** the JDBC instrumentation is initialized with `setStatementSanitizationEnabled(true)`
- **WHEN** a query `SELECT id FROM posts WHERE author_id = '<uuid>' AND created_at > '2026-04-01'` runs
- **THEN** the resulting span attribute `db.statement` equals `"SELECT id FROM posts WHERE author_id = ? AND created_at > ?"` AND the literal `<uuid>` and date string do NOT appear

### Requirement: Forbidden span attributes — defense-in-depth for PII

NO span produced by `:backend:ktor` SHALL ever carry these attribute keys or values:
- The raw `user_id` UUID (in any attribute, including custom names like `user_uuid`, `principal`, `actor`, etc.). Use `UserIdHasher.hash(...)` instead.
- The raw client IP read from `CF-Connecting-IP` or `X-Forwarded-For`. (No truncated form is currently sanctioned; if needed, file a follow-up that introduces an `ip.cidr` truncated attribute.)
- The OTel HTTP server semconv attributes `client.address`, `net.peer.ip`, `net.sock.peer.addr`, `http.client_ip`. The auto-instrumentation attaches these by default — when running behind Cloudflare they would carry the Cloudflare-edge peer IP (NOT the real client IP, which is sourced via `CF-Connecting-IP`), but the project's posture forbids exposing peer-IPs at all. The implementation MUST configure the Ktor server instrumentation to suppress these keys (`SpanCustomizer` / attribute-filter pattern, OTel SDK feature-supported).
- Raw JWT tokens, raw refresh tokens, raw API bearer tokens, raw OAuth client secrets, JWKS contents, raw Supabase service role key.
- Raw JWT claims (`sub`, `aud`, `iss`, custom claims). The truncated SHA-256 token correlation id pattern from [`internal-endpoint-auth/spec.md:18`](../../../../specs/internal-endpoint-auth/spec.md) is the only sanctioned anonymization shape for token-related identifiers.
- Raw `actual_location` GIS coordinates from `posts`. Even `display_location`-derived numbers are not currently sanctioned for span attributes (the spatial-fuzzing invariant covers all telemetry surfaces). Forbid any attribute key matching `*location*`, `*lat*`, `*lng*`, `*coord*` unless explicitly sanctioned.
- Raw post `content`, raw chat message `content`, raw search query strings (e.g., the `q` query parameter on the search endpoint) — span attribute surface is observable to operators with read access; these surfaces are user-private content.
- Plaintext password fields (defensive — none are accepted by the current API but the rule preempts a future regression).
- Raw Redis cluster credentials. The Lettuce auto-instrumentation's `db.connection_string` attribute MUST be sanitized to strip the `userinfo` portion of the Redis URI (the password) — see the Mandatory span attributes requirement above.

This requirement is enforced at code-review time. A follow-up Detekt rule (`OtelForbiddenAttributeRule`) is reserved for a future hardening change once the writer surface is concrete; this change does not ship the rule but the spec encodes the contract that the rule will enforce.

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
- **GIVEN** a request arriving with `CF-Connecting-IP: 1.2.3.4` AND the upstream peer IP (Cloudflare edge) being some address `E` AND the OTel HTTP server instrumentation configured with the project's attribute-suppression for `client.address` / `net.peer.ip` / `net.sock.peer.addr` / `http.client_ip`
- **WHEN** the resulting Ktor server span is exported
- **THEN** none of the keys `client.address`, `net.peer.ip`, `net.sock.peer.addr`, `http.client_ip` appear on the span AND no attribute value equals `"1.2.3.4"` or the peer IP `E`

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
- **GIVEN** the Redis connection URI is `redis://default:<sentinel>@<host>:6379/0` where `<sentinel>` is the well-known string `"sentinel-redis-password-DO-NOT-LEAK"` AND a test SpanRecorder captures every Redis span emitted during a representative cache read
- **WHEN** the captured Redis spans' attribute values are scanned for the literal sentinel
- **THEN** the literal does NOT appear in `db.connection_string` or any other attribute (the Lettuce telemetry is configured to either drop the attribute or sanitize the userinfo portion)

### Requirement: W3C Trace Context propagation on outbound HTTP from `:backend:ktor`

Every outbound HTTP request initiated by `:backend:ktor` SHALL carry the W3C `traceparent` header (and `tracestate` when non-empty) populated from the active `Context`. This applies to: the JDK / Apache HTTP client used by the Resend wrapper, Supabase REST calls, Supabase Realtime broadcast publish; and the Firebase Admin SDK FCM send (which uses its own internal HTTP client — propagation is achieved via manual `withSpan` wrapping that injects the header on the way out, per design § D8).

Auto-instrumentation handles propagation for the JDK / Apache HTTP client surface. The FCM Admin SDK case requires a manual injection wrapper provided by `:infra:otel`.

#### Scenario: Outbound JDK HTTP client carries `traceparent`
- **GIVEN** an active span context AND a Resend HTTP request initiated from `:backend:ktor` (or — pre-Resend-module — any other JDK-HTTP-client outbound)
- **WHEN** the outbound request is captured at the test boundary
- **THEN** the request headers include `traceparent` matching the active context's trace id and span id

#### Scenario: Supabase Realtime broadcast publish carries `traceparent`
- **GIVEN** an active span context inside the chat send handler AND a `ChatRealtimeClient.publish(...)` call wrapped by `:infra:otel`'s `withSpan("chat.realtime.publish", ...)` helper
- **WHEN** the Supabase Realtime publish HTTP request to `realtime.supabase.co` (or the configured Supabase endpoint) is captured at the test boundary
- **THEN** the request headers include `traceparent` matching the active context (this is the only currently-shipped manual-span site requiring verified outbound propagation; the `SupabaseBroadcastChatClient` HTTP transport uses the auto-instrumented JDK client — propagation is auto, but the assertion locks the contract)

#### Scenario: Supabase REST call carries `traceparent`
- **GIVEN** an active span context inside an authenticated request handler AND a Supabase REST request from `:backend:ktor`
- **WHEN** the outbound Supabase REST request is captured at the test boundary
- **THEN** the request headers include `traceparent`

#### Scenario: FCM Admin SDK send carries `traceparent`
- **GIVEN** an active span context AND a `FirebaseMessaging.send(message)` invocation wrapped by `:infra:otel`'s `withSpan` helper AND the Firebase Admin SDK configured with the `TracingHttpTransport` wrapper (per design § D8)
- **WHEN** the FCM Admin SDK's outbound HTTPS request to `fcm.googleapis.com` is captured at the test boundary (e.g., via a mock HTTP client transport)
- **THEN** the request headers include `traceparent` matching the active context (per design § D8 the integration is unconditional — if the firebase-admin pin doesn't expose the transport hook, that fails the tasks.md `1.x` baseline check, NOT this scenario)

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
