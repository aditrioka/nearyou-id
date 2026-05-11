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

This requirement is enforced via three defense-in-depth layers:
1. **Runtime stripping** at the SDK export pipeline via `ForbiddenAttributeStripper.kt` for auto-instrumentation peer-identity attrs the developer didn't write.
2. **Compile-time lint** at developer-written call sites via `OtelForbiddenAttributeRule` (see § "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes" below).
3. **Integration-test sentinel-string regression** at staging for end-to-end coverage of the 7 high-velocity categories (chat content, post content, search query, IP, JWT, bearer token, Redis password) — see the per-category scenarios below.

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

## ADDED Requirements

### Requirement: `OtelForbiddenAttributeRule` fences forbidden span-attribute writes

The repo SHALL ship a custom Detekt rule `OtelForbiddenAttributeRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`. The rule MUST fire on any Kotlin string-literal (`KtStringTemplateExpression`) whose source text contains one of the forbidden-attribute-key tokens OR matches one of the sensitive-value regex patterns enumerated below, unless the containing file is allowlisted (§ "Allowlist for `OtelForbiddenAttributeRule`") or the enclosing declaration is annotated `@AllowForbiddenSpanAttribute("<reason>")` (§ "`@AllowForbiddenSpanAttribute` annotation bypasses the rule").

**Tier 1 — forbidden-attribute-key literals (anywhere)**:

The rule MUST fire on any string literal exactly equal to one of the following keys, which mirror the runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` enumeration in [`infra/otel/.../ForbiddenAttributeStripper.kt`](../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) (11 entries):
- `client.address`, `client.port`, `http.client_ip` (HTTP client-identity semconv keys; same name across old + new semconv)
- `network.peer.address`, `network.peer.port` (new OTel-Java-2.x peer semconv)
- `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr` (old OTel-Java-1.x peer semconv, kept for backward-compat across BOM bumps)
- `user_id`, `user_uuid`, `user.uuid` (defensive: typo-shaped user-id keys; the sanctioned key is `user.id` with `UserIdHasher.hash(...)` consumption, NOT one of these three variants)

These keys SHALL NEVER appear as Kotlin string literals outside `:infra:otel`'s own configuration / test-fixture sources and `:lint:detekt-rules`'s own rule + test sources (the only legitimate enumerations of the list as data). The Tier 1 list MUST be a superset of (and stay synchronized with) the runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` enumeration; the synchronization guard test (§ "Detekt test coverage for `OtelForbiddenAttributeRule`" item 11) enforces this.

**Tier 2 — sensitive-value regex patterns (anywhere)**:

The rule MUST fire on any string literal matching one of these high-confidence sensitive-value regex patterns:
- `\-{5}BEGIN [A-Z ]+PRIVATE KEY\-{5}` — RSA / EC / Ed25519 PEM private key marker.
- `eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.` — JWT shape (base64url header `eyJ...` + `.` + base64url payload `eyJ...` + trailing `.`).
- `redis://[^:]+:[^@/]+@` — Redis URI with explicit userinfo (password embedded).

#### Scenario: Tier 1 forbidden-attribute key literal fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"client.address"` (e.g., as `span.setAttribute("client.address", ...)`)
- **THEN** `OtelForbiddenAttributeRule` reports a code smell on that literal

#### Scenario: Tier 1 forbidden-attribute key literal in a `mapOf(...)` passed to `withSpan(...)` fires
- **WHEN** a non-allowlisted Kotlin file contains `withSpan("foo", mapOf("network.peer.address" to clientAddr)) { ... }`
- **THEN** the rule fires on the `"network.peer.address"` literal

#### Scenario: Tier 2 sensitive-value pattern fires on PEM marker
- **WHEN** a non-allowlisted Kotlin file contains a string literal containing `-----BEGIN RSA PRIVATE KEY-----`
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on JWT-shaped literal
- **WHEN** a non-allowlisted Kotlin file contains a string literal matching `"eyJhbGciOiJSUzI1NiI.eyJzdWIiOiJ4eHgi.signature"` (illustrative shape)
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on Redis URI with userinfo
- **WHEN** a non-allowlisted Kotlin file contains a string literal `"redis://default:my-redis-password@redis.example:6379/0"`
- **THEN** the rule fires

#### Scenario: Sanctioned `UserIdHasher.hash` consumption does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `span.setAttribute("user.id", UserIdHasher.hash(userId))`
- **THEN** the rule does NOT fire (the literal `"user.id"` is NOT in Tier 1 — Tier 1 catches typo variants `user_id` / `user_uuid` / `user.uuid`; `user.id` is the sanctioned key paired with the `UserIdHasher.hash(...)` helper consumption)

#### Scenario: Typo-variant `user_id` key literal fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"user_id"` (underscore variant) — a likely bypass attempt
- **THEN** the rule fires (the literal IS in Tier 1; defense-in-depth against typo-shaped bypasses of the canonical `"user.id"` writer)

#### Scenario: Unrelated string literal does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `val msg = "Processing request"`
- **THEN** the rule does NOT fire

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `OtelForbiddenAttributeRule`

### Requirement: Allowlist for `OtelForbiddenAttributeRule`

The rule SHALL NOT fire in any of these allowed contexts:

1. **`:infra:otel` module sources**: files whose `virtualFilePath` contains `/infra/otel/src/` (both `main/` and `test/`). This module enumerates forbidden keys as DATA (the `FORBIDDEN_KEYS` Set in `ForbiddenAttributeStripper.kt`; sentinel-string test fixtures).
2. **`:lint:detekt-rules` module sources**: files whose `virtualFilePath` contains `/lint/detekt-rules/src/` (both `main/` and `test/`). The rule itself + its test fixtures necessarily contain the forbidden patterns as DATA / TEST INPUTS.
3. **Annotation bypass**: enclosing declaration (function, class, property — or any ancestor declaration) annotated `@AllowForbiddenSpanAttribute(reason: String)` with a non-empty reason string. Empty-string-reason silent bypass (`@AllowForbiddenSpanAttribute("")`) is forbidden — the rule MUST fire if the reason is empty (mirroring `@AllowRawRedisKey` / `@AllowDailyTtlOverride` convention).

All three allowlist gates MUST support the detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror the approach in `BlockExclusionJoinRule.isAllowedPath` / `RawXForwardedForRule.isExtractorFile`).

#### Scenario: `:infra:otel` module source passes
- **WHEN** a file under `/infra/otel/src/main/kotlin/.../ForbiddenAttributeStripper.kt` contains a `Set` literal enumerating `"client.address"`, `"net.peer.ip"`, etc.
- **THEN** the rule does NOT fire on any of those literals

#### Scenario: `:lint:detekt-rules` source passes
- **WHEN** a file under `/lint/detekt-rules/src/main/kotlin/.../OtelForbiddenAttributeRule.kt` contains the regex constants enumerating the forbidden keys
- **THEN** the rule does NOT fire

#### Scenario: `@AllowForbiddenSpanAttribute` on function with non-empty reason suppresses
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("admin span exempt — see Decision N in design.md")` contains `span.setAttribute("client.address", ...)` (a Tier 1 forbidden key)
- **THEN** the rule does NOT fire on that literal

#### Scenario: `@AllowForbiddenSpanAttribute` on enclosing class suppresses
- **WHEN** a class annotated `@AllowForbiddenSpanAttribute("admin telemetry escape hatch")` contains a method with `span.setAttribute("net.peer.ip", ...)`
- **THEN** the rule does NOT fire

#### Scenario: `@AllowForbiddenSpanAttribute("")` (empty reason) still fires
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("")` contains `"client.address"`
- **THEN** the rule reports a code smell on that literal (empty-reason silent bypass is rejected)

#### Scenario: Synthetic-file-harness via package-FQN fallback
- **WHEN** the detekt-test `lint(String)` synthetic harness loads a fixture whose package FQN starts with `id.nearyou.lint.detekt` AND the fixture's content has no `virtualFilePath`
- **THEN** the rule treats the fixture as an allowlisted source (package-FQN-fallback precedent)

### Requirement: Detekt test coverage for `OtelForbiddenAttributeRule`

`lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt` SHALL cover, at minimum:

1. **Tier 1 positive-fail**: each of the 11 Tier 1 forbidden-attribute keys (the 8 network-semconv keys + the 3 user-id typo variants) triggers from a synthetic non-allowlisted file (one test per key).
2. **Tier 1 positive-pass**: `:infra:otel` source path allowlist suppresses every Tier 1 key.
3. **Tier 2 positive-fail**: each of the 3 Tier 2 regex patterns fires from a synthetic non-allowlisted file (PEM marker; JWT shape; Redis URI with userinfo).
4. **Sanctioned `UserIdHasher.hash` consumption positive-pass**: `span.setAttribute("user.id", UserIdHasher.hash(userId))` does NOT fire (the literal `"user.id"` is not in Tier 1).
5. **Allowlist by path**: `:infra:otel` source path → no fire; `:lint:detekt-rules` source path → no fire; arbitrary backend path → fires.
6. **Annotation bypass with non-empty reason**: `@AllowForbiddenSpanAttribute("reason")` on function suppresses; on enclosing class suppresses.
7. **Empty-reason annotation still fires**: `@AllowForbiddenSpanAttribute("")` does NOT suppress.
8. **Synthetic-file-harness package-FQN fallback**: package `id.nearyou.lint.detekt.*` is treated as allowlisted source.
9. **Composition with existing rules**: a fixture that contains both `"actual_location"` (triggering `CoordinateJitterRule`) and `"client.address"` (triggering `OtelForbiddenAttributeRule`) produces exactly 2 findings, one per rule (no double-counting, no cross-suppression).
10. **Unrelated string literals**: a non-allowlisted file containing `"Processing request"` / `"INSERT INTO posts ..."` does NOT fire.
11. **Synchronization guard**: a test that asserts the rule's Tier 1 list contains every entry in `ForbiddenAttributeStripper.FORBIDDEN_KEYS` as a regression guard against silent drift.

#### Scenario: Test class exists and passes
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every scenario above corresponds to at least one test case AND all cases pass

### Requirement: Detekt run against the backend codebase remains green after rule activation

As of this change, `./gradlew detekt` SHALL pass on the backend + infra + lint modules with `OtelForbiddenAttributeRule` registered and active. Every existing `Span.setAttribute(...)` / `withSpan(...)` / `tryAcquireByKey(...)` call site (sanctioned via `UserIdHasher.hash` / `IpHasher.hash` already) MUST pass the rule. If any pre-existing call site fires the rule, the implementation MUST either fix the call site (preferred — convert to canonical helper consumption) OR add the appropriate path allowlist / annotation. Zero call-site behavior changes expected today (writer surface is 1 `setAttribute` + 1 `tryAcquireByKey`, both already canonical).

#### Scenario: Detekt green post-merge
- **WHEN** running `./gradlew detekt` after this change merges
- **THEN** the command exits 0 with no `OtelForbiddenAttributeRule` findings
