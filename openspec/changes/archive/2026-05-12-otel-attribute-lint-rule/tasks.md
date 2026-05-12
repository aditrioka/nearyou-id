# Tasks — `otel-attribute-lint-rule`

## 1. Rule implementation

- [x] 1.1 Create `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`. Model shape after `RawXForwardedForRule` / `CoordinateJitterRule` (`visitStringTemplateExpression` + path allowlist + `@AllowForbiddenSpanAttribute` annotation walk). Issue `Severity.Defect`, `Debt.TEN_MINS` (mirrors existing rules).
- [x] 1.2 Mode A — Tier 1 forbidden-attribute keys: regex match (preferably `Set<String>` exact-match) the 21 entries listed in `specs/observability-otel-foundation/spec.md` § "Tier 1 — forbidden-attribute-key literals" (10 Group A + 8 Group B + 3 Group C). `"user_id"` is INTENTIONALLY EXCLUDED from Group A — see `design.md` § Decision 3 for the carve-out rationale.
- [x] 1.3 Mode A — Tier 2 sensitive-value regex patterns: 4 regex constants per `specs/observability-otel-foundation/spec.md` § "Tier 2 — sensitive-value regex patterns" (PEM private-key marker; JWT three-segment shape; Redis URI with userinfo; JWKS RSA-key JSON shape).
- [x] 1.4 Mode B — IP-axis value-shape check (NO call-site context restriction): apply the regex `\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}` (recommended) OR an equivalent PSI-aware check on every `KtStringTemplateExpression` regardless of enclosing call context. The literal is hoisted to `val key` at `HealthRoutes.kt:166` BEFORE being passed to `tryAcquireByKey`, so any call-site-context restriction would produce zero findings against production code (see `design.md` § Decision 5). Fires on raw IPv4/IPv6/15-hex/17-hex/uppercase-hex; passes on canonical 16-hex AND Kotlin template placeholders (simple-name `$<identifier>` AND block-form `${<expression>}`).
- [x] 1.5 Path allowlist (broader than initial proposal — see `design.md` § Decision 6): file `virtualFilePath` substring check for `/src/test/` (broad, mirrors `RedisHashTagRule`), `/infra/otel/src/main/`, AND `/lint/detekt-rules/src/main/`. Synthetic-file-harness fallback via package-FQN check (`id.nearyou.lint.detekt.*`).
- [x] 1.6 Annotation bypass: walk `KtAnnotated` ancestors looking for `@AllowForbiddenSpanAttribute`; require `isNotBlank()` reason (mirror `RedisHashTagRule.kt:123-139` precedent — covers empty, whitespace-only, tabs, newlines).
- [x] 1.7 Register `OtelForbiddenAttributeRule(config)` in `NearYouRuleSetProvider.instance(config)` alongside the 6 existing rules.
- [x] 1.8 KDoc header: explain the two enforcement modes (Mode A — Tier 1 + Tier 2 anywhere; Mode B — IP-axis value-shape anywhere with NO call-site-context restriction), the path + annotation allowlists, the relationship to the runtime `ForbiddenAttributeStripper` (complementary defense-in-depth), and an explicit "why dynamic-key construction is NOT in scope" paragraph + "why value-aware userid-alias detection is deferred" paragraph.

## 2. Rule tests

- [x] 2.1 Create `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt`. Model shape after `RawXForwardedForRuleTest` / `RedisHashTagRuleTest` (`StringSpec` + `rule.lint(code)` / `rule.lint(path)` + `cleanupDir` helper).
- [x] 2.2 Tier 1 Group A positive-fail cases (10 tests): one per Group A entry — the 3 HTTP client-identity keys (`client.address`, `client.port`, `http.client_ip`), 2 new-semconv peer keys (`network.peer.address`, `network.peer.port`), 3 old-semconv peer keys (`net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`), and 2 user-id typo variants (`user_uuid`, `user.uuid`). PLUS one explicit positive-pass test: `rs.getObject("user_id", UUID::class.java)` (the carve-out) → rule does NOT fire on `"user_id"` literal.
- [x] 2.3 Tier 1 Group B positive-fail cases (8 tests): one per underscore typo-defensive variant (`client_address`, `client_port`, `http_client_ip`, `network_peer_address`, `network_peer_port`, `net_peer_ip`, `net_peer_port`, `net_sock_peer_addr`).
- [x] 2.4 Tier 1 Group C positive-fail cases (3 tests): `jwt.sub`, `jwt.aud`, `jwt.iss` each fires from a non-allowlisted path.
- [x] 2.5 Tier 2 positive-fail cases (4 tests): PEM marker (`-----BEGIN RSA PRIVATE KEY-----`); JWT three-segment shape (`eyJhbGc...eyJzdWI....sig`); Redis URI with userinfo (`redis://default:pw@host:6379/0`); JWKS RSA JSON (`{"kty":"RSA","n":"...","e":"..."}`).
- [x] 2.6 Tier 2 false-positive negative tests (3 tests): `"eyJfoo"` alone (single segment) → no fire; `"redis://host:6379/0"` (no userinfo) → no fire; `"-----BEGIN PUBLIC KEY-----"` (PUBLIC not PRIVATE) → no fire.
- [x] 2.7 Sanctioned `UserIdHasher.hash` consumption positive-pass: `span.setAttribute("user.id", UserIdHasher.hash(userId))` → no fire (the literal `"user.id"` is NOT in Tier 1).
- [x] 2.8 IP-axis Mode B positive-fail: `val k = "{scope:health}:{ip:1.2.3.4}"` (literal hoisted to val, never passed anywhere) → fires (no call-site restriction).
- [x] 2.9 IP-axis Mode B IPv6 positive-fail: `{ip:[2001:db8::1]}` literal anywhere → fires.
- [x] 2.10 IP-axis Mode B canonical positive-pass: `{ip:abcdef0123456789}` (16-hex lowercase) → no fire.
- [x] 2.11 IP-axis Mode B simple-name interpolation positive-pass (canonical prod shape): `val k = "{scope:health}:{ip:${'$'}hashedIp}"` → no fire (mirrors `HealthRoutes.kt:167`).
- [x] 2.12 IP-axis Mode B block-form interpolation positive-pass: `"{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"` → no fire.
- [x] 2.13 IP-axis Mode B off-canonical hex positive-fail (3 tests): 15-hex (`{ip:abcdef012345678}`) → fires; 17-hex (`{ip:abcdef01234567890}`) → fires; uppercase 16-hex (`{ip:ABCDEF0123456789}`) → fires.
- [x] 2.14 IP-axis Mode B no-op on non-IP-axis key: `"{scope:rate_like_day}:{user:${'$'}userId}"` (no `{ip:...}` segment) → no fire on IP-axis check.
- [x] 2.15 Annotation bypass non-empty reason positive-pass: `@AllowForbiddenSpanAttribute("admin span exempt — design Decision N")` on function → does NOT fire on `"client.address"` inside.
- [x] 2.16 Annotation bypass on enclosing class positive-pass: `@AllowForbiddenSpanAttribute("escape hatch")` on class → does NOT fire on `"net.peer.ip"` in nested function.
- [x] 2.17 Annotation bypass empty-reason still fires (3 tests): `@AllowForbiddenSpanAttribute("")` → fires; `@AllowForbiddenSpanAttribute("   ")` (whitespace-only) → fires; `@AllowForbiddenSpanAttribute("\t\n")` (other whitespace) → fires. All mirror `isNotBlank()` precedent from `RedisHashTagRule.kt:134` (rule additionally evaluates `\t` / `\n` / `\r` escape sequences before `isNotBlank()` so source-text `"\t"` collapses to a tab).
- [x] 2.18 Annotation single-non-blank-char positive-pass: `@AllowForbiddenSpanAttribute("x")` → does NOT fire (non-blank is sufficient; the rule's job is to require a reason exists, not to assess its quality).
- [x] 2.19 Path allowlist tests (4 tests): file under `/src/test/` (any path containing it) → no fire on Tier 1 / Tier 2 / IP-axis literals; file under `/infra/otel/src/main/` → no fire; file under `/lint/detekt-rules/src/main/` → no fire; arbitrary `/backend/ktor/src/main/` path → fires on the same literals.
- [x] 2.20 Synthetic-file-harness package-FQN fallback positive-pass: package `id.nearyou.lint.detekt.fixtures.Allowed` (synthetic, no `virtualFilePath`) → treated as allowlisted source.
- [x] 2.21 Composition with `CoordinateJitterRule`: a fixture with both `"actual_location"` AND `"client.address"` produces exactly 2 findings (1 per rule), no cross-suppression.
- [x] 2.22 Composition with `RedisHashTagRule` two-way: a fixture `"rate:user:${'$'}userId"` (legacy non-hash-tagged, NO `{ip:...}`) → fires `RedisHashTagRule` but NOT IP-axis mode; a fixture `"rate:health:{ip:1.2.3.4}"` (legacy prefix AND raw IP) → fires BOTH rules independently with no cross-suppression.
- [x] 2.23 Unrelated string literal positive-pass: `"Processing request"` / `"INSERT INTO posts ..."` / `"SELECT * FROM users WHERE id = ?"` → no fire.
- [x] 2.24 NearYouRuleSetProvider registration positive-pass: explicit fixture asserting the rule appears in `NearYouRuleSetProvider.instance(config)`'s returned `RuleSet` (mirrors `RedisHashTagRuleTest`'s registration test).
- [x] 2.25 Synchronization guard test: assert the rule's Tier 1 Group A list `containsAll (ForbiddenAttributeStripper.FORBIDDEN_KEYS - setOf("user_id"))` — superset relationship with one documented carve-out. Test failure message MUST name both any missing key AND the carve-out rationale, so the next contributor adding to `FORBIDDEN_KEYS` knows whether the new key belongs in Tier 1 or is a similar carve-out case. Implementation chose option (b) — hardcoded snapshot in test fixture with a comment pointing at `ForbiddenAttributeStripper.kt:89-108`. Rationale: `:lint:detekt-rules` targets JVM 17 (Detekt 1.23.x constraint) while `:infra:otel` targets JVM 21 (project-wide toolchain) — adding `testImplementation(project(":infra:otel"))` would mix class-file versions in the test classpath. Option (b) keeps the build self-contained.
- [x] 2.26 `./gradlew :lint:detekt-rules:test` green. 58 tests passed in `OtelForbiddenAttributeLintTest` (152 tests total across the lint module's test suite).

## 3. Verify no regressions on the backend

- [x] 3.1 `./gradlew detekt` green on `:backend:ktor:src/main/kotlin` (the only source set with detekt registered per `build-logic/.../nearyou.ktor.gradle.kts:10-21`) with `OtelForbiddenAttributeRule` active — 0 code smells. Today's writer surfaces (enumerated below) pass by construction. NOTE on scope: `./gradlew detekt` resolves only to `:backend:ktor:detekt` in this project; `:infra:*` and `:lint:detekt-rules` modules do not have detekt registered. The path-based allowlist (`/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`) is a safety net for any future expansion of detekt's source-set coverage and for the synchronization-guard scenarios in the lint tests.
- [x] 3.2 Zero call-site behavior changes were required. Every pre-existing writer literal in `:backend:ktor:src/main/kotlin` passes by construction — verified by the successful detekt run.
- [x] 3.3 Production writer surface audit (grep `setAttribute\|withSpan\|addEvent\|AttributesBuilder\|tryAcquireByKey` in `backend/` + `infra/`). All hits in non-test paths confirmed to pass either by canonical helper consumption OR by path-allowlist OR by safe-key choice:
  - `backend/ktor/src/main/.../auth/AuthPlugin.kt:115` — `setAttribute("user.id", UserIdHasher.hash(...))` ✓ sanctioned helper consumption; `"user.id"` literal NOT in Tier 1.
  - `backend/ktor/src/main/.../chat/ChatRoutes.kt:354` — `withSpan(..., mapOf("conversation_id" to ..., "message_id" to ..., "supabase.realtime.channel" to "chat:$conversationId"))` ✓ safe keys (none in Tier 1, none match Tier 2, no `{ip:...}` value).
  - `backend/ktor/src/main/.../chat/ChatRoutes.kt:415` — `span.addEvent("chat_realtime_publish_failed", ...)` ✓ safe event name.
  - `backend/ktor/src/main/.../health/HealthRoutes.kt:167` — `val key = "{scope:health}:{ip:$hashedIp}"` ✓ simple-name interpolation (Mode B negative-lookahead `(?!\$)` correctly passes).
  - `infra/fcm/src/main/.../FcmDispatcher.kt:159-166` — `withSpan(..., mapOf("messaging.system" to "fcm", "user.id" to UserIdHasher.hash(notification.userId)))` ✓ safe keys; would also be path-allowlisted if detekt were ever extended to `:infra:fcm`.
  - `infra/otel/src/main/` — all `AttributesBuilder.put` + `FORBIDDEN_KEYS` data-Set usages → path-allowlisted via `/infra/otel/src/main/`.

## 4. OpenSpec validation

- [x] 4.1 `openspec validate otel-attribute-lint-rule --strict` green.
- [x] 4.2 Verified — both files contain `### Requirement: Forbidden span attributes — defense-in-depth for PII` at line 3 (change delta) and line 183 (canonical spec), exact byte-for-byte match.

## 5. FOLLOW_UPS.md hygiene

- [x] 5.1 Deleted `FOLLOW_UPS.md` entry `observability-otel-attribute-detekt-rule` — full scope fulfilled by this change.
- [x] 5.2 Added four new `FOLLOW_UPS.md` entries for the deliberately-deferred scope:
  - `otel-attribute-rule-value-aware-userid-aliases` — value-aware detection of raw user-id under generic-named keys (`principal`, `actor`, `subject`, `owner`).
  - `otel-attribute-rule-location-key-patterns` — `*location*` / `*lat*` / `*lng*` / `*coord*` key-name pattern enforcement with `display_location` carve-out and no overlap with `CoordinateJitterRule`.
  - `otel-attribute-rule-opaque-secrets` — Tier 2 patterns for OAuth `client_secret`, raw refresh tokens, plaintext passwords (requires either known-prefix convention or accepting code-review as canonical defense).
  - `otel-attribute-rule-psi-context-restricted-mode-a` — PSI-context-restricted Mode A enforcement that fires only in setAttribute-like call sites; would allow re-introducing `"user_id"` to Tier 1 Group A (currently carved out — see Decision 3).
- [x] 5.3 Verified — `tryacquirebykey-ip-derived-uuid-detekt-rule` entry remains intact in `FOLLOW_UPS.md` (covers method-choice invariant, not key shape; stays open as a separate scope).

## 6. Pre-archive smoke (N/A for lint-only change)

- [x] 6.1 N/A — this change ships no runtime code path. The lint rule changes affect only build-time enforcement; there is no staging deploy surface to smoke. To be marked `N/A` in the archive commit body per `openspec/project.md` § Staging deploy timing convention.

## 7. Archive (same branch, same PR)

- [x] 7.1 `openspec archive otel-attribute-lint-rule --yes` ran — landed the spec deltas (+4 ADDED + ~1 MODIFIED in `observability-otel-foundation`; +2 ADDED in `rate-limit-infrastructure`); moved the change directory to `openspec/changes/archive/2026-05-12-otel-attribute-lint-rule/`.
- [x] 7.2 `openspec validate --specs observability-otel-foundation --strict` green — 45 spec items passed, 0 failed.
- [x] 7.3 `openspec validate --specs rate-limit-infrastructure --strict` green — 45 spec items passed, 0 failed.
- [x] 7.4 PR [#99](https://github.com/aditrioka/nearyou-id/pull/99) already retitled to `feat(lint): otel-attribute-lint-rule` during the `/opsx:apply` phase. Body will be refreshed to merge-ready shape (with archive-complete status, post-archive confirmation, post-merge tasks) as part of the archive commit push.
- [ ] 7.5 Single squash-merge to `main` at end-of-lifecycle — owner: user (after CI green on this archive commit).
