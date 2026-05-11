## Why

The `observability-otel-foundation` capability (shipped 2026-05-07, [PR #66](https://github.com/aditrioka/nearyou-id/pull/66)) defines a forbidden-attributes contract enumerating 9 categories of PII / secret-shaped values that MUST NOT appear in span attributes. Two layers of defense already ship:
1. **Runtime stripping** at the SDK pipeline via [`infra/otel/.../ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) for auto-instrumentation peer-identity attrs (the 11-entry `FORBIDDEN_KEYS` Set: 8 OTel HTTP / network peer-identity semconv keys + 3 user-id typo-defensive variants).
2. **Sentinel-string regression tests** covering the high-velocity categories at staging (post content, chat content, peer-IP suppression, raw-IP-in-Lua-key, bearer token, JWT claim, search query, Redis password).

The remaining categories rely on code review:
- The call-site half of every category (e.g., a developer typing `Span.setAttribute("user_id", ...)` instead of using the sanctioned `UserIdHasher.hash` consumption).
- Symmetric typo-defensive shapes for the network-semconv keys (e.g., `client_address` with underscore — a known typo-bypass vector).
- Canonical JWT-claim attribute keys (`jwt.sub`, `jwt.aud`, `jwt.iss`) the spec forbids but the runtime stripper doesn't enumerate.

A Detekt rule fires at every commit and locks the contract mechanically, sibling to `RawFromPostsRule`, `BlockExclusionJoinRule`, `RedisHashTagRule`, `RawXForwardedForRule`, `RateLimitTtlRule`, and `CoordinateJitterRule` (the six existing rules in the project Detekt ruleset).

Direct trigger: [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) item 4 (`observability-otel-attribute-detekt-rule`, last triaged 2026-05-10). The same entry's third action item folds in the deferred IP-axis enforcement from `rate-limit-ip-hashing` ([PR #74](https://github.com/aditrioka/nearyou-id/pull/74) round-1 N6): every Kotlin string literal containing `{ip:<value>}` should fire when `<value>` is not the canonical 16-hex `IpHasher.hash` output (or a Kotlin template-string placeholder the implementation cannot statically verify), forcing `IpHasher.hash` consumption at the call site rather than relying on reviewer attestation.

## What Changes

- Add `OtelForbiddenAttributeRule.kt` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` — a Detekt Rule that fires on:
  - **Tier 1 — forbidden attribute keys (21 entries total — `ForbiddenAttributeStripper.FORBIDDEN_KEYS` superset minus the `"user_id"` carve-out)**:
    - Group A (10 keys, `FORBIDDEN_KEYS` mirror minus `"user_id"`): 3 HTTP client-identity semconv keys (`client.address`, `client.port`, `http.client_ip`), 2 new-semconv peer keys (`network.peer.address`, `network.peer.port`), 3 old-semconv peer keys (`net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`), and 2 user-id typo-defensive variants (`user_uuid`, `user.uuid`). `"user_id"` is INTENTIONALLY EXCLUDED — it appears in ~12 production paths as SQL column names + `@SerialName` JSON keys + Ktor route parameters (semantically unrelated to OTel attributes); the runtime stripper continues to handle it defensively at export, and the integration-test sentinel scenario covers value-side leakage. See [`design.md`](design.md) § Decision 3 for the full rationale.
    - Group B (8 typo-defensive underscore variants for HTTP / network semconv keys): `client_address`, `client_port`, `http_client_ip`, `network_peer_address`, `network_peer_port`, `net_peer_ip`, `net_peer_port`, `net_sock_peer_addr`.
    - Group C (3 JWT-claim attribute keys): `jwt.sub`, `jwt.aud`, `jwt.iss` (per canonical spec § "Forbidden span attributes" bullet 5).
  - **Tier 2 — sensitive-value regex patterns (4 patterns)**: PEM private-key marker, JWT three-segment shape, Redis URI with userinfo, JWKS RSA JSON shape.
  - **Mode B — IP-axis value-shape**: any Kotlin string literal containing `{ip:<value>}` where `<value>` is neither (a) exactly 16 lowercase hex chars nor (b) a Kotlin template-string placeholder (`$<identifier>` OR `${<expression>}`). NOT scoped to `tryAcquireByKey` call-context — the production literal is hoisted to a `val` first, so PSI parent-walk would miss it (see `design.md` § Decision 5).
- Register `OtelForbiddenAttributeRule` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/NearYouRuleSetProvider.kt`.
- Ship `OtelForbiddenAttributeLintTest` covering ~30 fixture cases: each Tier 1 key triggers (one test per key for all 21) + one explicit positive-pass for the `"user_id"` carve-out, each Tier 2 pattern triggers (one test per pattern for all 4) + false-positive negative tests, IP-axis Mode B triggers on raw IPv4/IPv6 + off-canonical hex shapes, IP-axis Mode B passes on simple-name `$<identifier>` AND block-form `${<expression>}` interpolation, allowlist mechanisms work (broad `/src/test/` allowlist + `/infra/otel/src/main/` + `/lint/detekt-rules/src/main/` + `@AllowForbiddenSpanAttribute` annotation with `isNotBlank()` reason enforcement), composition with `RedisHashTagRule` + `CoordinateJitterRule` produces independent findings, NearYouRuleSetProvider registration, synchronization-guard test asserting Tier 1 Group A `containsAll (FORBIDDEN_KEYS - {"user_id"})`.
- MODIFY `observability-otel-foundation` spec — ADD requirements for the rule + allowlist + Detekt test coverage + post-merge `./gradlew detekt` green check; MODIFY the existing "Forbidden span attributes" requirement to reflect the new three-layer defense-in-depth posture (runtime stripping + compile-time lint + integration-test sentinel-string regression).
- MODIFY `rate-limit-infrastructure` spec — ADD requirement for IP-axis `{ip:<hashed>}` enforcement.
- Delete [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) entry 4 (`observability-otel-attribute-detekt-rule`) at archive time — its scope is fully fulfilled by this change. Add three new FOLLOW_UPS entries for the deliberately-deferred follow-ups (see `design.md` § "Explicitly deferred follow-ups"): `otel-attribute-rule-value-aware-userid-aliases`, `otel-attribute-rule-location-key-patterns`, `otel-attribute-rule-opaque-secrets`. The `tryacquirebykey-ip-derived-uuid-detekt-rule` entry (item 7) remains open as-is — it covers a different invariant (method choice, not key shape).

## Capabilities

### Modified Capabilities

- `observability-otel-foundation` — gains a lint-rule requirement that mechanically enforces an extended forbidden-attributes contract at every developer-written `Span.setAttribute(...)` / `withSpan(name, mapOf(...))` / `AttributesBuilder.put(...)` call site, plus a value-axis regex pattern enforcement. The MODIFIED requirement on "Forbidden span attributes" updates the closing prose from "code-review-only" to a three-layer defense-in-depth statement.
- `rate-limit-infrastructure` — gains a lint-rule requirement that the IP-axis `{ip:<value>}` shape MUST be either canonical 16-hex `IpHasher.hash` output OR a Kotlin template-string placeholder, with no call-site-context restriction. Covers any literal anywhere in non-allowlisted Kotlin sources.

## Impact

- **Code**: ~300 LOC for `OtelForbiddenAttributeRule.kt` + KDoc; ~600 LOC for `OtelForbiddenAttributeLintTest` covering ~30 fixture cases; 1-line update to `NearYouRuleSetProvider` to register the rule; 1-line addition to the project Detekt config applied cross-cuttingly; 1-line `testImplementation(project(":infra:otel"))` addition in `lint/detekt-rules/build.gradle.kts` for the synchronization-guard test (OR hardcoded-list-with-comment if the build-graph dependency is undesirable).
- **Schema / APIs / Dependencies**: none.
- **Out of scope (explicit)**:
  - Runtime span-attribute stripping. Already shipped via [`ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) — the lint rule complements rather than replaces it.
  - Runtime-constructed values (string concat with variables, secrets fetched from `SecretResolver`). The rule scans Kotlin string literals via Detekt PSI; runtime construction is not visible — `ForbiddenAttributeStripper` remains the runtime backstop.
  - Mobile-side enforcement. Out of scope until the mobile telemetry path lands.
  - SQL migration file scanning. Detekt visits Kotlin PSI; `.sql` files reviewed in PR.
  - The `tryAcquireByKey`-vs-`tryAcquire` method-choice invariant ([`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) item 7) — separate rule, separate semantics; stays open.
  - Value-aware detection of raw user_id under generic-named keys (`principal`, `actor`, `subject`, `owner`). Canonical spec mentions these but key-name match alone would false-positive on legitimate auth-domain code. Deferred to follow-up `otel-attribute-rule-value-aware-userid-aliases` (logged at archive).
  - `*location*` / `*lat*` / `*lng*` / `*coord*` key-name patterns. Substring match conflicts with `display_location` (sanctioned) and overlaps with `CoordinateJitterRule`. Deferred to follow-up `otel-attribute-rule-location-key-patterns` (logged at archive).
  - Tier 2 value-shape patterns for opaque secrets (OAuth `client_secret`, raw refresh tokens, plaintext passwords). These are opaque strings without distinguishing markers; code review remains the canonical defense. Deferred to follow-up `otel-attribute-rule-opaque-secrets` (logged at archive).
  - Backfill of `@AllowForbiddenSpanAttribute("<reason>")` annotations onto existing test fixtures. The broad `/src/test/` path allowlist covers all current call sites; the annotation exists as a future escape hatch for new admin tools that legitimately need an OTel attribute the rule blocks.
