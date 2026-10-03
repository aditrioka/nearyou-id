## ADDED Requirements

### Requirement: `OtelForbiddenAttributeRule` checks attribute-key positions

`OtelForbiddenAttributeRule` SHALL run a PSI-context-restricted Mode A check. This check fires only on a Kotlin string literal (`KtStringTemplateExpression`) that sits in an **attribute-key position** (AKP). The project runs the syntactic `detekt` task with no type resolution, so the check uses Kotlin PSI only.

**Attribute-key positions.** A key expression K is in an AKP when any of the following holds. Each shape also defines the **paired value** that checks 2, 5 and 6 inspect:

- **P1 — `setAttribute`.** K is the key argument of a call whose callee short name is `setAttribute`.
  - Key argument: the one named `key`; otherwise the unnamed argument at position 0.
  - Paired value: the argument named `value`; otherwise the unnamed argument at position 1.
- **P2 — `AttributeKey` factory.** K is the first argument of a call to `stringKey`, `booleanKey`, `longKey`, `doubleKey`, `stringArrayKey`, `booleanArrayKey`, `longArrayKey` or `doubleArrayKey`. The call is either unqualified, or its receiver text ends in `AttributeKey`.
  - Paired value: when the factory call is itself argument 0 of a `setAttribute(...)` / `put(...)` call, argument 1 of that call. When it is an even-indexed argument of `Attributes.of(...)`, the argument that follows it. Otherwise there is no paired value, and checks 2, 5 and 6 do not apply.
- **P3 — `AttributesBuilder.put`.** K is argument 0 of a two-argument `put(...)` call whose receiver is *builder-evidenced*. Paired value: argument 1.
  - Builder-evidenced means either: the receiver chain text contains `Attributes.builder()` or `.toBuilder()`; or the receiver is a simple name declared in the same file with type `AttributesBuilder`, or with an initializer containing `Attributes.builder()`.
  - NOT builder-evidenced (do not match): a generic `Map.put`, an unqualified `put` inside `buildJsonObject { }`, and Ktor's `call.attributes.put(...)`.
- **P4 — `withSpan` attribute map.** K is the key of a map entry — the left operand of infix `to`, or argument 0 of `Pair(...)` — inside a direct argument of a `mapOf` / `mutableMapOf` / `hashMapOf` / `linkedMapOf` call M. Paired value: the right operand of `to`, or argument 1 of `Pair`. In addition, one of these holds:
  - (a) M is the `attributes` argument of a `withSpan(...)` call — named `attributes =`, or the unnamed argument at position 1; or
  - (b) M initializes a property or variable, and a `withSpan(...)` call in the same file passes that variable's simple name as its `attributes` argument.

**Hoisted key literals.** A string literal that initializes a property (e.g. `const val K = "user_id"`) is checked at EVERY same-file reference to that property — a simple name, or the selector of a qualified expression (`Keys.K`) — that sits in an AKP; each reference contributes its own paired value, and the literal fires when the checks match at ANY such reference. A reference inside an `@AllowForbiddenSpanAttribute`-annotated declaration is sanctioned at that use site and skipped; annotating the constant itself sanctions every use.

**Tokenization.** Before token matching, a key is split into tokens on `.`, `_`, `-`, whitespace, acronym→word boundaries (`IDToken` → `ID Token`) and lower/digit→upper camelCase boundaries, then lowercased — e.g. `geo.userLat` → `[geo, user, lat]`, `JWTSecret` → `[jwt, secret]`.

**Peeling.** Before evidence matching, a value expression is peeled: parentheses, `!!`, the left operand of `?:`, a trailing no-argument `.toString()` / `.toJavaUuid()` / `.toKotlinUuid()`, and a single-entry string template (`"$x"` / `"${x}"`) are removed.

In an AKP the rule SHALL fire when any of these checks matches:

1. **Context-restricted forbidden key** — the key equals `user_id`, compared case-insensitively (`USER_ID` too), regardless of the value. This is the one `ForbiddenAttributeStripper.FORBIDDEN_KEYS` entry too common in non-OTel code to enforce anywhere.
2. **User-identity alias with a raw identifier.** The key's tokens include `user`, `enduser`, `principal`, `actor`, `subject`, `owner` or `account`, AND the peeled paired value is raw-identifier-evidenced:
   - (V1) a non-interpolated string literal matching `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`;
   - (V2) a UUID-producing factory call: `UUID.randomUUID()` / `UUID.fromString(…)` / `UUID.nameUUIDFromBytes(…)` (receiver `UUID` or `java.util.UUID`), `Uuid.random()` / `Uuid.parse(…)` / `Uuid.parseHex(…)` / `Uuid.fromLongs(…)` (receiver `Uuid` or `kotlin.uuid.Uuid`), or `UuidV7.next()`;
   - (V3) a simple name whose same-file parameter or property declaration (not a function) has type `UUID` / `java.util.UUID` / `Uuid` / `kotlin.uuid.Uuid` (nullable allowed), or an initializer satisfying V1, V2 or V4;
   - (V4) a name or property reference — not a call — whose terminal identifier is `userId` or ends in `UserId`, or `<x>.id` where `x`'s peeled terminal identifier is `user` or ends in `User`; a terminal identifier mentioning `hash` (case-insensitive — `hashedUserId`, `userIdHash`) is an already-hashed value and is NOT evidence.

   The following SHALL NOT fire: hashed values (`UserIdHasher.hash(…)`, `ServiceAccountIdHasher.hash(…)`), role or name strings, call selectors such as `getUserId()`, and UUIDs under non-alias keys (e.g. `conversation_id`).
3. **Location key.** A token is one of `location`, `locations`, `geolocation`, `lat`, `lats`, `latitude`, `latitudes`, `lng`, `lon`, `longitude`, `longitudes`, `latlng`, `latlon`, `latlong`, `coord`, `coords`, `coordinate`, `coordinates`, `geohash`, `geom`, `geometry`, `geography`, `wkt`.
   - Exception: the **exact key `display_location`** (tokens exactly `[display, location]`, so `display.location` and `displayLocation` too) is the one sanctioned location key and SHALL NOT fire.
   - Every other location key fires, including `display_lat` (which could carry a raw viewer coordinate) and `display_actual_location`.
4. **Credential key.** A token is one of `password`, `passwd`, `secret`, `secrets`, `bearer`, `authorization`, `apikey` or `cookie`; OR adjacent tokens form `refresh token`, `access token`, `id token`, `api key`, `private key` or `service role`.
5. **Raw JWT / OIDC claim value, any key.** The peeled paired value is a name or property reference whose terminal identifier is `sub`, or `subject` whose PEELED receiver's terminal identifier is `payload`, `decoded`, `claims` or `jwt` (`decoded!!.subject` included). A bare `.subject` (`email.subject`) is not evidence.
6. **Raw client IP value, any key.** The peeled paired value is a name or property reference whose terminal identifier is `clientIp` — the canonical request-context accessor (`call.clientIp`) read directly. `IpHasher.hash(call.clientIp)` is a call and does NOT fire.

The path allowlist and the `@AllowForbiddenSpanAttribute("<non-blank reason>")` bypass (§ "Allowlist for `OtelForbiddenAttributeRule`") SHALL apply to these checks unchanged (for a hoisted key literal, at each use site as described above). A literal SHALL be reported at most once, however many checks match it. The sanctioned `display_location` key is sanctioned by NAME only — that its value is the `display_location` column (not a raw or viewer coordinate) is defended by code review.

**Known limits** (documented in the rule KDoc). The runtime stripper, the sentinel scenarios and code review remain the backstop for each of these:
- Maps built in another file, through `buildMap { }`, or composed with `+`.
- An unqualified `put` inside `Attributes.builder().apply { }`.
- Key constants declared in another file.
- A standalone hoisted `AttributeKey` value: there is no paired value, so the alias, claim and client-IP checks are skipped. Key-name checks still apply.
- Dynamic key concatenation, and semconv constants (`ClientAttributes.CLIENT_ADDRESS`, `UrlAttributes.URL_QUERY`) that carry no literal.
- Identifiers embedded in a multi-entry string template (`"user:$userId"`) — only single-entry templates are peeled.
- Unseparated all-lowercase compound keys (`refreshtoken`, `userid`, `actuallocation`) — tokenization needs a separator or case boundary.
- User-referencing keys outside the alias token set (e.g. `author_id`).
- P3, P4(b), V3 and hoisted-key resolution match names across the whole file, not by lexical scope, so a same-named declaration can over-match.

#### Scenario: `setAttribute("user_id", …)` fires
- **WHEN** a non-allowlisted Kotlin file contains `span.setAttribute("user_id", hashed)`, `span.setAttribute("user_id", value = hashed)` OR `span.setAttribute("USER_ID", hashed)`
- **THEN** the rule reports a code smell on the `"user_id"` literal

#### Scenario: `user_id` as an `AttributeKey` / `AttributesBuilder.put` key fires
- **WHEN** a non-allowlisted file contains `AttributeKey.stringKey("user_id")`, an unqualified `stringKey("user_id")`, `Attributes.builder().put("user_id", v)`, `base.toBuilder().put("user_id", v)`, OR `val b = Attributes.builder(); b.put("user_id", v)`
- **THEN** the rule fires on each `"user_id"` literal

#### Scenario: `user_id` in a `withSpan` attribute map fires, directly or via a same-file `val`
- **WHEN** a non-allowlisted file contains `withSpan("op", mapOf("user_id" to v)) { }`, OR `withSpan(name = "op", attributes = mapOf("user_id" to v)) { }`, OR `withSpan("op", mutableMapOf(Pair("user_id", v))) { }`, OR `val attrs = mapOf("user_id" to v)` followed by `withSpan("op", attrs) { }`
- **THEN** the rule fires on the `"user_id"` literal in each case

#### Scenario: A hoisted key constant used as an attribute key fires
- **WHEN** a non-allowlisted file contains `const val K = "user_id"` AND `span.setAttribute(K, v)`, OR `object Keys { const val K = "user_id" }` AND `span.setAttribute(Keys.K, v)`
- **THEN** the rule fires on the `"user_id"` literal AND does NOT fire when the same constant is only concatenated into SQL text

#### Scenario: Every use of a hoisted key is checked; an annotated use site is sanctioned
- **WHEN** a non-allowlisted file contains `const val K = "principal"` with `span.setAttribute(K, "system")` in one function AND `span.setAttribute(K, id.toString())` (`id: UUID`) in another, OR `const val K = "user_id"` used by `setAttribute(K, v)` in an `@AllowForbiddenSpanAttribute("…")`-annotated function AND in an unannotated one
- **THEN** the rule fires once on the `"principal"` literal AND once on the `"user_id"` literal, AND does NOT fire on `"user_id"` when its ONLY use is inside the annotated function

#### Scenario: A raw client IP value fires under any key
- **WHEN** a non-allowlisted file contains `span.setAttribute("net.client", call.clientIp)`
- **THEN** the rule fires AND does NOT fire on `span.setAttribute("client.hash", IpHasher.hash(call.clientIp))`

#### Scenario: `user_id` outside an attribute-key position does NOT fire
- **WHEN** a non-allowlisted file contains any of:
  - `rs.getObject("user_id", UUID::class.java)`
  - `@SerialName("user_id") val userId: String`
  - `call.parameters["user_id"]`
  - `buildJsonObject { put("user_id", JsonPrimitive(id)) }`
  - `mutableMapOf<String, Any>().put("user_id", id)`
  - `mapOf("user_id" to id)` not passed to `withSpan`
- **THEN** the rule does NOT fire

#### Scenario: Alias key with a role string does NOT fire
- **WHEN** a non-allowlisted file contains `span.setAttribute("principal", "system")` OR `span.setAttribute("actor", actorUsername)` where `actorUsername: String`
- **THEN** the rule does NOT fire

#### Scenario: Alias key with a raw identifier fires
- **WHEN** a non-allowlisted file contains any of:
  - `span.setAttribute("owner", "550e8400-e29b-41d4-a716-446655440000")`
  - `fun f(id: UUID) { span.setAttribute("subject", id.toString()) }`
  - `span.setAttribute("user.id", user.id.toString())`
  - `span.setAttribute("principal", call.principal.targetUserId.toString())`
  - `val viewer = principal.userId` followed by `span.setAttribute("principal", viewer.toString())`
- **THEN** the rule fires on the key literal in each case

#### Scenario: A raw JWT / OIDC claim value fires under any key
- **WHEN** a non-allowlisted file contains `span.setAttribute("service.account.id", claims.sub)` OR `withSpan("op", mapOf("actor" to claims.sub)) { }` OR `span.setAttribute("auth.identity", credential.payload.subject)`
- **THEN** the rule fires on the key literal AND does NOT fire on `span.setAttribute("email.subject", mail.subject)`

#### Scenario: Sanctioned hashed identity and non-alias UUID keys do NOT fire
- **WHEN** a non-allowlisted file contains any of:
  - `span.setAttribute("user.id", UserIdHasher.hash(user.id))`
  - `span.setAttribute("service.account.id", ServiceAccountIdHasher.hash(claims.sub))`
  - `withSpan("chat.realtime.publish", mapOf("conversation_id" to conversationId.toString())) { }`
- **THEN** the rule does NOT fire

#### Scenario: Location key fires
- **WHEN** a non-allowlisted file contains any of:
  - `span.setAttribute("geo.lat", lat)`
  - `span.setAttribute("actual_location", wkt)`
  - `span.setAttribute("display_lat", lat)`
  - `span.setAttribute("display_actual_location", v)`
  - `withSpan("op", mapOf("userCoords" to c)) { }`
- **THEN** the rule fires on each key literal

#### Scenario: Only the exact `display_location` key is sanctioned
- **WHEN** a non-allowlisted file contains `span.setAttribute("display_location", fuzzed)` OR `span.setAttribute("displayLocation", fuzzed)`
- **THEN** the rule does NOT fire

#### Scenario: Look-alike keys and non-key location words do NOT fire
- **WHEN** a non-allowlisted file contains `span.setAttribute("latency_ms", ms)`, `span.setAttribute("cloud.platform", p)`, `span.setAttribute("memory.allocation", a)` or `span.setAttribute("coordinator", c)`, OR the non-AKP literals `"SELECT ST_Y(display_location::geometry) AS lat"`, `"latitude"`, `"actual_lat"`
- **THEN** the rule does NOT fire

#### Scenario: Credential key fires
- **WHEN** a non-allowlisted file contains `span.setAttribute(k, v)` with `k` one of `"client_secret"`, `"http.request.header.authorization"`, `"http.request.header.cookie"`, `"refreshToken"`, `"id_token"`, `"IDToken"`, `"JWTSecret"`, `"private_key"`, `"db.password"`
- **THEN** the rule fires on each key literal AND does NOT fire for `"gen_ai.usage.input_tokens"` or `"credential.type"`

#### Scenario: Location key composes with `CoordinateJitterRule`
- **WHEN** a non-allowlisted file contains `span.setAttribute("actual_location", v)`
- **THEN** `OtelForbiddenAttributeRule` reports exactly 1 finding AND `CoordinateJitterRule` reports exactly 1 finding (independent, no cross-suppression)

#### Scenario: Annotation bypass and path allowlist apply to attribute-key checks
- **WHEN** a function annotated `@AllowForbiddenSpanAttribute("legacy admin trace")` contains `span.setAttribute("user_id", v)`, OR a file under `/src/test/`, `/infra/otel/src/main/` or `/lint/detekt-rules/src/main/` contains the same call
- **THEN** the rule does NOT fire

## MODIFIED Requirements

### Requirement: Forbidden span attributes — defense-in-depth for PII

NO span produced by `:backend:ktor` SHALL ever carry these attribute keys or values:
- **Raw `user_id` UUID** — in any attribute, including custom names like `user_uuid`, `principal`, `actor`, etc. Use `UserIdHasher.hash(...)` instead.
  - The keys `user_id`, `user_uuid` and `user.uuid` are forbidden outright.
  - A key whose tokens include `user`, `enduser`, `principal`, `actor`, `subject`, `owner` or `account` MUST NOT carry a raw identifier (a raw UUID or a raw JWT/OIDC `sub`).
  - Other user-referencing keys (e.g. `author_id`, `sender_id`) carrying a raw UUID are equally forbidden. These are defended by code review only, plus the layer-3 JWT-claim sentinel where it applies — layer 1 strips only the `FORBIDDEN_KEYS` key names, never values. The compile-time lint value-checks the enumerated alias token set (see § "`OtelForbiddenAttributeRule` checks attribute-key positions").
- **Raw client IP** read from `CF-Connecting-IP` or `X-Forwarded-For`.
  - The sanctioned anonymization shape is `IpHasher.hash(ip)` (16-hex truncated SHA-256, exported from `:infra:otel`). IP-axis rate-limit Redis keys use it per [`rate-limit-infrastructure/spec.md`](/openspec/specs/rate-limit-infrastructure/spec.md).
  - Embedding the raw IP directly in any span attribute, log field or Redis key segment is forbidden. The hashed form is the only sanctioned way for IP-derived values to surface in telemetry.
  - The compile-time lint fires on a raw `clientIp` value (the canonical request-context accessor, read directly) under ANY attribute key (§ "`OtelForbiddenAttributeRule` checks attribute-key positions" check 6). Raw header reads are already forbidden by `RawXForwardedForRule`; an IP copied into a differently-named local is defended by code review.
- **OTel HTTP server / network semconv peer-identity attributes** — both name sets:
  - OLD-semconv names: `client.address`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `http.client_ip`.
  - NEW-semconv names: `client.address` (unchanged), `network.peer.address`, `network.peer.port`.
  
  The OTel Java 2.x instrumentation migrated to the new HTTP semconv (verified at staging soak: `opentelemetry-ktor-3.0:2.25.0-alpha` emits `network.peer.address`). Both name sets MUST be stripped to handle BOM upgrades and instrumentation alternates. Behind Cloudflare these attributes would carry the Cloudflare-edge peer IP (NOT the real client IP, which comes from `CF-Connecting-IP`). On the Cloud Run direct URL they carry the internal load-balancer link-local IP. Neither shape is acceptable per project posture. The implementation MUST suppress these keys via the SDK pipeline. The canonical mechanism is a `SpanExporter` decorator that strips forbidden keys before delegate export — see [`infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt`](/infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt).
- **Raw secrets** — raw JWT tokens, raw refresh tokens, raw API bearer tokens, raw OAuth client secrets, JWKS contents, raw Supabase service role key.
  - **Values with a stable marker** are compile-time-enforced by `OtelForbiddenAttributeRule` Tier 2 (§ "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes"): JWT shape, PEM private key, JWKS RSA, credentialed `redis://` / `rediss://` / `postgres(ql)://` connection URIs, Google OAuth `GOCSPX-` client secrets / `ya29.` (incl. metadata-server `ya29.c.`) access tokens / `1//` refresh tokens, Supabase `sb_secret_` keys, raw Grafana Cloud `glc_` tokens, OpenAI keys (`sk-proj-` / `sk-svcacct-` / `sk-admin-`, or the `T3BlbkFJ` marker), RevenueCat / Stripe-style `sk_` keys.
  - **Prefix-less opaque secrets** have no value shape any regex can recognise: project-issued refresh tokens (`SecureRandom` base64url), Supabase GoTrue refresh tokens, operator-generated webhook / HMAC / AES secrets, Cloudflare API tokens, Resend API keys (`re_…` — rejected as a value pattern because the prefix collides with `re_threshold`-style identifiers), and the stored OTLP exporter credential (`otel-grafana-otlp-token` is a base64 Basic-auth wrapping, so its `glc_` prefix is not visible). Their values are defended by code review only, plus the layer-3 bearer-token sentinel for the `Authorization` header — layer 1 never inspects values. The attribute KEYS that would carry them (`refresh_token`, `client_secret`, `authorization`, …) are compile-time-enforced by the credential-key check.
- **Raw JWT claims** (`sub`, `aud`, `iss`, custom claims). The truncated SHA-256 token correlation id pattern from [`internal-endpoint-auth/spec.md:18`](/openspec/specs/internal-endpoint-auth/spec.md) is the only sanctioned anonymization shape for token-related identifiers. The compile-time lint forbids the `jwt.sub` / `jwt.aud` / `jwt.iss` keys anywhere, AND a raw claim value (`.sub`, or `.subject` read off a token `payload` / `decoded` / `claims` / `jwt`) under ANY attribute key.
- **Raw `actual_location` GIS coordinates** from `posts`.
  - Forbid any attribute key whose tokens include a location token (`location`, `lat`, `latitude`, `lng`, `lon`, `longitude`, `coord`, `coordinate`, and their plural / compound forms enumerated in § "`OtelForbiddenAttributeRule` checks attribute-key positions") unless explicitly sanctioned.
  - **The one explicit sanction is the exact key `display_location`**, carrying the post's `display_location` column value: the HMAC-fuzzed coordinate that every non-admin post read path already returns to API clients. Derived or look-alike keys (`display_lat`, `display_lng`, `display_actual_location`) are NOT sanctioned — they could carry an unfuzzed viewer coordinate. The spatial-fuzzing invariant (non-admin paths use `display_location`, never `actual_location`) is unchanged.
  - The change that adds the first `display_location` span-attribute writer MUST also amend the Grafana Cloud processor disclosure in [`docs/06-Security-Privacy.md`](/docs/06-Security-Privacy.md) § Consent Flow (today: "hashed user IDs, parameterized SQL, route patterns") to name fuzzed post location, in the same PR. No writer exists today.
  - (Amended by `otel-attribute-rule-spec-parity`; previously even `display_location`-derived numbers were unsanctioned.)
- **Raw user-private content** — raw post `content`, raw chat message `content`, raw search query strings (e.g. the `q` query parameter on the search endpoint). The span attribute surface is observable to operators with read access, and these surfaces are user-private content. Their values have no recognizable shape; they are defended by layer 3 plus code review.
- **Plaintext password fields** — defensive: none are accepted by the current API, but the rule preempts a future regression. Attribute keys with a `password` / `passwd` / `secret` token are compile-time-enforced by the credential-key check.
- **Raw Redis cluster credentials.**
  - The Lettuce auto-instrumentation's `db.connection_string` attribute MUST be sanitized to strip the `userinfo` portion of the Redis URI (the password) — see the Mandatory span attributes requirement above.
  - Connection URIs with embedded credentials (`redis://`, `rediss://`, `postgres(ql)://`; with or without a username) are compile-time-enforced by Tier 2.

This requirement is enforced via three defense-in-depth layers:
1. **Runtime stripping** at the SDK export pipeline via `ForbiddenAttributeStripper.kt`. This covers auto-instrumentation peer-identity attrs the developer didn't write.
2. **Compile-time lint** at developer-written call sites via `OtelForbiddenAttributeRule`:
   - anywhere-firing Tier 1 keys, Tier 2 value patterns and IP-axis shapes (§ "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes");
   - attribute-key-position checks for `user_id`, user-identity aliases, location keys, credential keys, raw JWT-claim values and raw client-IP values (§ "`OtelForbiddenAttributeRule` checks attribute-key positions").
3. **Integration-test sentinel-string regression** at staging, for end-to-end coverage of the high-velocity categories (post content, chat content, peer-IP, raw-IP-in-Lua-key, bearer token, JWT claim, search query, Redis password) — see the per-category scenarios below.

#### Scenario: Setting raw user_id on a span is a code-review blocker
- **GIVEN** a hypothetical PR adds `Span.setAttribute("user.id", userId.toString())` (raw UUID)
- **WHEN** a reviewer applies this requirement
- **THEN** the change is rejected; the PR must use `UserIdHasher.hash(userId)` instead (the compile-time lint also fires on this shape — § "`OtelForbiddenAttributeRule` checks attribute-key positions")

#### Scenario: `display_location` is the sanctioned location attribute key
- **GIVEN** a hypothetical PR adds `Span.setAttribute("display_location", fuzzedWkt)` where the value comes from the `display_location` column
- **WHEN** a reviewer applies this requirement
- **THEN** the change is acceptable only if the same PR amends the Grafana Cloud processor disclosure in `docs/06-Security-Privacy.md` § Consent Flow AND an equivalent write under `display_lat` / `actual_location` / `geo.lat` / any other location-token key is rejected

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

### Requirement: `OtelForbiddenAttributeRule` fences forbidden span-attribute writes

The repo SHALL ship a custom Detekt rule `OtelForbiddenAttributeRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`.

The rule MUST fire on any Kotlin string literal (`KtStringTemplateExpression`) whose source text exactly equals one of the anywhere-forbidden attribute-key tokens below, OR matches one of the sensitive-value regex patterns below. It MUST NOT fire when either of these holds:
- the containing file is allowlisted (§ "Allowlist for `OtelForbiddenAttributeRule`");
- the enclosing declaration is annotated `@AllowForbiddenSpanAttribute("<reason>")` (§ "`@AllowForbiddenSpanAttribute` annotation bypasses the rule").

The same rule additionally runs the PSI-context-restricted attribute-key-position checks specified in § "`OtelForbiddenAttributeRule` checks attribute-key positions".

**Tier 1 — forbidden-attribute-key literals (anywhere)**:

The rule MUST fire on any string literal exactly equal to one of the following 21 keys. Together with the attribute-key-position check on `"user_id"`, these keys form a SUPERSET of the runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` enumeration in [`infra/otel/.../ForbiddenAttributeStripper.kt`](/infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt), with zero carve-outs.

**Group A — `ForbiddenAttributeStripper.FORBIDDEN_KEYS` entries enforced anywhere (10)**:
- HTTP client-identity semconv: `client.address`, `client.port`, `http.client_ip`
- New OTel-Java-2.x peer semconv: `network.peer.address`, `network.peer.port`
- Old OTel-Java-1.x peer semconv: `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`
- User-id typo-defensive variants: `user_uuid`, `user.uuid` (the sanctioned key is `user.id` with `UserIdHasher.hash(...)` consumption)

The remaining `FORBIDDEN_KEYS` entry, `"user_id"`, is enforced in **attribute-key position only** (§ "`OtelForbiddenAttributeRule` checks attribute-key positions" check 1), not anywhere.
- **Why:** it appears in ~30 production literals as a SQL column name (`rs.getObject("user_id", UUID::class.java)`), `@SerialName` JSON key, Ktor route parameter (`call.parameters["user_id"]`) and `buildJsonObject { put("user_id", …) }` key. All of these are semantically unrelated to OTel attribute writes.
- **Effect:** the PSI-context restriction lets the rule fire on `setAttribute("user_id", …)` / `withSpan(…, mapOf("user_id" to …))` without a single false positive on those uses.
- **What remains:** the runtime stripper continues to strip emitted `"user_id"` attributes defensively at export.

**Group B — symmetric typo-defensive underscore variants for HTTP / network semconv keys (8)**:
- `client_address`, `client_port`, `http_client_ip` (underscore variants of Group A's HTTP client-identity keys)
- `network_peer_address`, `network_peer_port` (underscore variants of the new peer semconv)
- `net_peer_ip`, `net_peer_port`, `net_sock_peer_addr` (underscore variants of the old peer semconv)

**Group C — JWT-claim attribute keys (3)**:
- `jwt.sub`, `jwt.aud`, `jwt.iss` (per canonical spec § "Forbidden span attributes" — raw JWT claims forbidden on any span)

These keys SHALL NEVER appear as Kotlin string literals outside the path allowlist (next requirement).

The rule's key sets MUST satisfy `Group A ∪ {"user_id"} ⊇ FORBIDDEN_KEYS`. The synchronization-guard test (§ "Detekt test coverage" item 11) asserts this. On failure, its message names any missing key AND both enforcement modes.

**Tier 2 — sensitive-value regex patterns (anywhere)**:

The rule MUST fire on any string literal matching one of these high-confidence sensitive-value regex patterns. Each opaque-secret pattern is anchored on a vendor prefix plus a long token-alphabet body, so prose and identifiers do not match:
- `-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}` — RSA / EC / Ed25519 / PKCS#8 PEM private key marker (including the label-less `BEGIN PRIVATE KEY` used by GCP service-account JSON). Never legitimate in source code outside test fixtures.
- `eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.` — JWT shape (base64url header `eyJ...` + `.` + base64url payload `eyJ...` + trailing `.`). Also covers the legacy JWT-format Supabase service role key.
- `(?:rediss?|postgres(?:ql)?)://[^:/@\s]*:[^@/\s]+@` — connection URI with embedded credentials: `redis://`, TLS `rediss://` (Upstash) and `postgres(ql)://` (the admin DB connection-string slot), with or without a username (`redis://:pw@host`). `jdbc:postgresql://host:5432/db` (no userinfo) does not match.
- `"kty"\s*:\s*"RSA"\s*,?\s*"n"\s*:` — JWKS RSA-key JSON shape (presence of `"kty": "RSA"` followed by `"n":` modulus). Specific enough to avoid false-positives on legitimate JSON-with-`kty` mentions.
- `GOCSPX-[A-Za-z0-9_\-]{20,}` — Google OAuth client secret.
- `ya29\.[A-Za-z0-9_.\-]{20,}` — Google OAuth / metadata-server access token (the body admits `.`, so the metadata-server `ya29.c.` form matches).
- `(?<![A-Za-z0-9/])1//[A-Za-z0-9_\-]{20,}` — Google OAuth refresh token.
- `sb_secret_[A-Za-z0-9_\-]{20,}` — Supabase secret API key.
- `glc_[A-Za-z0-9+/=_\-]{20,}` — Grafana Cloud access-policy token, raw form (the stored OTLP credential is base64-wrapped and therefore prefix-less).
- `sk-(?:(?:proj|svcacct|admin)-[A-Za-z0-9_\-]{20,}|[A-Za-z0-9_\-]{8,}T3BlbkFJ[A-Za-z0-9_\-]{8,})` — OpenAI API key (typed prefix, or the `T3BlbkFJ` marker that legacy and project keys embed).
- `(?<![A-Za-z0-9])sk_(?:live_|test_)?[A-Za-z0-9]{24,}` — RevenueCat / Stripe-style secret API key.

Prefix-less opaque secrets cannot be matched by any value regex. These are project-issued refresh tokens, Supabase GoTrue refresh tokens, operator-generated webhook / HMAC / AES secrets, Cloudflare API tokens, Resend API keys (`re_…`, prefix too collision-prone), and the base64-wrapped stored OTLP credential. They are covered as specified in § "Forbidden span attributes".

#### Scenario: Group A Tier 1 key literal fires (exact mirror of FORBIDDEN_KEYS)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"client.address"` (e.g., as `span.setAttribute("client.address", ...)`)
- **THEN** `OtelForbiddenAttributeRule` reports a code smell on that literal

#### Scenario: Group B Tier 1 key literal fires (typo-defensive underscore variant)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"client_address"` (underscore variant) — a likely typo-bypass attempt of `client.address`
- **THEN** the rule fires (the literal IS in Tier 1 Group B)

#### Scenario: Group C Tier 1 key literal fires (JWT-claim attribute)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"jwt.sub"` (e.g., as a setAttribute key)
- **THEN** the rule fires (raw JWT claims on spans are forbidden per canonical spec § "Forbidden span attributes")

#### Scenario: User-id typo variant `user_uuid` literal fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"user_uuid"` (Group A typo-defensive variant)
- **THEN** the rule fires

#### Scenario: `user_id` literal outside attribute-key position does NOT fire; inside it does
- **WHEN** a non-allowlisted Kotlin file contains `rs.getObject("user_id", UUID::class.java)` or `call.parameters["user_id"]` AND, separately, `span.setAttribute("user_id", v)`
- **THEN** the rule does NOT fire on the first two literals AND fires on the `setAttribute` key literal (Group A excludes `"user_id"`; the attribute-key-position check enforces it)

#### Scenario: Tier 1 forbidden-attribute key literal in a `mapOf(...)` passed to `withSpan(...)` fires
- **WHEN** a non-allowlisted Kotlin file contains `withSpan("foo", mapOf("network.peer.address" to clientAddr)) { ... }`
- **THEN** the rule fires on the `"network.peer.address"` literal (`withSpan`'s `mapOf` keys are checked as string literals)

#### Scenario: Tier 2 sensitive-value pattern fires on PEM marker
- **WHEN** a non-allowlisted Kotlin file contains a string literal containing `-----BEGIN RSA PRIVATE KEY-----` OR the label-less `-----BEGIN PRIVATE KEY-----`
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on JWT-shaped literal
- **WHEN** a non-allowlisted Kotlin file contains a string literal `"eyJhbGciOiJSUzI1NiI.eyJzdWIiOiJ4eHgi.signature"` (illustrative three-segment JWT shape)
- **THEN** the rule fires

#### Scenario: Tier 2 sensitive-value pattern fires on Redis URI with credentials
- **WHEN** a non-allowlisted Kotlin file contains a connection-URI string literal carrying userinfo credentials in any of these shapes — `redis://` with user AND password, TLS `rediss://` with user AND password (the Upstash shape), the user-less `redis://:` password form, or `postgresql://` with user AND password
- **THEN** the rule fires on each

#### Scenario: Tier 2 sensitive-value pattern fires on JWKS RSA shape
- **WHEN** a non-allowlisted Kotlin file contains a string literal containing `{"kty":"RSA","n":"...","e":"AQAB"}` (JWKS RSA-key JSON shape)
- **THEN** the rule fires

#### Scenario: Tier 2 fires on prefix-anchored opaque secrets
- **WHEN** a non-allowlisted Kotlin file contains a string literal that is (illustrative, synthetic values):
  - a Google OAuth client secret `GOCSPX-` + 28 token chars;
  - a Google access token `ya29.` + 40 token chars;
  - a Google metadata-server access token `ya29.c.` + 40 token chars;
  - a Google refresh token `1//0g` + 40 token chars;
  - a Supabase secret key `sb_secret_` + 32 token chars;
  - a Grafana Cloud token `glc_` + 40 base64 chars;
  - an OpenAI key `sk-proj-` + 40 token chars; or
  - a RevenueCat secret key `sk_` + 32 alphanumerics
- **THEN** the rule fires on each literal

#### Scenario: Tier 2 near-miss literals do NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `"GOCSPX-"` alone, `"ya29.short"`, `"https://host/1//"`, a `/`-preceded `1//` + full-length body, `"sb_secret_"` alone, `"risk_assessment_threshold_value_x"`, an alnum-preceded `disk_` + 32 alphanumerics, `"re_threshold"`, `"sk-learn"`, `"redis://host:6379/0"`, `"rediss://host:6380/0"`, or `"jdbc:postgresql://db.internal:5432/nearyou"`
- **THEN** the rule does NOT fire

#### Scenario: Sanctioned `UserIdHasher.hash` consumption does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `span.setAttribute("user.id", UserIdHasher.hash(userId))`
- **THEN** the rule does NOT fire (the literal `"user.id"` is NOT in Tier 1 — Tier 1 Group A catches typo variants `user_uuid` / `user.uuid`; `user.id` is the sanctioned key, and its hashed value is not raw-identifier-evidenced)

#### Scenario: Unrelated string literal does NOT fire
- **WHEN** a non-allowlisted Kotlin file contains `val msg = "Processing request"` or `"INSERT INTO posts (...) VALUES (...)"`
- **THEN** the rule does NOT fire

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `OtelForbiddenAttributeRule`

### Requirement: Detekt test coverage for `OtelForbiddenAttributeRule`

`lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt` SHALL cover, at minimum:

1. **Tier 1 Group A positive-fail**: each of the 10 Group A keys triggers from a synthetic non-allowlisted file, one test per key: `client.address`, `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `user_uuid`, `user.uuid`. PLUS a `"user_id"` pair:
   - a bare `"user_id"` / `rs.getObject("user_id", UUID::class.java)` literal does NOT fire;
   - `span.setAttribute("user_id", v)` DOES fire.
2. **Tier 1 Group B positive-fail**: each of the 8 underscore-variant typo-defensive keys triggers (e.g., `"client_address"`, `"network_peer_address"`).
3. **Tier 1 Group C positive-fail**: each of the 3 JWT-claim keys triggers (`"jwt.sub"`, `"jwt.aud"`, `"jwt.iss"`).
4. **Tier 1 positive-pass**: each of these path allowlists suppresses every Tier 1 key:
   - `:infra:otel/src/main/`;
   - `:lint:detekt-rules/src/main/`;
   - `/src/test/` (suppresses across the board).
5. **Tier 2 positive-fail**: each of the 11 Tier 2 regex patterns fires from a synthetic non-allowlisted file:
   - PEM marker (labelled AND label-less);
   - JWT three-segment shape;
   - connection URI with credentials (`redis://` user + password, TLS `rediss://` user + password, user-less `redis://:` password form, `postgresql://` user + password);
   - JWKS RSA shape;
   - Google `GOCSPX-` / `ya29.` (plain AND metadata-server `ya29.c.`) / `1//`;
   - Supabase `sb_secret_`;
   - Grafana `glc_`;
   - OpenAI `sk-proj-` AND the `T3BlbkFJ`-marker form;
   - RevenueCat `sk_`.

   All secret fixtures are synthetic. They are assembled at test runtime and interpolated into the fixture source as ONE complete literal, so the committed test source never contains a scanner-matching token. A concatenation inside the fixture would be two short literals and would never fire.
6. **Tier 2 false-positive negative tests**: legitimate strings that look near a Tier 2 pattern but should NOT fire:
   - `"eyJfoo"` alone (single segment, not JWT);
   - `"redis://host:6379/0"` / `"rediss://host:6380/0"` (no userinfo);
   - `"-----BEGIN PUBLIC KEY-----"` (PUBLIC, not PRIVATE);
   - bare / short vendor prefixes (`"GOCSPX-"`, `"ya29.short"`, `"sb_secret_"`, `"sk_short"`), and an alnum-preceded `disk_` + full-length body (exercises the `sk_` lookbehind);
   - `"jdbc:postgresql://db.internal:5432/nearyou"` (no userinfo);
   - a `/`-preceded `1//` followed by a full-length token body (exercises the lookbehind);
   - `"risk_assessment_threshold_value_x"`, `"re_threshold"`, `"sk-learn"`.
7. **Sanctioned `UserIdHasher.hash` consumption positive-pass**: `span.setAttribute("user.id", UserIdHasher.hash(userId))` does NOT fire.
8. **Allowlist by path**: no fire on Tier 1 / Tier 2 / attribute-key-position literals under `:infra:otel/src/main/`, `:lint:detekt-rules/src/main/` or `/src/test/`. An arbitrary `:backend:ktor/src/main/` path fires.
9. **Annotation bypass with non-empty reason**: `@AllowForbiddenSpanAttribute("reason")` suppresses when placed on the function, and when placed on the enclosing class. This includes an attribute-key-position finding (`setAttribute("user_id", v)`).
10. **Empty-reason / whitespace-only-reason annotation still fires**: none of these suppress — `@AllowForbiddenSpanAttribute("")`, `@AllowForbiddenSpanAttribute("   ")`, `@AllowForbiddenSpanAttribute("\t")`.
11. **Synchronization guard test**: asserts `TIER_1_GROUP_A ∪ CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS` with ZERO carve-outs. This is a regression guard against silent drift. The failure message MUST name any missing key(s) AND both enforcement modes (anywhere Group A vs attribute-key position), so the implementer adding to `FORBIDDEN_KEYS` knows to place the key in one of them.
12. **Synthetic-file-harness package-FQN fallback**: package `id.nearyou.lint.detekt.*` is treated as allowlisted source.
13. **Composition with existing rules** (no double-counting, no cross-suppression):
    - A fixture containing both `"actual_location"` (triggering `CoordinateJitterRule`) and `"client.address"` (triggering `OtelForbiddenAttributeRule`) produces exactly 2 findings, one per rule.
    - A fixture with `"rate:health:{ip:1.2.3.4}"` triggers both `RedisHashTagRule` (legacy prefix) and `OtelForbiddenAttributeRule` IP-axis mode independently — 2 findings.
    - A fixture with `span.setAttribute("actual_location", v)` produces exactly 1 `OtelForbiddenAttributeRule` finding AND 1 `CoordinateJitterRule` finding.
    - A fixture with `withSpan("foo", mapOf("network.peer.address" to clientAddr)) { }` (Tier 1 key AND attribute key) produces exactly 1 `OtelForbiddenAttributeRule` finding.
14. **Unrelated string literals**: a non-allowlisted file containing `"Processing request"` / `"INSERT INTO posts ..."` / `"SELECT * FROM users WHERE id = ?"` does NOT fire.
15. **NearYouRuleSetProvider registration**: explicit fixture asserting the rule appears in the returned `RuleSet`.
16. **Attribute-key-position shapes**: `"user_id"` fires as:
    - (P1) a `setAttribute` key — positional, named `key =`, and positional key with named `value =`; also the uppercase `USER_ID`;
    - (P2) an `AttributeKey.stringKey(...)` argument and an unqualified `stringKey(...)` argument;
    - (P3) a key on `Attributes.builder().put(...)`, on `.toBuilder().put(...)`, and on a same-file `AttributesBuilder`-typed / `Attributes.builder()`-initialized variable;
    - (P4) a `withSpan` map key — `mapOf` positional, named `attributes =`, `Pair(...)` form, `mutableMapOf` / `hashMapOf` / `linkedMapOf`, and the same-file hoisted-`val` form;
    - a hoisted same-file key constant used as a `setAttribute` key (also via a qualified `Keys.K` selector) and as an `AttributeKey.stringKey(...)` argument.
17. **Non-AKP regression fixtures** (one test per real production shape) do NOT fire:
    - `rs.getObject("user_id", UUID::class.java)` (item 1's anchor test);
    - `@SerialName("user_id")`;
    - `call.parameters["user_id"]`;
    - `buildJsonObject { put("user_id", JsonPrimitive(...)) }`;
    - `mutableMapOf<String, Any>().put("user_id", id)`;
    - Ktor `call.attributes.put("user_id", id)` and an unqualified `attributes.put("user_id", id)` (receiver not builder-evidenced);
    - a `mapOf("user_id" to id)` passed to a non-`withSpan` call, and one hoisted into a `val` that `withSpan` never receives;
    - a P2 factory on a non-`AttributeKey` receiver (`prefs.stringKey("user_id")`);
    - a key constant used only in SQL concatenation;
    - the production `AttributesBuilder.put` writers' safe keys (`error_code`, `event`, `error.type`).
18. **User-identity alias value-awareness**:
    - Fires on:
      - V1 UUID literal (also triple-quoted);
      - V2 `UUID.randomUUID()` / `fromString` / `java.util.UUID.nameUUIDFromBytes`, Kotlin `Uuid.random()` / `kotlin.uuid.Uuid.parse(…)`, `UuidV7.next().toJavaUuid()`, `UUID.randomUUID().toKotlinUuid()`;
      - V3 same-file `UUID`-typed parameter, Kotlin `Uuid`-typed parameter, nullable `UUID` property via a `"$x"` template, V2-initialized local, V4-initialized local;
      - V4 `*UserId`, `user.id`, `userId`, `!!`-peeled `currentUser!!.id`, and elvis-peeled `principal?.userId?.toString() ?: "anon"`;
      - P2 nested in `setAttribute` and inside `Attributes.of` (paired value);
      - a hoisted alias key whose LATER use carries a raw identifier.
    - Passes on:
      - role/name strings (`"system"`);
      - `UserIdHasher.hash(...)` (item 7's anchor test) / `ServiceAccountIdHasher.hash(...)`;
      - an already-hashed local (`hashedUserId`);
      - a `String`-typed non-convention name, including one whose same-named FUNCTION returns `UUID`;
      - UUID values under non-alias keys (`conversation_id`);
      - `user_agent.original`, `email.subject`;
      - a call selector (`p.getUserId()`);
      - a hoisted `AttributeKey` val used with a raw id (documented limit — no paired value).
    - Exercised across `principal`, `actor`, `subject`, `owner`, `user.id`, `enduser.id`, `account`, `service.account.id`.
19. **Raw JWT / OIDC claim value under any key**: `claims.sub` (on `service.account.id`, in a `withSpan` map, and on a neutral key), `credential.payload.subject`, `decoded.subject` and `decoded!!.subject` all fire.
20. **Location key tokens**:
    - Fire: `geo.lat`, `actual_location`, `userCoords` (camelCase), `userGPSLocation` (acronym), `latitude`, `lng`, `coordinates`, `geohash`, `display_lat`, `display_actual_location`; and `userCoords` as a `withSpan` map key.
    - Pass via the exact-key sanction: `display_location`, `displayLocation`.
    - Pass as look-alikes: `latency_ms`, `cloud.platform`, `memory.allocation`, `coordinator`.
    - Pass as non-AKP location words: SQL text, a `"latitude"` request-param literal, an `"actual_lat"` export-map key.
21. **Credential key tokens**:
    - Fire: `client_secret`, `http.request.header.authorization`, `http.request.header.cookie`, `refreshToken`, `db.password`, `supabase.service_role_key`, `api_key`, `id_token`, `private_key`, `IDToken`, `JWTSecret`.
    - Pass: `gen_ai.usage.input_tokens`, `credential.type`.
22. **Single report per literal**: a literal matching several checks (e.g. `setAttribute("secret_location", v)` — credential AND location) produces exactly 1 finding.
23. **Raw client IP under any key**: `span.setAttribute("net.client", call.clientIp)` fires; `IpHasher.hash(call.clientIp)` passes.
24. **Hoisted-key annotation scope**: a hoisted `"user_id"` constant used only inside an `@AllowForbiddenSpanAttribute`-annotated function does NOT fire; the same constant also used in an unannotated function fires once.

#### Scenario: Test class exists and passes
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every scenario above corresponds to at least one test case AND all cases pass

### Requirement: Detekt run against the backend codebase remains green after rule activation

`./gradlew detekt` SHALL pass with `OtelForbiddenAttributeRule` registered and active, including its attribute-key-position checks and extended Tier 2.
- **Scope:** `:backend:ktor` plus every module applying the `nearyou.detekt` convention plugin (`:infra:*`, `:core:*`).
- **Pass condition:** every existing `Span.setAttribute(...)` / `withSpan(...)` / `AttributesBuilder.put(...)` / IP-axis Redis-key literal call site MUST pass the rule. Each is either sanctioned via `UserIdHasher.hash` / `ServiceAccountIdHasher.hash` / `IpHasher.hash`, uses a safe key, or lives in an allowlisted path.
- **If a pre-existing call site fires**, the implementation MUST do one of the following — it MUST NOT loosen the rule to make the run green:
  - fix the call site at its source (preferred — convert to canonical helper consumption or a sanctioned key); OR
  - sanction it with `@AllowForbiddenSpanAttribute("<non-blank reason>")`. The annotation class is declared in `core/domain/.../lint/Annotations.kt` alongside its sibling `Allow*` annotations if and when a sanction is first needed.

The implementation MUST audit:

- Production `Span.setAttribute(...)` writers (today: 2):
  - `AuthPlugin.kt` — `"user.id"` with `UserIdHasher.hash`, sanctioned;
  - `InternalEndpointAuth.kt` — `"service.account.id"` with `ServiceAccountIdHasher.hash`, sanctioned.
- Production `withSpan(name, mapOf(...))` writers (today: 2):
  - `ChatRoutes.kt` — safe keys `conversation_id`, `message_id`, `supabase.realtime.channel`;
  - `FcmDispatcher.kt` — safe keys `messaging.system`, plus `user.id` via `UserIdHasher.hash`.
- Production `AttributesBuilder.put(...)` writers. Today:
  - `:infra:otel/src/main/` internals — allowlisted by path;
  - `ChatRoutes.kt` — the `chat_realtime_publish_failed` event, fluent `Attributes.builder().put("event", …).put("error.type", …)`, safe keys;
  - `FcmDispatcher.kt` — `attrsBuilder.put("event", …)` / `put("error_code", …)`, safe keys.
- Production `tryAcquireByKey(...)` first-arg literals (today: 1 — `HealthRoutes.kt`, sanctioned via `IpHasher.hash`).
- Production `"user_id"` literals (today: ~30 SQL-column / `@SerialName` / route-param / `buildJsonObject` uses). None is in an attribute-key position, so the rule MUST NOT fire on any of them.

#### Scenario: Detekt green post-merge
- **WHEN** running `./gradlew detekt` after this change merges
- **THEN** the command exits 0 with no `OtelForbiddenAttributeRule` findings