## ADDED Requirements

### Requirement: `OtelForbiddenAttributeRule` fences raw IP literal in `{ip:<value>}` rate-limit-key segments

The `OtelForbiddenAttributeRule` Detekt rule (shipped under the `observability-otel-foundation` capability — see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "`OtelForbiddenAttributeRule` fences forbidden span-attribute writes") SHALL ALSO fire on any Kotlin string literal containing an `{ip:<value>}` segment where `<value>` is none of: (a) exactly 16 lowercase hexadecimal characters (the canonical `IpHasher.hash` output shape), (b) a Kotlin template-string placeholder — simple-name `$<identifier>` OR block-form `${<expression>}` — whose runtime value the implementation cannot statically verify.

**The check is NOT scoped to `tryAcquireByKey(...)` call-context.** Rationale: the canonical production call site at [`backend/ktor/.../health/HealthRoutes.kt:166-170`](../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt) hoists the literal `val key = "{scope:health}:{ip:$hashedIp}"` BEFORE passing it to `tryAcquireByKey`. The string-template-expression's PSI parent is `KtProperty`, NOT `KtCallExpression(tryAcquireByKey)`. A PSI parent-walk that requires `tryAcquireByKey` as the immediate enclosing call would produce ZERO findings against the real production codebase, defeating the rule's purpose. Firing on any `{ip:<value>}` literal anywhere is the correct enforcement boundary; the path-based allowlist (next requirement) handles test fixtures that legitimately need raw inputs.

**Functional contract** (the authoritative spec; implementation phase selects an equivalent regex / PSI logic):
1. **PASS** — `{ip:[0-9a-f]{16}}` (exactly 16 lowercase hex chars between the braces).
2. **PASS** — `{ip:$<identifier>}` (Kotlin simple-name template — the canonical production shape, e.g., `$hashedIp`).
3. **PASS** — `{ip:${<expression>}}` (Kotlin block-form template — e.g., `${IpHasher.hash(clientIp)}`).
4. **FIRE** — anything else: raw IPv4 dotted-quad (`{ip:1.2.3.4}`), IPv6 colon-delimited (`{ip:[2001:db8::1]}`), 15-hex / 17-hex / uppercase-hex / mixed-case / shapes containing colons or dots inside the value.

**Recommended regex** (illustrative; final shape selected at implementation time): `\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}` — fires when the value at the position after `{ip:` is NEITHER exactly 16 lowercase hex chars followed by `}` (negative lookahead 1, handling clause 1) NOR begins with `$` (negative lookahead 2, handling clauses 2 AND 3 — `$<identifier>` and `${<expression>}` both start with `$`).

#### Scenario: Raw IPv4 literal anywhere fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:1.2.3.4}"` — bound to a `val`, passed as a method arg, embedded in a log message, anywhere
- **THEN** the rule fires on that literal

#### Scenario: Raw IPv6 literal anywhere fires
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:[2001:db8::1]}"`
- **THEN** the rule fires

#### Scenario: Canonical 16-hex literal passes
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef0123456789}"`
- **THEN** the rule does NOT fire on that literal

#### Scenario: Kotlin simple-name interpolation passes (canonical production shape)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:${'$'}hashedIp}"` where `hashedIp` is a Kotlin identifier — the canonical production shape at `HealthRoutes.kt:167`
- **THEN** the rule does NOT fire (the value between `{ip:` and `}` begins with `$` — passes per the functional contract's clause (2); the implementation cannot statically verify the identifier's runtime value, so trust the caller)

#### Scenario: Kotlin block-form interpolation passes
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"` (block-form template with the helper consumption inline)
- **THEN** the rule does NOT fire (the value between `{ip:` and `}` begins with `${'$'}{` — passes per the functional contract's clause (3))

#### Scenario: 15-hex value fires (off-canonical length)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef012345678}"` (15 hex chars — one short of canonical)
- **THEN** the rule fires (the canonical shape is EXACTLY 16 hex chars)

#### Scenario: 17-hex value fires (off-canonical length)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:abcdef01234567890}"` (17 hex chars)
- **THEN** the rule fires

#### Scenario: Uppercase-hex 16-char value fires (off-canonical case)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:health}:{ip:ABCDEF0123456789}"`
- **THEN** the rule fires (canonical `IpHasher.hash` output is lowercase; uppercase indicates a different source)

#### Scenario: Non-IP-axis key passes (no-op for unrelated axes)
- **WHEN** a non-allowlisted Kotlin file contains the literal `"{scope:rate_like_day}:{user:${'$'}userId}"` — no `{ip:...}` segment present
- **THEN** the rule's IP-axis check does NOT fire on that literal

#### Scenario: Test-source allowlist applies
- **WHEN** a file under any `/src/test/` path (e.g., `infra/redis/src/test/kotlin/.../RedisRateLimiterTelemetryTest.kt`, `core/domain/src/test/kotlin/.../RateLimiterTest.kt`, `backend/ktor/src/test/kotlin/.../HealthRoutesScenariosTest.kt`) contains a fixture string `"{scope:health}:{ip:1.2.3.4}"` to verify the limiter's behavior on raw inputs OR a regex-string asserting the canonical shape `"""^\{scope:health\}:\{ip:[0-9a-f]{16}\}${'$'}"""`
- **THEN** the rule does NOT fire on those literals (the `/src/test/` path allowlist suppresses; tests of the limiter and the canonical-shape assertion legitimately need raw / regex-literal inputs)

### Requirement: Detekt test coverage for the IP-axis lint mode

The `OtelForbiddenAttributeLintTest` class shipped under the `observability-otel-foundation` capability (see [`observability-otel-foundation/spec.md`](../observability-otel-foundation/spec.md) § "Detekt test coverage for `OtelForbiddenAttributeRule`") SHALL include, in addition to the OTel attribute scenarios, the following IP-axis-mode test cases:

1. **Raw IPv4 anywhere fires**: fixture `val k = "{scope:health}:{ip:1.2.3.4}"` in a non-`/src/test/` path → rule fires.
2. **Raw IPv6 anywhere fires**: fixture with `{ip:[2001:db8::1]}` literal in non-test path → rule fires.
3. **Canonical 16-hex passes**: fixture with `{ip:abcdef0123456789}` literal → rule does NOT fire.
4. **Simple-name interpolation passes (canonical prod shape)**: fixture `val k = "{scope:health}:{ip:${'$'}hashedIp}"` → rule does NOT fire.
5. **Block-form interpolation passes**: fixture with `{ip:${'$'}{IpHasher.hash(clientIp)}}` → rule does NOT fire.
6. **15-hex fires**: fixture with `{ip:abcdef012345678}` → rule fires.
7. **17-hex fires**: fixture with `{ip:abcdef01234567890}` → rule fires.
8. **Uppercase-hex fires**: fixture with `{ip:ABCDEF0123456789}` → rule fires.
9. **Non-IP-axis (user-axis) passes**: fixture with `{scope:rate_like_day}:{user:${'$'}userId}` (no `{ip:...}` segment) → no fire on IP-axis check.
10. **Test-path allowlist for `/src/test/`**: fixture under simulated `/infra/redis/src/test/.../IpFixturesTest.kt` with raw `{ip:1.2.3.4}` → rule does NOT fire.
11. **Composition with `RedisHashTagRule` two-way**: a fixture `"rate:user:${'$'}userId"` (legacy non-hash-tagged, NO `{ip:...}` segment) → fires `RedisHashTagRule` but NOT IP-axis check; a fixture `"rate:health:{ip:1.2.3.4}"` (legacy prefix AND raw IP) → fires BOTH rules independently with no cross-suppression.
12. **Mode B fires on val-hoisted literal (not call-context-restricted)**: fixture `val k = "{scope:health}:{ip:1.2.3.4}"` (literal bound to a property, never passed anywhere) → rule fires (rule is NOT scoped to `tryAcquireByKey` call-context; firing on any `{ip:...}` literal is the canonical behavior per the functional contract above).

#### Scenario: Test class exists and IP-axis cases pass
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `OtelForbiddenAttributeLintTest` is discovered AND every IP-axis-mode test case above is covered AND all cases pass
