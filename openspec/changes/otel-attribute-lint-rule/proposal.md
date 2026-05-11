## Why

The `observability-otel-foundation` capability (shipped 2026-05-07, [PR #66](https://github.com/aditrioka/nearyou-id/pull/66)) defines a forbidden-attributes contract enumerating 9 categories of PII / secret-shaped values that MUST NOT appear in span attributes (raw user_id, raw client IP, OTel HTTP/network peer-identity semconv keys, raw JWT/refresh/bearer tokens + OAuth client secret + JWKS + Supabase service-role key, raw JWT claims, raw `actual_location` GIS coordinates, raw post/chat/search content, plaintext passwords, raw Redis cluster credentials). Two layers of defense already ship:
1. **Runtime stripping** at the SDK pipeline via [`infra/otel/.../ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) for auto-instrumentation peer-identity attrs (8 OTel HTTP/network semconv keys + 3 user-id typo-defensive variants; 11 keys total in the live `FORBIDDEN_KEYS` Set).
2. **Sentinel-string regression tests** covering approximately 7 categories (post content, chat content, peer-IP suppression, raw-IP-in-Lua-key, bearer token, JWT claim, search query, Redis password).

The remaining categories (OAuth client secrets, JWKS contents, plaintext passwords, raw `actual_location` GIS coordinates, raw refresh tokens) plus the call-site half of every category rely on code review. A Detekt rule fires at every commit and locks the contract mechanically, sibling to `RawFromPostsRule`, `BlockExclusionJoinRule`, `RedisHashTagRule`, `RawXForwardedForRule`, `RateLimitTtlRule`, and `CoordinateJitterRule` (the six existing rules in the project Detekt ruleset).

Direct trigger: [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) item 4 (`observability-otel-attribute-detekt-rule`, last triaged 2026-05-10). The same entry's third action item folds in the deferred IP-axis enforcement from `rate-limit-ip-hashing` ([PR #74](https://github.com/aditrioka/nearyou-id/pull/74) round-1 finding N6): every `tryAcquireByKey(...)` call site whose key literal contains `{ip:<value>}` where `<value>` is not a 16-hex-character SHA-256 hash should fire the rule, forcing `IpHasher.hash` consumption at the call site rather than relying on reviewer attestation.

## What Changes

- Add `OtelForbiddenAttributeRule.kt` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` — a Detekt Rule that fires on:
  - Any Kotlin string literal exactly matching one of the 11 Tier 1 forbidden-attribute keys, mirroring `ForbiddenAttributeStripper.FORBIDDEN_KEYS` exactly (8 OTel HTTP/network peer-identity semconv keys: `client.address`, `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr` + 3 user-id typo-defensive variants: `user_id`, `user_uuid`, `user.uuid`).
  - Any Kotlin string literal matching one of the Tier 2 sensitive-value regex patterns (RSA / EC / Ed25519 PEM private key marker; JWT `eyJ...eyJ....` three-segment shape; Redis URI with explicit userinfo `redis://user:pass@...`).
- Extend the same rule (single Detekt class, single enable/disable surface) to fire on `RateLimiter.tryAcquireByKey(...)` calls whose `key` argument matches `\{ip:[^h{][^}]*` — i.e., the value inside `{ip:...}` is not 16 hex characters (the canonical `IpHasher.hash` output shape).
- Register `OtelForbiddenAttributeRule` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/NearYouRuleSetProvider.kt`.
- Ship `OtelForbiddenAttributeLintTest` covering ~20 fixture cases: each forbidden-key category triggers; each sensitive-value-regex category triggers; IP-axis enforcement triggers on `{ip:1.2.3.4}` (and IPv6 literal) and passes on `{ip:abcdef0123456789}`; allowlist mechanisms work (file-path allowlist for `:infra:otel`'s own sentinel-test fixtures + `@AllowForbiddenSpanAttribute("<reason>")` annotation bypass); the rule does NOT fire on `UserIdHasher.hash(userId)` / `IpHasher.hash(ip)` consumption (those are the sanctioned helpers).
- MODIFY `observability-otel-foundation` spec — ADD requirement "`OtelForbiddenAttributeRule` mechanically enforces the forbidden-attribute contract at the call site" with scenarios for representative key + value triggers, annotation bypass, and composition with the runtime `ForbiddenAttributeStripper`.
- MODIFY `rate-limit-infrastructure` spec — ADD requirement "`OtelForbiddenAttributeRule` enforces the `{ip:<hashed>}` invariant at every `tryAcquireByKey(...)` call site" with scenarios for hashed-pass / raw-IPv4-fail / IPv6-literal-fail / non-IP-axis-no-op.
- Delete [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) entry 4 (`observability-otel-attribute-detekt-rule`) at archive time — its scope is fully fulfilled by this change. The `tryacquirebykey-ip-derived-uuid-detekt-rule` entry (item 7) remains open — it covers a different invariant (method choice, not key shape) and is not folded in here.

## Capabilities

### Modified Capabilities

- `observability-otel-foundation` — gains a lint-rule requirement that mechanically enforces the existing "Forbidden span attributes" contract at every `Span.setAttribute(...)` / `Span.addEvent(...)` / `AttributesBuilder.put(...)` call site.
- `rate-limit-infrastructure` — gains a lint-rule requirement that the IP-axis `{ip:<value>}` shape is enforced (value MUST be 16-hex SHA-256 hash from `IpHasher.hash`) at every `tryAcquireByKey(...)` call site.

## Impact

- **Code**: ~250 LOC for `OtelForbiddenAttributeRule.kt` + KDoc; ~400 LOC for `OtelForbiddenAttributeLintTest` covering ~20 fixture cases; 1-line update to `NearYouRuleSetProvider` to register the rule; 1-line addition to the project Detekt config applied to `:backend:ktor`.
- **Schema / APIs / Dependencies**: none.
- **Out of scope (explicit)**:
  - Runtime span-attribute stripping. Already shipped via [`ForbiddenAttributeStripper.kt`](../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt) — the lint rule complements rather than replaces it. The runtime stripper handles auto-instrumentation attrs the dev didn't write; the lint rule handles manual call sites the dev did write.
  - Runtime-constructed values (string concat with variables, secrets fetched from `SecretResolver`). The rule scans Kotlin string literals via Detekt PSI; runtime construction is not visible — `ForbiddenAttributeStripper` remains the runtime backstop.
  - Mobile-side enforcement. Sentry KMP / Amplitude attribute calls on `:mobile:app` are out of scope until the mobile telemetry path lands.
  - SQL migration file scanning. Same rationale as `CoordinateJitterRule`: Detekt visits Kotlin PSI; `.sql` files are reviewed in PR.
  - The `tryAcquireByKey`-vs-`tryAcquire` method-choice invariant ([`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) item 7 `tryacquirebykey-ip-derived-uuid-detekt-rule`). That's a separate rule with separate semantics; it remains an open follow-up.
  - Backfill of `@AllowForbiddenSpanAttribute("<reason>")` annotations onto existing test fixtures. The allowlist-by-path covers the current call sites; the annotation exists as a future escape hatch.
