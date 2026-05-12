## ADDED Requirements

### Requirement: `IpAxisMustUseTryAcquireByKeyRule` fences user-axis method on IP-axis keys

The `:lint:detekt-rules` module SHALL ship a Detekt `Rule` named `IpAxisMustUseTryAcquireByKeyRule` that fires on any `KtCallExpression` whose callee short name is `tryAcquire` AND whose `key` argument is a Kotlin `KtStringTemplateExpression` whose flat textual content matches the regex `\{[^}]*ip:`. The `key` argument MAY appear positionally (index 1) OR as the named argument `key = ...`; both shapes MUST be handled. The fire location SHALL point to the `key`-argument PSI element (the string literal), not the enclosing call. The error message SHALL include a recommended fix: "Use `RateLimiter.tryAcquireByKey(key, capacity, ttl)` for IP-axis bucketing — `tryAcquire(userId, key, ...)` is the user-axis entry point."

The rule SHALL be registered in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/NearYouRuleSetProvider.kt` and SHALL be enabled in the project Detekt config so it applies cross-cuttingly to every Kotlin source set under `:backend:ktor`, `:core:domain`, `:infra:*`, `:shared:*`, and any future module that depends on `:core:domain` (the source of the `RateLimiter` interface).

The rule SHALL short-circuit (return early without firing) on any `KtFile` whose `virtualFilePath` contains the substring `/src/test/`. This path-based test-source allowlist mirrors the precedent set by `OtelForbiddenAttributeRule`.

The rule SHALL NOT introduce an annotation-bypass mechanism (no `@AllowIpAxisOnTryAcquire` or equivalent). The standard Detekt `@Suppress("IpAxisMustUseTryAcquireByKey")` mechanism remains available as the per-call escape hatch (consistent with every other rule in the project ruleset); code-review is the canonical defence against `@Suppress` misuse.

This requirement complements — and does NOT replace — the existing `### Requirement: RateLimiter interface in :core:domain` scenario "tryAcquireByKey omits userId from telemetry" (which forbids the literal sentinel UUID `00000000-0000-0000-0000-000000000000` at runtime/log level) AND the existing `### Requirement: OtelForbiddenAttributeRule fences raw IP literal in {ip:<value>} rate-limit-key segments` (which fires on raw IP value-shapes at compile time). Together the three layers form defense-in-depth on the IP-axis bucketing contract: value-shape (compile-time, OtelForbiddenAttributeRule), method-choice (compile-time, this rule), telemetry surface (runtime, RedisRateLimiter structured-log).

#### Scenario: tryAcquire with literal IP-axis key fires
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", capacity, ttl)` (with a real `userId` UUID — not a sentinel — and a properly-hashed 16-hex IP)
- **THEN** the rule fires on the `key`-argument literal AND the error message recommends `tryAcquireByKey`

#### Scenario: tryAcquire with IP-derived UUID + IP-axis key fires
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(UUID.nameUUIDFromBytes(ip.toByteArray()), "{scope:foo}:{ip:abc123def4567890}", capacity, ttl)` — the regression vector that motivates this change
- **THEN** the rule fires on the `key`-argument literal (the IP-derived `userId` is NOT the rule's gate; the gate is the `{ip:...}` axis in the `key`)

#### Scenario: tryAcquire with literal sentinel UUID + IP-axis key fires
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(UUID.fromString("00000000-0000-0000-0000-000000000000"), "{scope:foo}:{ip:abc123def4567890}", capacity, ttl)` — the compile-time complement to the runtime-only structured-log scenario
- **THEN** the rule fires (mechanically, without inspecting the `userId` value — the rule's gate is the `key`-argument shape alone)

#### Scenario: tryAcquire with template-string IP value fires
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:${'$'}hashedIp}", capacity, ttl)` (Kotlin simple-name interpolation — the canonical production value shape)
- **THEN** the rule fires (the `{ip:...}` axis-key shape is statically present in the literal regardless of the value's runtime resolution; the canonical method for any `{ip:...}` axis is `tryAcquireByKey`)

#### Scenario: tryAcquire with named-argument IP-axis key fires
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId = u, key = "{scope:foo}:{ip:abc123def4567890}", capacity = 10, ttl = ttl)` (explicit named-argument syntax)
- **THEN** the rule fires (the named-argument fallback resolves the `key` parameter identically to the positional form)

#### Scenario: tryAcquireByKey with IP-axis key passes
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquireByKey("{scope:health}:{ip:abc123def4567890}", capacity, ttl)`
- **THEN** the rule does NOT fire (correct method for the IP axis)

#### Scenario: tryAcquire with user-axis key passes
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId, "{scope:rate_like_day}:{user:${'$'}userId}", capacity, ttl)` (no `{ip:...}` segment)
- **THEN** the rule does NOT fire

#### Scenario: tryAcquire with non-IP non-user axis key passes
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId, "{scope:foo}:{geocell:abc}", capacity, ttl)` (geocell axis, no `{ip:...}` segment)
- **THEN** the rule does NOT fire (rule scope is intentionally narrow to `ip:` only — see `design.md` § Decision 3 for the future-extension path)

#### Scenario: Semaphore.tryAcquire short-name collision passes
- **WHEN** a non-allowlisted Kotlin file contains the call `semaphore.tryAcquire(1, TimeUnit.SECONDS)` where `semaphore` is a `java.util.concurrent.Semaphore` instance
- **THEN** the rule does NOT fire (the second positional argument is not a `KtStringTemplateExpression` — the argument-shape gate eliminates the false-positive surface)

#### Scenario: Test-source allowlist applies
- **WHEN** a Kotlin file under any `/src/test/` path (e.g., `infra/redis/src/test/kotlin/.../RedisRateLimiterTelemetryTest.kt`, `core/domain/src/test/kotlin/.../RateLimiterTest.kt`, the new `lint/detekt-rules/src/test/kotlin/.../IpAxisMustUseTryAcquireByKeyLintTest.kt`) contains the call `RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", capacity, ttl)` to verify the limiter's behavior on a wrong-method call site
- **THEN** the rule does NOT fire on that call (the `/src/test/` path allowlist suppresses; tests of the limiter and the rule itself legitimately need wrong-method fixtures)

#### Scenario: Composition with RedisHashTagRule + OtelForbiddenAttributeRule produces independent findings
- **WHEN** a non-allowlisted Kotlin file contains the call `RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:1.2.3.4}", capacity, ttl)` — a single line that triggers (a) `IpAxisMustUseTryAcquireByKeyRule` (wrong method for IP axis) AND (b) `OtelForbiddenAttributeRule` IP-axis Mode B (raw IPv4 in the `{ip:...}` value position)
- **THEN** both rules fire independently AND each finding is reported separately AND there is no cross-suppression between them

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** the test `NearYouRuleSetProviderTest.providerExposesIpAxisRule` invokes `NearYouRuleSetProvider().instance(Config.empty()).rules`
- **THEN** the returned list contains exactly one `IpAxisMustUseTryAcquireByKeyRule` instance

### Requirement: Detekt test coverage for `IpAxisMustUseTryAcquireByKeyRule`

The `:lint:detekt-rules` module SHALL ship a Kotest test class `IpAxisMustUseTryAcquireByKeyLintTest` covering the following fixture scenarios. Each scenario is a Detekt-rule unit test exercising the rule against a single inline-Kotlin fixture string via the project's existing Detekt-test harness (mirror `OtelForbiddenAttributeLintTest` shipped under `observability-otel-foundation`).

1. **Positive — literal IP-axis key**: fixture `RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", 10, ttl)` in a non-test simulated path → exactly one finding.
2. **Positive — IP-derived UUID**: fixture `RateLimiter.tryAcquire(UUID.nameUUIDFromBytes(ip.toByteArray()), "{scope:foo}:{ip:abc123def4567890}", 10, ttl)` → exactly one finding.
3. **Positive — literal sentinel UUID**: fixture `RateLimiter.tryAcquire(UUID.fromString("00000000-0000-0000-0000-000000000000"), "{scope:foo}:{ip:abc123def4567890}", 10, ttl)` → exactly one finding.
4. **Positive — template-string IP value**: fixture `RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:${'$'}hashedIp}", 10, ttl)` → exactly one finding.
5. **Positive — named-argument syntax**: fixture `RateLimiter.tryAcquire(userId = u, key = "{scope:foo}:{ip:abc123def4567890}", capacity = 10, ttl = ttl)` → exactly one finding.
6. **Negative — tryAcquireByKey with IP key**: fixture `RateLimiter.tryAcquireByKey("{scope:health}:{ip:abc123def4567890}", 60, Duration.ofSeconds(60))` → zero findings.
7. **Negative — user-axis key**: fixture `RateLimiter.tryAcquire(userId, "{scope:rate_like_day}:{user:${'$'}userId}", 10, ttl)` → zero findings.
8. **Negative — non-IP non-user axis**: fixture `RateLimiter.tryAcquire(userId, "{scope:foo}:{geocell:abc}", 10, ttl)` → zero findings (scope intentionally narrow per Decision 3).
9. **Negative — Semaphore short-name collision**: fixture `semaphore.tryAcquire(1, TimeUnit.SECONDS)` → zero findings (argument-shape gate rejects).
10. **Allowlist — test-source path**: fixture identical to (1) BUT placed under simulated `/infra/redis/src/test/.../IpFixturesTest.kt` → zero findings (path allowlist suppresses).
11. **Composition — IP-axis rule + OtelForbiddenAttributeRule**: fixture `RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:1.2.3.4}", 10, ttl)` → produces TWO independent findings (one per rule), no cross-suppression. The test runs BOTH rules against the same fixture and asserts both finding-IDs appear in the output.
12. **Provider registration**: extend `NearYouRuleSetProviderTest` with a method `providerExposesIpAxisRule()` asserting `provider.instance(Config.empty()).rules.any { it is IpAxisMustUseTryAcquireByKeyRule }`. (This case lives in `NearYouRuleSetProviderTest`, not `IpAxisMustUseTryAcquireByKeyLintTest` — same precedent as the OTel rule's registration test.)

#### Scenario: Test class exists and all positive cases fire
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `IpAxisMustUseTryAcquireByKeyLintTest` is discovered AND every positive-case fixture (1, 2, 3, 4, 5) produces exactly one finding AND the finding's reported message includes the recommended-fix text mentioning `tryAcquireByKey`

#### Scenario: All negative cases pass
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** every negative-case fixture (6, 7, 8, 9) produces zero findings under `IpAxisMustUseTryAcquireByKeyRule`

#### Scenario: Test-path allowlist suppresses
- **WHEN** the fixture (1) is placed under a simulated `/src/test/` virtual file path AND the rule visits the file
- **THEN** zero findings are produced (the path-allowlist short-circuit fires before the per-call detection)

#### Scenario: Composition produces independent findings
- **WHEN** fixture (11) is run against BOTH `IpAxisMustUseTryAcquireByKeyRule` AND `OtelForbiddenAttributeRule` in the same Detekt invocation
- **THEN** the output contains exactly one finding from each rule (two findings total) AND neither rule suppresses the other AND the two finding-IDs (`IpAxisMustUseTryAcquireByKey` and `OtelForbiddenAttribute`) both appear in the captured findings list

#### Scenario: Provider registration test discovered
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `NearYouRuleSetProviderTest.providerExposesIpAxisRule` is discovered AND passes (the rule is exposed via `NearYouRuleSetProvider`)

#### Scenario: Project Detekt run includes the rule
- **WHEN** running `./gradlew detekt` against the full project codebase (post-implementation)
- **THEN** `IpAxisMustUseTryAcquireByKeyRule` is one of the executed rules AND no production source file produces a finding (this asserts the rule is active AND that no current production call site violates the contract; if a violation exists, it MUST be fixed before this change can ship)
