## ADDED Requirements

### Requirement: `OtelForbiddenAttributeRule` fences raw IP literal in `{ip:<value>}` rate-limit-key segments

The `OtelForbiddenAttributeRule` Detekt rule (shipped under the `observability-otel-foundation` capability — see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes") SHALL ALSO fire on any `RateLimiter.tryAcquireByKey(...)` call expression whose first argument resolves to a Kotlin string literal containing an `{ip:<value>}` segment where `<value>` is neither (a) exactly 16 lowercase hexadecimal characters — the canonical `IpHasher.hash` output shape — nor (b) a Kotlin template-string placeholder (`${...}`) that the implementation cannot statically resolve.

**Functional contract** (the authoritative spec; implementation phase selects an equivalent regex / PSI logic):
1. **PASS** — `{ip:[0-9a-f]{16}}` (exactly 16 lowercase hex chars between the braces).
2. **PASS** — `{ip:${<placeholder>}}` (the value between the braces is a Kotlin string-template placeholder; the implementation cannot statically verify the placeholder's runtime value, so trust the caller).
3. **FIRE** — anything else: raw IPv4 dotted-quad (`{ip:1.2.3.4}`), IPv6 colon-delimited (`{ip:[2001:db8::1]}`), 15-hex / 17-hex / uppercase-hex / mixed-case / other-axis-confusing shapes.

**Recommended regex** (illustrative; final shape selected at implementation time): `\{ip:(?![0-9a-f]{16}\})(?!\$\{)[^}]*\}` — fires when the value at the position after `{ip:` is NEITHER exactly 16 lowercase hex chars followed by `}` (negative lookahead 1) NOR the start of a Kotlin template placeholder `${` (negative lookahead 2). An equivalent PSI-aware implementation that walks `KtStringTemplateEntry` children to detect `KtSimpleNameStringTemplateEntry` / `KtBlockStringTemplateEntry` and skips the IP-axis check when interpolation is present inside the `{ip:...}` segment is also acceptable.

This enforcement complements the existing posture-level requirement at [`rate-limit-infrastructure/spec.md`](../rate-limit-infrastructure/spec.md) § "Hash-tag key format standard for rate-limit keys" (which already specifies that the IP-axis value MUST be `IpHasher.hash(clientIp)` output) by adding compile-time enforcement at the call site, rather than relying on reviewer attestation + telemetry-side sentinel-string regression tests.

The rule MUST honor the same allowlist as the OTel forbidden-attribute mode (see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Allowlist for `OtelForbiddenAttributeRule`"): `:infra:otel` source path, `:lint:detekt-rules` source path, `@AllowForbiddenSpanAttribute("<reason>")` annotation with non-empty reason. There is no separate annotation for the IP-axis mode — one rule, one allowlist surface.

#### Scenario: Raw IPv4 literal in `tryAcquireByKey` key argument fires
- **WHEN** a non-allowlisted Kotlin file contains `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))`
- **THEN** the rule reports a code smell on the `"{scope:health}:{ip:1.2.3.4}"` literal

#### Scenario: Raw IPv6 literal in `tryAcquireByKey` key argument fires
- **WHEN** a non-allowlisted Kotlin file contains `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:[2001:db8::1]}", capacity = 60, ttl = Duration.ofSeconds(60))`
- **THEN** the rule fires

#### Scenario: Canonical 16-hex `IpHasher.hash` output in key argument passes
- **WHEN** a non-allowlisted Kotlin file contains `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:abcdef0123456789}", capacity = 60, ttl = Duration.ofSeconds(60))`
- **THEN** the rule does NOT fire on that literal

#### Scenario: `IpHasher.hash(clientIp)` consumption in interpolated key passes
- **WHEN** a non-allowlisted Kotlin file contains `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}", capacity = 60, ttl = Duration.ofSeconds(60))` (Kotlin template string with the helper consumption inline)
- **THEN** the rule does NOT fire (the value between `{ip:` and `}` is a Kotlin template-string placeholder `${'$'}{IpHasher.hash(clientIp)}` — passes per the functional contract's clause (b); the implementation cannot statically verify the placeholder's runtime value, so trust the caller)

#### Scenario: Non-IP-axis key passes (no-op for unrelated axes)
- **WHEN** a non-allowlisted Kotlin file contains `rateLimiter.tryAcquire(userId, key = "{scope:rate_like_day}:{user:${'$'}userId}", capacity = 10, ttl = Duration.ofSeconds(60))`
- **THEN** the rule does NOT fire (the key does not contain an `{ip:...}` segment; the IP-axis enforcement is no-op on user-axis or other-axis keys)

#### Scenario: `{ip:...}` literal outside `tryAcquireByKey` call context does NOT fire on the IP-axis check
- **WHEN** a non-allowlisted Kotlin file contains `val logKey = "{ip:1.2.3.4}"` (not passed to `tryAcquireByKey`)
- **THEN** the rule's IP-axis mode does NOT fire on that literal (the IP-axis check is scoped to `tryAcquireByKey(...)` call-context per Decision 1 in design.md; the literal `1.2.3.4` separately matches no Tier 1 / Tier 2 OTel forbidden pattern)

#### Scenario: Allowlist by path applies (`:infra:otel` test sources)
- **WHEN** a file under `/infra/otel/src/test/kotlin/.../IpHasherTest.kt` contains a fixture `rateLimiter.tryAcquireByKey(key = "{scope:test}:{ip:1.2.3.4}", ...)` to verify hashing
- **THEN** the rule does NOT fire on that literal (the path allowlist suppresses; tests of the hasher itself legitimately need raw inputs)

### Requirement: Detekt test coverage for the IP-axis lint mode

The `OtelForbiddenAttributeLintTest` class shipped under the `observability-otel-foundation` capability (see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Detekt test coverage for `OtelForbiddenAttributeRule`") SHALL include, in addition to the OTel attribute scenarios, the following IP-axis-mode test cases:

1. **Raw IPv4 in `tryAcquireByKey` key fires**: fixture `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))` → rule fires.
2. **Raw IPv6 in `tryAcquireByKey` key fires**: fixture with `{ip:[2001:db8::1]}` literal → rule fires.
3. **Canonical 16-hex passes**: fixture with `{ip:abcdef0123456789}` literal → rule does NOT fire.
4. **`IpHasher.hash(clientIp)` interpolated form passes**: fixture with `${'$'}{IpHasher.hash(clientIp)}` placeholder inside the `{ip:...}` segment → rule does NOT fire.
5. **Non-IP-axis call passes**: fixture with `tryAcquire(userId, "{scope:rate_like_day}:{user:${'$'}userId}", ...)` → no IP-axis-mode fire.
6. **Non-call-context `{ip:...}` literal**: fixture with `val s = "{ip:1.2.3.4}"` (not passed to a rate-limit method) → IP-axis-mode does NOT fire (the rule is call-context-scoped per Decision 1 in design.md).
7. **Path allowlist for `:infra:otel` test fixtures**: fixture under simulated `/infra/otel/src/test/.../IpHasherTest.kt` path with raw `{ip:1.2.3.4}` → rule does NOT fire.
8. **15-hex (not 16) fires**: fixture with `{ip:abcdef012345678}` (15 hex chars) → rule fires (the canonical shape is exactly 16 hex).
9. **17-hex fires**: fixture with `{ip:abcdef01234567890}` (17 hex chars) → rule fires.
10. **Uppercase-hex fires**: fixture with `{ip:ABCDEF0123456789}` → rule fires (the canonical shape is lowercase; mirrors `IpHasher.hash` output).
11. **Composition with `RedisHashTagRule`**: a fixture with `"rate:user:${'$'}userId"` (legacy non-hash-tagged form, NO `{ip:...}` segment) → fires `RedisHashTagRule` (the existing rule) but does NOT fire the new IP-axis mode (separate concerns, no double-fire).

#### Scenario: Test class exists and IP-axis cases pass
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every IP-axis-mode test case above is covered AND all cases pass
