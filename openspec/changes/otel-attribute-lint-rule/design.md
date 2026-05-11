## Context

The `observability-otel-foundation` capability spec (line 196) explicitly defers a Detekt rule named `OtelForbiddenAttributeRule` to a future hardening change "once the writer surface is concrete." The writer surface today is minimal: one `Span.setAttribute("user.id", UserIdHasher.hash(...))` call site in [`AuthPlugin.kt:115`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt), one `tryAcquireByKey(...)` call site in [`HealthRoutes.kt:169`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt), and a few attribute-setting paths inside `:infra:otel` itself (`WithSpan.applyAttribute`, `KtorOtelPlugins`). Six Detekt rules already exist that fence adjacent invariants: `RawFromPostsRule`, `BlockExclusionJoinRule`, `CoordinateJitterRule`, `RateLimitTtlRule`, `RedisHashTagRule`, `RawXForwardedForRule`. `OtelForbiddenAttributeRule` is the obvious seventh — locking the forbidden-attributes contract at commit time before more writers land.

The runtime backstop ([`ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt)) handles auto-instrumentation keys (peer-identity attrs the developer didn't write). This change adds the COMPLEMENTARY compile-time check for the developer-written half — manual `Span.setAttribute(...)` / `withSpan(name, mapOf(...))` / `tryAcquireByKey(...)` call sites.

## Goals / Non-Goals

**Goals:**
- Fire on any Kotlin string literal containing one of the high-confidence forbidden attribute keys (the 7 OTel network-semconv names already enumerated in `ForbiddenAttributeStripper.FORBIDDEN_KEYS`) outside the allowlisted paths.
- Fire on any Kotlin string literal matching a high-confidence sensitive-value regex (RSA private key PEM marker, JWT header `eyJhbGc[A-Za-z0-9_-]+\.`, JWKS RSA modulus/exponent shape).
- Fire on any `tryAcquireByKey(...)` call whose first-argument string literal contains an `{ip:<value>}` segment where `<value>` is NOT exactly 16 hex characters (the canonical `IpHasher.hash` output shape).
- Provide `@AllowForbiddenSpanAttribute("<reason>")` annotation bypass for genuine edge cases (e.g., a future admin tool that legitimately sets a normally-forbidden key on an admin-restricted span).
- Register the rule via `NearYouRuleSetProvider` and activate it in the project Detekt config applied to `:backend:ktor`.

**Non-Goals:**
- Runtime span-attribute stripping. Already shipped via `ForbiddenAttributeStripper`. The lint rule is defense-in-depth, not a replacement.
- Detection of forbidden values constructed at runtime (e.g., `Span.setAttribute("user.id", uuid.toString())` where `uuid` is a variable, or attributes built from `SecretResolver.resolve(...)`). The rule scans Kotlin string literals via Detekt PSI; runtime construction is invisible. The runtime stripper + the existing sentinel-string regression tests remain the backstop for that surface.
- Mobile-side enforcement. Sentry KMP / Amplitude attribute calls on `:mobile:app` are out of scope until the mobile telemetry path lands; that's a follow-up change.
- SQL migration file scanning. Detekt visits Kotlin PSI; `.sql` files are reviewed in PR.
- The `tryAcquireByKey`-vs-`tryAcquire` method-choice invariant (the [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) item 7 `tryacquirebykey-ip-derived-uuid-detekt-rule` follow-up). That's a separate posture (correct method choice for IP-axis buckets) and a separate rule with separate semantics; it stays an open follow-up.
- Retrofitting `@AllowForbiddenSpanAttribute` onto every existing test fixture. The path-based allowlist for `:infra:otel`'s own test fixtures + the small writer surface today cover every live call site without annotations.

## Decisions

### Decision 1: Single rule with two enforcement axes (forbidden patterns + IP-axis call-site)

`OtelForbiddenAttributeRule` enforces both:
- **Mode A — forbidden patterns anywhere**: Fire on any Kotlin string literal containing one of the forbidden-attribute-key strings (Tier 1) OR matching one of the sensitive-value regex patterns (Tier 3). Mirrors `RawXForwardedForRule` / `CoordinateJitterRule` — no parent-call-context check, just string-literal match + path-based allowlist.
- **Mode B — IP-axis call-site check**: Fire on `tryAcquireByKey(...)` calls whose first-argument string literal contains `{ip:<value>}` where `<value>` is not 16 hex characters. Parent-call-context check IS required here because the `{ip:...}` shape is only meaningful in a Redis-key context (and the existing `RedisHashTagRule` already covers the broader hash-tag shape).

**Alternative considered:** Two separate rules (`OtelForbiddenAttributeRule` for span-attr modes + `IpAxisHashedKeyRule` for the IP-axis mode). Rejected — the [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) entry's action item 3 explicitly folds the IP-axis enforcement into this rule ("Extend rule scope to enforce `IpHasher.hash` consumption..."). The unifying rationale is "raw PII MUST NOT surface in telemetry surfaces" — span attributes AND Lettuce-instrumented Redis-EVALSHA `db.statement` spans (which surface IP-axis Redis keys) are both telemetry. Keeping one rule means one enable/disable surface and one config edit when patterns evolve.

### Decision 2: Same PSI shape as `RawXForwardedForRule` / `CoordinateJitterRule`

Visit `KtStringTemplateExpression`, regex-match the expression's source text, path-based allowlist by substring check on the normalized `virtualFilePath`, annotation bypass via `KtAnnotated` ancestor walk. Mirroring established rules means one mental model for maintainers; the shape is proven across 6 active rules and 8+ shipped Detekt-lint tests with zero false-positives in the V4–V12 shipping history. For Mode B, additionally walk PSI ancestors to verify the literal is the first-argument of a `tryAcquireByKey` call expression.

**Alternative considered:** Use Detekt's `visitCallExpression` with full type-resolved arg analysis. Rejected — the existing rules use string-template visiting + parent-walk and it works; adding type-resolution complexity isn't warranted for the small writer surface today, and type-resolved Detekt configs require additional Gradle setup the project doesn't currently use.

### Decision 3: Forbidden-attribute-keys list = exact mirror of `ForbiddenAttributeStripper.FORBIDDEN_KEYS`

The rule's Tier 1 key list MUST EQUAL the runtime list at [`ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) `FORBIDDEN_KEYS` (11 entries today: 8 OTel HTTP / network peer-identity semconv keys + 3 user-id typo defensive variants `user_id` / `user_uuid` / `user.uuid`). Synchronization is enforced via the synchronization-guard unit test (spec § "Detekt test coverage" item 11) that fails if the two lists diverge.

**Alternative considered (superset):** Allow the lint rule's list to be a *superset* of the runtime list (lint catches strictly more keys). Rejected — divergence makes the synchronization-guard test logic ambiguous ("lint contains everything in runtime" vs "lint equals runtime"), and there's no current writer-side concern that justifies adding lint-only keys. Defensive additions (e.g., `db.connection_string` for value-sanitization concerns) are better expressed via Tier 2 value-regex (the Redis URI userinfo pattern) than via Tier 1 key-name match. Keep the lists exactly equal for clarity; add to BOTH lists in a future change if a new key emerges as forbidden.

**Alternative considered (independent):** Treat the rule's list and the runtime list as fully independent. Rejected — divergence is a footgun: a key added to runtime-strip but not lint-fail would slip through code review; the opposite direction is also bad (a key the lint rule blocks but the runtime stripper accepts would be a "lint says no" + "runtime says yes" contradiction reviewers can't quickly resolve).

### Decision 4: Sensitive-value-regex tier is small and high-confidence only

Patterns shipped in the MVP rule:
- `\-{5}BEGIN [A-Z ]+PRIVATE KEY\-{5}` — RSA / EC / Ed25519 PEM private key marker. Never legitimate in source code.
- `eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.` — JWT shape (`header.payload.signature` with base64url-encoded header starting `eyJ`). Catches accidentally-committed test tokens.
- `redis://[^:]+:[^@/]+@` — Redis URI with explicit userinfo (carries password). Should only appear inside `:infra:redis` config code.

Tier 3 stays narrow because every regex is a false-positive risk; the runtime `ForbiddenAttributeStripper` + the sentinel-string regression tests at integration time cover broader value shapes. Future patterns (OAuth client_secret shape, AES key base64) can be added in a follow-up change once we have a writer surface that justifies the broader regex risk.

**Alternative considered:** Ship a broader regex set covering all 12 forbidden-attribute categories. Rejected — the FOLLOW_UPS entry's "Detekt rules built ahead of writers tend to over-fit" caution applies; we'll iterate after the rule sees real-world false-positive feedback.

### Decision 5: IP-axis enforcement passes on canonical-hex OR Kotlin template placeholders

The functional contract: a `{ip:<value>}` segment inside a `tryAcquireByKey` first-argument string literal passes IF AND ONLY IF either (a) `<value>` is exactly 16 lowercase hexadecimal characters (the canonical `IpHasher.hash` output), OR (b) `<value>` is a Kotlin template-string placeholder (`${...}`) whose runtime value the implementation cannot statically verify.

Clause (b) is load-bearing: the canonical call-site shape in production is `tryAcquireByKey("{scope:health}:{ip:${IpHasher.hash(clientIp)}}", ...)` — the value between `{ip:` and `}` is a placeholder, NOT a literal 16-hex string. Without the placeholder exception, the rule would false-positive on every legitimate call site.

**Recommended regex** (implementation phase may refine): `\{ip:(?![0-9a-f]{16}\})(?!\$\{)[^}]*\}`. The two negative-lookaheads handle the two passing cases:
- `(?![0-9a-f]{16}\})` — pass if the next chars are exactly 16 lowercase hex followed by `}` (clause a)
- `(?!\$\{)` — pass if the next chars are `${` (clause b — Kotlin template placeholder start marker)
- `[^}]*\}` — match the rest of the `{ip:...}` segment if neither lookahead saved it

The FOLLOW_UPS entry's `[^h{][^}]*\}` heuristic was incorrect (it would fire on canonical 16-hex starting with `a`–`f` digits and would not handle placeholders); the corrected recommended regex above replaces it.

**Alternative considered:** Lint at the `tryAcquireByKey` call-arg site without inspecting the literal contents — fire on any `tryAcquireByKey` call whose key argument isn't a `String` produced by `IpHasher.hash(...)`. Rejected — that requires data-flow analysis Detekt's default config doesn't perform; the literal-pattern check covers the same surface with simpler PSI logic.

**Alternative considered (PSI-aware over regex):** Implement the IP-axis check by walking `KtStringTemplateExpression.entries` to detect interpolation entries (`KtSimpleNameStringTemplateEntry`, `KtBlockStringTemplateEntry`) and skip the rule when an interpolation lives inside the `{ip:...}` segment. Both this approach and the recommended-regex approach satisfy the functional contract; implementation phase picks one.

### Decision 6: Allowlist by path + annotation

Allowlisted paths:
- `:infra:otel` source (`/infra/otel/src/`) — the module legitimately enumerates forbidden keys as DATA (the `FORBIDDEN_KEYS` Set; sentinel-string test fixtures). Both `main/` and `test/` paths are allowed.
- `:lint:detekt-rules` source (`/lint/detekt-rules/src/`) — the rule itself + its test fixtures necessarily contain the forbidden patterns as DATA / TEST INPUTS.
- Synthetic `Test.kt` virtual paths from `detekt-test`'s `lint(String)` overload — NOT exempt by path (mirrors `RawXForwardedForRule`'s convention). Tests must pass real fixtures using the `cleanupDir` pattern.

Annotation bypass: `@AllowForbiddenSpanAttribute("<reason>")` on the enclosing function, class, or property. Reason string MUST be non-empty per existing rule convention.

**Alternative considered:** Package-only allowlist (`id.nearyou.app.infra.otel`, `id.nearyou.lint.detekt`). Rejected — `:infra:otel`'s test sources live under `id.nearyou.app.infra.otel` package too but in a different module; path-based allowlist mirrors the existing rules' convention and works the same for test/main.

### Decision 7: Rule registered cross-cuttingly, not scoped to `:infra:otel`

Activate the rule in the project Detekt config applied to all Kotlin source sets (the same scope `RawXForwardedForRule` etc. use today). Reason: the writer surface for `Span.setAttribute(...)` is `:backend:ktor` primarily, but any future module could call into OTel attribute APIs; making the rule cross-cutting is the right enforcement boundary. The allowlist (Decision 6) keeps `:infra:otel`'s own sources from firing.

**Alternative considered:** Scope to `:backend:ktor` only. Rejected — same rationale as the cross-cutting registration of other rules; one rule scope, simple mental model.

## Risks / Trade-offs

- **False positive on documentation strings**: a KDoc comment or display string mentioning `client.address` would NOT trigger (Detekt visits `KtStringTemplateExpression`, not KDoc comments), but a class constant like `val FORBIDDEN_KEY_NAME = "client.address"` in non-`:infra:otel` code WOULD trigger. → **Mitigation**: `@AllowForbiddenSpanAttribute("<reason>")` on the property. Realistically, this case shouldn't appear outside `:infra:otel` (no other module has a legitimate reason to hold these key names as constants).

- **False negative on dynamic key construction**: `Span.setAttribute("network." + "peer.address", value)` or `Span.setAttribute(KEY_PREFIX + "address", value)` would not match a literal regex. → **Mitigation**: dynamic key construction is itself a code-review smell and would be flagged by reviewers; the runtime stripper covers this path. Accept the false-negative for MVP — `docs/08-Roadmap-Risk.md` "grep-level is OK for MVP" applies.

- **Synchronization drift between lint rule and runtime stripper**: if `FORBIDDEN_KEYS` in `ForbiddenAttributeStripper.kt` gains a new entry and the lint rule's Tier 1 list isn't updated, the rule's coverage degrades silently. → **Mitigation**: implementation MAY add a `:lint:detekt-rules` test that asserts the rule's list contains every entry in `FORBIDDEN_KEYS` (per Decision 3); design captures the intent, implementation phase decides shape.

- **Tier 3 sensitive-value regex tuning**: too narrow misses real leaks; too broad false-positives on test fixtures. → **Mitigation**: ship the 3 high-confidence patterns from Decision 4 only; widen in a follow-up after real-world feedback. The runtime sentinel-string test surface (integration tests at staging) provides the broader-but-slower coverage.

- **IP-axis regex misses legitimate non-`ip:` axes containing raw IPs in values**: a future axis `{geocell:6.21_106.85}` would not match. → **Mitigation**: out of scope; the spec only mandates IP-axis hashing today. New axes that require similar enforcement extend the rule via a follow-up.

- **Rule activates Detekt-fast-fail on `main` if pre-existing call sites violate it**: today's writer surface (1 sanctioned `setAttribute` in `AuthPlugin.kt`, 1 sanctioned `tryAcquireByKey` in `HealthRoutes.kt`) passes the rule by construction. → **Mitigation**: validated at implementation time before merge; if any pre-existing call site fires the rule, the implementation either fixes the call site OR adds the canonical allowlist (annotation or path). Zero call-site changes expected.
