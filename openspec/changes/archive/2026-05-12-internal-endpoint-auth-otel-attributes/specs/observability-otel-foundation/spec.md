## MODIFIED Requirements

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
