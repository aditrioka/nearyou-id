## ADDED Requirements

### Requirement: `IpHasher` SHALL anonymize client IP for span-attribute and rate-limit-key surfaces

`:infra:otel` SHALL expose `IpHasher.hash(ip: String): String` that returns the first 16 hex characters of `SHA-256(ip.toByteArray(StandardCharsets.UTF_8))` (16 hex = 64-bit truncated digest). Every site that constructs an IP-axis Redis key for the `RateLimiter.tryAcquireByKey` overload SHALL go through this helper. Embedding the raw IPv4 dotted-quad or IPv6 colon-delimited literal in the Lua key (which surfaces in Tempo `db.statement` span attributes on the Lettuce `EVALSHA` span AND in the `key=` field of structured logs mandated by [`rate-limit-infrastructure/spec.md`](../rate-limit-infrastructure/spec.md)) is forbidden.

The truncation length and digest function are fixed (changing them is an explicit follow-up change requiring a separate proposal). The shape mirrors `UserIdHasher` ("first 16 hex chars of `SHA-256(...)`"), keeping the operator mental model unified across user/IP/token correlation IDs.

`IpHasher` accepts the literal string supplied by the caller (the `clientIp` request-context value from `ClientIpExtractor`). It does NOT normalize IPv6 forms (`::1` vs `0:0:0:0:0:0:0:1` hash differently) — Cloudflare's emission shape is deterministic per request-edge, so two semantically-equivalent forms reaching the helper from the same client is not a real-world concern. If a future IPv6 audit shows form-drift causing rate-limit-bypass, normalization can be added without breaking the shape contract.

#### Scenario: Hash is deterministic
- **GIVEN** an IP literal `I` (e.g., `"1.2.3.4"`)
- **WHEN** `IpHasher.hash(I)` is invoked twice
- **THEN** both calls return the identical 16-character hex string

#### Scenario: Hash differs between distinct IPs
- **GIVEN** two distinct IP literals `I1 != I2` (e.g., `"1.2.3.4"` and `"5.6.7.8"`)
- **WHEN** `IpHasher.hash(I1)` and `IpHasher.hash(I2)` are computed
- **THEN** the two return values differ

#### Scenario: Hash output is exactly 16 hex characters
- **GIVEN** any IP literal
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

## MODIFIED Requirements

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
