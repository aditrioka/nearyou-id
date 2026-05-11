## Context

The `observability-otel-foundation` capability spec (line 196 in the existing as-shipped spec) explicitly defers a Detekt rule named `OtelForbiddenAttributeRule` to a future hardening change "once the writer surface is concrete." The writer surface today is minimal:
- 1 production `Span.setAttribute(...)` call site: [`AuthPlugin.kt:115`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) (sanctioned, via `UserIdHasher.hash`).
- 2 production `withSpan(name, mapOf(...))` call sites: [`ChatRoutes.kt:354`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRoutes.kt) (chat realtime publish; safe keys `conversation_id`, `message_id`, `supabase.realtime.channel`) + [`FcmDispatcher.kt:159`](../../../infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FcmDispatcher.kt) (FCM dispatch; safe keys `messaging.system`, `user.id` via `UserIdHasher.hash`).
- 1 production `tryAcquireByKey(...)` literal-bearing call site: [`HealthRoutes.kt:166-170`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) (sanctioned, via `IpHasher.hash`).
- Multiple attribute-setting paths inside `:infra:otel` itself (`WithSpan.applyAttribute`, `KtorOtelPlugins`).

Six Detekt rules already exist that fence adjacent invariants: `RawFromPostsRule`, `BlockExclusionJoinRule`, `CoordinateJitterRule`, `RateLimitTtlRule`, `RedisHashTagRule`, `RawXForwardedForRule`. `OtelForbiddenAttributeRule` is the obvious seventh — locking the forbidden-attributes contract at commit time before more writers land.

The runtime backstop ([`ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt)) handles auto-instrumentation keys the developer didn't write (the 11 `FORBIDDEN_KEYS` entries — 8 OTel HTTP / network peer-identity semconv keys + 3 user-id typo-defensive variants). This change adds the COMPLEMENTARY compile-time check for the developer-written half — manual `Span.setAttribute(...)`, `withSpan(name, mapOf(...))`, `AttributesBuilder.put(...)`, and any literal containing `{ip:<non-canonical>}`.

## Goals / Non-Goals

**Goals:**
- Fire on any Kotlin string literal containing one of the 22 Tier 1 forbidden-attribute keys (the 11-entry `FORBIDDEN_KEYS` mirror + 8 underscore-typo-defensive variants + 3 JWT-claim attribute keys) outside the allowlisted paths.
- Fire on any Kotlin string literal matching one of the 4 high-confidence Tier 2 sensitive-value regex patterns (RSA / EC / Ed25519 PEM private key marker, JWT three-segment shape, Redis URI with userinfo, JWKS RSA JSON shape).
- Fire on any Kotlin string literal containing `{ip:<value>}` where `<value>` is neither (a) exactly 16 lowercase hex characters — the canonical `IpHasher.hash` output, nor (b) a Kotlin template-string placeholder (`$<simple-name>` OR `${<expression>}`). The rule is NOT scoped to `tryAcquireByKey` call-context — the production literal is hoisted to a `val` first, so PSI parent-walk would miss it.
- Provide `@AllowForbiddenSpanAttribute("<reason>")` annotation bypass with `isNotBlank()` enforcement on the reason string (mirrors `@AllowRawRedisKey` precedent).
- Register the rule via `NearYouRuleSetProvider` and activate it in the project Detekt config applied to all Kotlin source sets.

**Non-Goals:**
- Runtime span-attribute stripping. Already shipped via `ForbiddenAttributeStripper`. The lint rule is defense-in-depth, not a replacement.
- Detection of forbidden values constructed at runtime (e.g., `Span.setAttribute("user.id", uuid.toString())` where `uuid` is a variable, or attributes built from `SecretResolver.resolve(...)`). The rule scans Kotlin string literals via Detekt PSI; runtime construction is invisible. The runtime stripper + the existing sentinel-string regression tests remain the backstop for that surface.
- Mobile-side enforcement. Sentry KMP / Amplitude attribute calls on `:mobile:app` are out of scope until the mobile telemetry path lands; that's a follow-up change.
- SQL migration file scanning. Detekt visits Kotlin PSI; `.sql` files are reviewed in PR.
- The `tryAcquireByKey`-vs-`tryAcquire` method-choice invariant (FOLLOW_UPS.md item 7 `tryacquirebykey-ip-derived-uuid-detekt-rule`). That's a separate rule with separate semantics; it stays an open follow-up.
- Value-aware detection of raw user_id under generic-named keys (`principal`, `actor`, `subject`, `owner`). The canonical spec explicitly mentions these as forbidden-by-semantics, but key-name-exact-match with these high-false-positive identifiers would block legitimate auth-domain code. Deferred to a value-aware follow-up (see "Explicitly deferred follow-ups" below).
- Key-name pattern enforcement for `*location*` / `*lat*` / `*lng*` / `*coord*`. The canonical spec mandates this but a substring match would false-positive on `display_location` (the sanctioned key) and conflict with `CoordinateJitterRule` (which already handles `actual_location`). Deferred to a follow-up that uses tighter regex.
- Tier 2 value-shape patterns for opaque secrets (OAuth `client_secret` values, raw refresh tokens, plaintext passwords). These are opaque strings without distinguishing markers — any regex would over-match or under-match. Covered by code review + the runtime stripper's value-substring sentinel test pattern.

## Decisions

### Decision 1: Single rule with two enforcement axes (forbidden patterns + IP-axis value-shape)

`OtelForbiddenAttributeRule` enforces both:
- **Mode A — forbidden patterns anywhere**: Fire on any Kotlin string literal containing one of the 22 Tier 1 forbidden-attribute-key strings OR matching one of the 4 Tier 2 sensitive-value regex patterns. Mirrors `RawXForwardedForRule` / `CoordinateJitterRule` — no parent-call-context check, just string-literal match + path-based allowlist.
- **Mode B — IP-axis value-shape anywhere**: Fire on any Kotlin string literal containing `{ip:<value>}` where `<value>` is neither canonical 16-hex nor a Kotlin template placeholder. NO call-site context check — the production literal is hoisted to `val key = "..."` BEFORE being passed to `tryAcquireByKey`, so a parent-walk requirement would produce zero findings (see Decision 5).

**Alternative considered:** Two separate rules. Rejected — the FOLLOW_UPS.md entry's action item 3 explicitly folds the IP-axis enforcement into this rule ("Extend rule scope to enforce `IpHasher.hash` consumption..."). The unifying rationale is "raw PII MUST NOT surface in telemetry surfaces" — span attributes AND Lettuce-instrumented Redis-EVALSHA `db.statement` spans (which surface IP-axis Redis keys) are both telemetry. Keeping one rule means one enable/disable surface and one config edit when patterns evolve.

### Decision 2: Same PSI shape as `RawXForwardedForRule` / `CoordinateJitterRule`

Visit `KtStringTemplateExpression`, regex-match the expression's source text, path-based allowlist by substring check on the normalized `virtualFilePath`, annotation bypass via `KtAnnotated` ancestor walk. Mirroring established rules means one mental model for maintainers; the shape is proven across 6 active rules with zero false-positives in the V4–V12 shipping history.

**Alternative considered:** Use Detekt's `visitCallExpression` with full type-resolved arg analysis. Rejected — the existing rules use string-template visiting + path-based allowlist and it works; adding type-resolution complexity isn't warranted for the small writer surface today.

### Decision 3: Tier 1 list IS a superset of `ForbiddenAttributeStripper.FORBIDDEN_KEYS` (not exact equal)

The rule's Tier 1 list contains 22 keys = 11 from `FORBIDDEN_KEYS` (Group A) + 8 symmetric typo-defensive underscore variants (Group B) + 3 JWT-claim attribute keys (Group C). The synchronization-guard test (spec § "Detekt test coverage" item 11) asserts the lint rule's Tier 1 list `containsAll FORBIDDEN_KEYS` — superset relationship, NOT exact equal. The test message MUST name the missing key(s) on failure.

**Why superset, not exact-equal:** The runtime stripper only handles attrs the dev didn't write (auto-instrumentation peer-identity surface + the user-id typo guards). The lint rule additionally covers symmetric typos a dev might write under `client_address` (underscore not dot — a known typo-bypass vector) and the canonical spec's JWT-claim attribute keys (`jwt.sub`, `jwt.aud`, `jwt.iss`). Asymmetric coverage is correct here: runtime is value-side (auto-instrumentation), lint is key-side (developer writes).

**Alternative considered (exact equal):** Initial proposal locked the lists to exact equality with the synchronization-guard test asserting bidirectional containment. Rejected after multi-lens review — too restrictive; the symmetric typo variants AND the JWT-claim keys are genuine value-add over the runtime list.

**Alternative considered (independent):** Treat the lists as fully independent. Rejected — divergence the OTHER direction (runtime adds a key but lint doesn't) is a real footgun. Superset gives both sides correct enforcement boundaries.

### Decision 4: Tier 2 ships 4 high-confidence value-regex patterns

Patterns shipped in the MVP rule:
- `\-{5}BEGIN [A-Z ]+PRIVATE KEY\-{5}` — RSA / EC / Ed25519 PEM private key marker. Never legitimate in source outside test fixtures.
- `eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.` — JWT shape (`header.payload.signature` with base64url-encoded segments starting `eyJ`).
- `redis://[^:]+:[^@/]+@` — Redis URI with explicit userinfo (carries password). Should only appear inside `:infra:redis` config code (allowlisted by `/infra/otel/src/main/` if the Redis URI is constructed there — but more practically, it's allowlisted by `/src/test/` paths).
- `"kty"\s*:\s*"RSA"\s*,?\s*"n"\s*:` — JWKS RSA-key JSON shape (specific marker: `"kty":"RSA"` followed by `"n":` modulus). Specific enough to avoid false-positives on legitimate JSON-with-`kty` mentions in unrelated contexts (e.g., test fixtures explicitly testing JWKS handling).

Tier 3 stays narrow because every regex is a false-positive risk; the runtime `ForbiddenAttributeStripper` + the sentinel-string regression tests at integration time cover broader value shapes. Future patterns (OAuth `client_secret` shape, AES key base64, raw refresh tokens) can be added in a follow-up change once we have a writer surface that justifies the broader regex risk (see "Explicitly deferred follow-ups" below).

**Alternative considered:** Ship a broader regex set covering all categories from the canonical spec. Rejected — the FOLLOW_UPS entry's "Detekt rules built ahead of writers tend to over-fit" caution applies; we'll iterate after the rule sees real-world false-positive feedback.

### Decision 5: Mode B drops the call-site-context restriction; fires on any `{ip:<value>}` literal

The originally-proposed PSI parent-walk to verify a `tryAcquireByKey` enclosing call expression CANNOT work against the real codebase. The canonical production call site at [`HealthRoutes.kt:166-170`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) is:

```kotlin
val hashedIp = IpHasher.hash(call.clientIp)
val key = "{scope:health}:{ip:$hashedIp}"  // ← literal hoisted to val
// ...
rateLimiter.tryAcquireByKey(key = key, ...)  // ← variable reference, not literal
```

Detekt's `KtStringTemplateExpression` at line 167's literal has PSI parent `KtProperty(key)`, not `KtCallExpression(tryAcquireByKey)`. A parent-walk would NEVER find `tryAcquireByKey` and produce zero findings. The rule MUST drop the call-site-context restriction and fire on any `{ip:<value>}` literal anywhere — the path-based allowlist (`/src/test/`, `/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`) handles legitimate test fixtures and the rule's own constant-data sources.

**Functional contract** (the authoritative spec for what MUST fire / pass):
1. **PASS** — `{ip:[0-9a-f]{16}}` (16 lowercase hex)
2. **PASS** — `{ip:$<identifier>}` (Kotlin simple-name template — the canonical production shape)
3. **PASS** — `{ip:${<expression>}}` (Kotlin block-form template)
4. **FIRE** — anything else (raw IPv4, IPv6, 15-hex, 17-hex, uppercase-hex, mixed-case)

**Recommended regex** (illustrative; final shape selected at implementation time): `\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}`. The two negative-lookaheads handle the two passing cases:
- `(?![0-9a-f]{16}\})` — pass if next chars are exactly 16 lowercase hex followed by `}` (clause 1)
- `(?!\$)` — pass if next char is `$` (clauses 2 AND 3 — both `$<identifier>` and `${<expression>}` start with `$`)
- `[^}]*\}` — match the rest of the `{ip:...}` segment if neither lookahead saved it

**Alternative considered (PSI-aware over regex):** Walk `KtStringTemplateExpression.entries` to detect interpolation entries inside the `{ip:...}` segment. Both this approach and the recommended-regex approach satisfy the functional contract; implementation phase picks one.

**Alternative considered (data-flow):** Use Detekt's full type-resolution mode to chase `val key = "..."` back to the literal initializer when the variable is passed to `tryAcquireByKey`. Rejected — adds significant complexity for marginal precision; the path-allowlist approach is simpler and correct.

### Decision 6: Allowlist by `/src/test/` broadly + module-specific main paths + annotation

Allowlisted paths:
1. **Any `/src/test/` path** (mirrors `RedisHashTagRule` / `CoordinateJitterRule` precedent). Test fixtures across the codebase legitimately contain raw `{ip:1.2.3.4}` for limiter behavior tests, regex-string canonical-shape assertions, sentinel-string forbidden-key references, etc. Verified path inventory: `infra/redis/src/test/`, `core/domain/src/test/`, `backend/ktor/src/test/`, `infra/otel/src/test/` — all contain fixtures that the rule must allowlist.
2. **`/infra/otel/src/main/`** — the module legitimately enumerates forbidden keys as DATA (the `FORBIDDEN_KEYS` Set).
3. **`/lint/detekt-rules/src/main/`** — the rule itself necessarily contains the forbidden patterns as DATA / REGEX CONSTANTS.

Annotation bypass: `@AllowForbiddenSpanAttribute("<reason>")` on the enclosing function, class, or property. Reason string MUST be `isNotBlank()` (mirrors `RedisHashTagRule.kt:134` precedent — covers empty, whitespace-only, tabs, newlines).

**Alternative considered (narrow `/infra/otel/src/test/` only):** Initial proposal allowlisted only `:infra:otel/src/test/` and `:lint:detekt-rules/src/test/`. Rejected after audit found raw `{ip:1.2.3.4}` literals in `infra/redis/src/test/.../RedisRateLimiterTelemetryTest.kt:171`, `RedisRateLimiterIntegrationTest.kt:352,356`, `core/domain/src/test/.../RateLimiterTest.kt:116,136`, AND `backend/ktor/src/test/.../HealthRoutesScenariosTest.kt:382` (regex `\{ip:[0-9a-f]{16}\}`). All these are legitimate test fixtures; surgically allowlisting each module would be brittle. Broad `/src/test/` matches existing conventions.

### Decision 7: Rule registered cross-cuttingly, not scoped to `:infra:otel`

Activate the rule in the project Detekt config applied to all Kotlin source sets (the same scope `RawXForwardedForRule` etc. use today). Reason: the writer surface for `Span.setAttribute(...)` is `:backend:ktor` primarily, but any future module could call into OTel attribute APIs; making the rule cross-cutting is the right enforcement boundary. The allowlist (Decision 6) keeps the rule's own infrastructure paths from firing.

## Explicitly deferred follow-ups

Out of scope for this change but tracked as new entries in `FOLLOW_UPS.md` at archive time:

- **`otel-attribute-rule-value-aware-userid-aliases`**: Add detection of raw user-id under generic-named keys (`principal`, `actor`, `subject`, `owner`). Requires value-aware analysis (firing only when the value resolves to a UUID-shaped literal) to avoid false-positives on auth-domain code. The canonical spec at `observability-otel-foundation/spec.md:186` enumerates these aliases.
- **`otel-attribute-rule-location-key-patterns`**: Add detection of `*location*` / `*lat*` / `*lng*` / `*coord*` key-name patterns per the canonical spec at `observability-otel-foundation/spec.md:191`. Requires careful regex to avoid false-positives on `display_location` (sanctioned) and overlap with `CoordinateJitterRule` (which handles `actual_location`).
- **`otel-attribute-rule-opaque-secrets`**: Tier 2 patterns for OAuth `client_secret` values, raw refresh tokens, plaintext passwords. These are opaque strings — any regex over-matches or under-matches. Requires either a known-prefix convention (e.g., OAuth client_secret always starts with a known prefix) or accept that code review remains the canonical defense.

## Risks / Trade-offs

- **False positive on documentation strings**: a KDoc comment or display string mentioning `client.address` would NOT trigger (Detekt visits `KtStringTemplateExpression`, not KDoc comments), but a class constant like `val FORBIDDEN_KEY_NAME = "client.address"` in non-allowlisted code WOULD trigger. → **Mitigation**: `@AllowForbiddenSpanAttribute("<reason>")` on the property. Realistically, this case shouldn't appear outside `:infra:otel/src/main/` (no other module has a legitimate reason to hold these key names as constants).

- **False negative on dynamic key construction**: `Span.setAttribute("network." + "peer.address", value)` or `Span.setAttribute(KEY_PREFIX + "address", value)` would not match a literal regex. → **Mitigation**: dynamic key construction is itself a code-review smell and would be flagged by reviewers; the runtime stripper covers this path. Accept the false-negative for MVP.

- **Synchronization drift between lint rule and runtime stripper**: if `FORBIDDEN_KEYS` in `ForbiddenAttributeStripper.kt` gains a new entry and the lint rule's Group A list isn't updated, the rule's coverage degrades silently. → **Mitigation**: the synchronization-guard test (spec § "Detekt test coverage" item 11) fails with a specific message naming the missing key(s).

- **Tier 2 sensitive-value regex false positives**: the 4 patterns are high-confidence but not zero-risk. PEM marker false-positive on docstring-like literals discussing PEM format; JWT pattern false-positive on coincidentally-shaped base64 data. → **Mitigation**: shipping only 4 high-confidence patterns; broader patterns deferred per Decision 4 + "Explicitly deferred follow-ups."

- **IP-axis regex misses non-`{ip:...}` axes containing raw IPs**: a future axis `{geocell:6.21_106.85}` (not implemented today) would not match. → **Mitigation**: out of scope; the spec only mandates IP-axis hashing today. New axes extend the rule via a follow-up.

- **`/src/test/` allowlist is broad**: a developer could commit a real (not synthetic) bearer token / PEM key inside a test fixture and the rule wouldn't catch it. → **Mitigation**: matches existing project posture (`RedisHashTagRule` uses the same broad allowlist); if real-PII-in-test-fixtures becomes a concern, that's a follow-up. The integration-test sentinel-string regression scenarios still cover the production output path.

- **Build-graph requirement for synchronization-guard test**: the `:lint:detekt-rules:test` source set needs a compile-time reference to `ForbiddenAttributeStripper.FORBIDDEN_KEYS` (currently `:lint:detekt-rules` does not depend on `:infra:otel`). → **Mitigation**: implementation phase adds a `testImplementation(project(":infra:otel"))` line in `lint/detekt-rules/build.gradle.kts`. Alternative: hardcode the expected keys in the test fixture, with a comment pointing at `ForbiddenAttributeStripper.kt:89-108` (defers maintenance pain to the same place); pick at implementation time.

- **Rule activates Detekt-fast-fail on `main` if pre-existing call sites violate it**: today's writer surface (1 sanctioned `setAttribute`, 2 sanctioned `withSpan(mapOf(...))`, 1 sanctioned `tryAcquireByKey` literal in main + multiple `{ip:` literals in `/src/test/` paths) is expected to pass by construction. → **Mitigation**: validated at implementation time before merge; the audit at spec § "Detekt run against the backend codebase remains green after rule activation" enumerates the surfaces to check.
