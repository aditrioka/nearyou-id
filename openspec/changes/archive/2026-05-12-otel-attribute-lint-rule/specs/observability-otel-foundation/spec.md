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

## ADDED Requirements

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
