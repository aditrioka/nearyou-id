# Tasks — `otel-attribute-lint-rule`

## 1. Rule implementation

- [ ] 1.1 Create `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`. Model shape after `RawXForwardedForRule` / `CoordinateJitterRule` (`visitStringTemplateExpression` + path allowlist + `@AllowForbiddenSpanAttribute` annotation walk). Issue `Severity.Defect`, `Debt.TEN_MINS` (mirrors existing rules).
- [ ] 1.2 Mode A — Tier 1 forbidden-attribute keys: regex match (preferably `Set<String>` exact-match) the 21 entries listed in `specs/observability-otel-foundation/spec.md` § "Tier 1 — forbidden-attribute-key literals" (10 Group A + 8 Group B + 3 Group C). `"user_id"` is INTENTIONALLY EXCLUDED from Group A — see `design.md` § Decision 3 for the carve-out rationale.
- [ ] 1.3 Mode A — Tier 2 sensitive-value regex patterns: 4 regex constants per `specs/observability-otel-foundation/spec.md` § "Tier 2 — sensitive-value regex patterns" (PEM private-key marker; JWT three-segment shape; Redis URI with userinfo; JWKS RSA-key JSON shape).
- [ ] 1.4 Mode B — IP-axis value-shape check (NO call-site context restriction): apply the regex `\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}` (recommended) OR an equivalent PSI-aware check on every `KtStringTemplateExpression` regardless of enclosing call context. The literal is hoisted to `val key` at `HealthRoutes.kt:166` BEFORE being passed to `tryAcquireByKey`, so any call-site-context restriction would produce zero findings against production code (see `design.md` § Decision 5). Fires on raw IPv4/IPv6/15-hex/17-hex/uppercase-hex; passes on canonical 16-hex AND Kotlin template placeholders (simple-name `$<identifier>` AND block-form `${<expression>}`).
- [ ] 1.5 Path allowlist (broader than initial proposal — see `design.md` § Decision 6): file `virtualFilePath` substring check for `/src/test/` (broad, mirrors `RedisHashTagRule`), `/infra/otel/src/main/`, AND `/lint/detekt-rules/src/main/`. Synthetic-file-harness fallback via package-FQN check (`id.nearyou.lint.detekt.*`).
- [ ] 1.6 Annotation bypass: walk `KtAnnotated` ancestors looking for `@AllowForbiddenSpanAttribute`; require `isNotBlank()` reason (mirror `RedisHashTagRule.kt:123-139` precedent — covers empty, whitespace-only, tabs, newlines).
- [ ] 1.7 Register `OtelForbiddenAttributeRule(config)` in `NearYouRuleSetProvider.instance(config)` alongside the 6 existing rules.
- [ ] 1.8 KDoc header: explain the two enforcement modes (Mode A — Tier 1 + Tier 2 anywhere; Mode B — IP-axis value-shape anywhere with NO call-site-context restriction), the path + annotation allowlists, the relationship to the runtime `ForbiddenAttributeStripper` (complementary defense-in-depth), and an explicit "why dynamic-key construction is NOT in scope" paragraph + "why value-aware userid-alias detection is deferred" paragraph.

## 2. Rule tests

- [ ] 2.1 Create `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt`. Model shape after `RawXForwardedForRuleTest` / `RedisHashTagRuleTest` (`StringSpec` + `rule.lint(code)` / `rule.lint(path)` + `cleanupDir` helper).
- [ ] 2.2 Tier 1 Group A positive-fail cases (10 tests): one per Group A entry — the 3 HTTP client-identity keys (`client.address`, `client.port`, `http.client_ip`), 2 new-semconv peer keys (`network.peer.address`, `network.peer.port`), 3 old-semconv peer keys (`net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`), and 2 user-id typo variants (`user_uuid`, `user.uuid`). PLUS one explicit positive-pass test: `rs.getObject("user_id", UUID::class.java)` (the carve-out) → rule does NOT fire on `"user_id"` literal.
- [ ] 2.3 Tier 1 Group B positive-fail cases (8 tests): one per underscore typo-defensive variant (`client_address`, `client_port`, `http_client_ip`, `network_peer_address`, `network_peer_port`, `net_peer_ip`, `net_peer_port`, `net_sock_peer_addr`).
- [ ] 2.4 Tier 1 Group C positive-fail cases (3 tests): `jwt.sub`, `jwt.aud`, `jwt.iss` each fires from a non-allowlisted path.
- [ ] 2.5 Tier 2 positive-fail cases (4 tests): PEM marker (`-----BEGIN RSA PRIVATE KEY-----`); JWT three-segment shape (`eyJhbGc...eyJzdWI....sig`); Redis URI with userinfo (`redis://default:pw@host:6379/0`); JWKS RSA JSON (`{"kty":"RSA","n":"...","e":"..."}`).
- [ ] 2.6 Tier 2 false-positive negative tests (3 tests): `"eyJfoo"` alone (single segment) → no fire; `"redis://host:6379/0"` (no userinfo) → no fire; `"-----BEGIN PUBLIC KEY-----"` (PUBLIC not PRIVATE) → no fire.
- [ ] 2.7 Sanctioned `UserIdHasher.hash` consumption positive-pass: `span.setAttribute("user.id", UserIdHasher.hash(userId))` → no fire (the literal `"user.id"` is NOT in Tier 1).
- [ ] 2.8 IP-axis Mode B positive-fail: `val k = "{scope:health}:{ip:1.2.3.4}"` (literal hoisted to val, never passed anywhere) → fires (no call-site restriction).
- [ ] 2.9 IP-axis Mode B IPv6 positive-fail: `{ip:[2001:db8::1]}` literal anywhere → fires.
- [ ] 2.10 IP-axis Mode B canonical positive-pass: `{ip:abcdef0123456789}` (16-hex lowercase) → no fire.
- [ ] 2.11 IP-axis Mode B simple-name interpolation positive-pass (canonical prod shape): `val k = "{scope:health}:{ip:${'$'}hashedIp}"` → no fire (mirrors `HealthRoutes.kt:167`).
- [ ] 2.12 IP-axis Mode B block-form interpolation positive-pass: `"{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"` → no fire.
- [ ] 2.13 IP-axis Mode B off-canonical hex positive-fail (3 tests): 15-hex (`{ip:abcdef012345678}`) → fires; 17-hex (`{ip:abcdef01234567890}`) → fires; uppercase 16-hex (`{ip:ABCDEF0123456789}`) → fires.
- [ ] 2.14 IP-axis Mode B no-op on non-IP-axis key: `"{scope:rate_like_day}:{user:${'$'}userId}"` (no `{ip:...}` segment) → no fire on IP-axis check.
- [ ] 2.15 Annotation bypass non-empty reason positive-pass: `@AllowForbiddenSpanAttribute("admin span exempt — design Decision N")` on function → does NOT fire on `"client.address"` inside.
- [ ] 2.16 Annotation bypass on enclosing class positive-pass: `@AllowForbiddenSpanAttribute("escape hatch")` on class → does NOT fire on `"net.peer.ip"` in nested function.
- [ ] 2.17 Annotation bypass empty-reason still fires (3 tests): `@AllowForbiddenSpanAttribute("")` → fires; `@AllowForbiddenSpanAttribute("   ")` (whitespace-only) → fires; `@AllowForbiddenSpanAttribute("\t\n")` (other whitespace) → fires. All mirror `isNotBlank()` precedent from `RedisHashTagRule.kt:134`.
- [ ] 2.18 Annotation single-non-blank-char positive-pass: `@AllowForbiddenSpanAttribute("x")` → does NOT fire (non-blank is sufficient; the rule's job is to require a reason exists, not to assess its quality).
- [ ] 2.19 Path allowlist tests (4 tests): file under `/src/test/` (any path containing it) → no fire on Tier 1 / Tier 2 / IP-axis literals; file under `/infra/otel/src/main/` → no fire; file under `/lint/detekt-rules/src/main/` → no fire; arbitrary `/backend/ktor/src/main/` path → fires on the same literals.
- [ ] 2.20 Synthetic-file-harness package-FQN fallback positive-pass: package `id.nearyou.lint.detekt.fixtures.Allowed` (synthetic, no `virtualFilePath`) → treated as allowlisted source.
- [ ] 2.21 Composition with `CoordinateJitterRule`: a fixture with both `"actual_location"` AND `"client.address"` produces exactly 2 findings (1 per rule), no cross-suppression.
- [ ] 2.22 Composition with `RedisHashTagRule` two-way: a fixture `"rate:user:${'$'}userId"` (legacy non-hash-tagged, NO `{ip:...}`) → fires `RedisHashTagRule` but NOT IP-axis mode; a fixture `"rate:health:{ip:1.2.3.4}"` (legacy prefix AND raw IP) → fires BOTH rules independently with no cross-suppression.
- [ ] 2.23 Unrelated string literal positive-pass: `"Processing request"` / `"INSERT INTO posts ..."` / `"SELECT * FROM users WHERE id = ?"` → no fire.
- [ ] 2.24 NearYouRuleSetProvider registration positive-pass: explicit fixture asserting the rule appears in `NearYouRuleSetProvider.instance(config)`'s returned `RuleSet` (mirrors `RedisHashTagRuleTest`'s registration test).
- [ ] 2.25 Synchronization guard test: assert the rule's Tier 1 Group A list `containsAll (ForbiddenAttributeStripper.FORBIDDEN_KEYS - setOf("user_id"))` — superset relationship with one documented carve-out. Test failure message MUST name both any missing key AND the carve-out rationale, so the next contributor adding to `FORBIDDEN_KEYS` knows whether the new key belongs in Tier 1 or is a similar carve-out case. NOTE: requires `:lint:detekt-rules:test` to reference `ForbiddenAttributeStripper.FORBIDDEN_KEYS` at compile time — pick at implementation: (a) add `testImplementation(project(":infra:otel"))` to `lint/detekt-rules/build.gradle.kts`, OR (b) hardcode the expected keys in the test fixture with a comment pointing at `ForbiddenAttributeStripper.kt:89-108`.
- [ ] 2.26 `./gradlew :lint:detekt-rules:test` green.

## 3. Verify no regressions on the backend

- [ ] 3.1 `./gradlew detekt` green across backend + infra + lint modules with `OtelForbiddenAttributeRule` active. Today's writer surfaces (enumerated below) are expected to pass by construction.
- [ ] 3.2 If any pre-existing call site fires the rule, the implementation MUST fix the call site (preferred — convert to canonical helper consumption) OR add the appropriate path allowlist / annotation in the same commit with a justification comment. Zero call-site behavior changes expected today.
- [ ] 3.3 Audit the production writer surface (expanded grep from initial proposal): `grep -rn "setAttribute\|withSpan\|addEvent\|AttributesBuilder\|tryAcquireByKey" backend/ infra/ --include='*.kt'`. Enumerate all hits and confirm each is either canonical-helper-consumed (`UserIdHasher.hash`, `IpHasher.hash`) OR in an allowlisted path. Expected surfaces today:
  - `backend/ktor/src/main/.../auth/AuthPlugin.kt:115` — `setAttribute("user.id", UserIdHasher.hash(...))` ✓ sanctioned
  - `backend/ktor/src/main/.../chat/ChatRoutes.kt:354` — `withSpan(..., mapOf("conversation_id" to ..., "message_id" to ..., "supabase.realtime.channel" to ...))` ✓ safe keys
  - `backend/ktor/src/main/.../health/HealthRoutes.kt:166-170` — `val key = "{scope:health}:{ip:$hashedIp}"` ✓ simple-name interp
  - `infra/fcm/src/main/.../FcmDispatcher.kt:~159` — `withSpan(..., mapOf("messaging.system" to "fcm", "user.id" to UserIdHasher.hash(...)))` ✓ safe keys (`fcm` is a string value, not a Tier 1 key)
  - `infra/otel/src/main/` — all `AttributesBuilder.put` + key-list-as-data usages → path-allowlisted

## 4. OpenSpec validation

- [ ] 4.1 `openspec validate otel-attribute-lint-rule --strict` green.
- [ ] 4.2 Verify the MODIFIED requirement block in `specs/observability-otel-foundation/spec.md` matches the existing requirement header exactly (whitespace-insensitive). Header: `### Requirement: Forbidden span attributes — defense-in-depth for PII`.

## 5. FOLLOW_UPS.md hygiene

- [ ] 5.1 At archive time, delete `FOLLOW_UPS.md` item 4 (`observability-otel-attribute-detekt-rule`) — its full scope is fulfilled by this change.
- [ ] 5.2 At archive time, ADD four new `FOLLOW_UPS.md` entries for the deliberately-deferred scope (per `design.md` § "Explicitly deferred follow-ups"):
  - `otel-attribute-rule-value-aware-userid-aliases` — add detection of raw user-id under generic-named keys (`principal`, `actor`, `subject`, `owner`) via value-aware analysis.
  - `otel-attribute-rule-location-key-patterns` — add detection of `*location*` / `*lat*` / `*lng*` / `*coord*` key-name patterns with tight regex avoiding `display_location` false-positive and `CoordinateJitterRule` overlap.
  - `otel-attribute-rule-opaque-secrets` — Tier 2 patterns for OAuth `client_secret` values, raw refresh tokens, plaintext passwords (requires either known-prefix convention or accepting code-review as canonical defense).
  - `otel-attribute-rule-psi-context-restricted-mode-a` — PSI-context-restricted Mode A enforcement that fires only in setAttribute-like call sites; would allow re-introducing `"user_id"` to Tier 1 Group A (currently carved out — see Decision 3).
- [ ] 5.3 Verify other related FOLLOW_UPS items NOT folded in remain intact: item 7 (`tryacquirebykey-ip-derived-uuid-detekt-rule`) covers a different invariant (method choice, not key shape) and stays open.

## 6. Pre-archive smoke (N/A for lint-only change)

- [ ] 6.1 N/A — this change ships no runtime code path. The lint rule changes affect only build-time enforcement; there is no staging deploy surface to smoke. Mark `N/A` in the archive commit body per `openspec/project.md` § Staging deploy timing convention.

## 7. Archive (same branch, same PR)

- [ ] 7.1 `openspec archive otel-attribute-lint-rule` — lands the spec deltas into `openspec/specs/observability-otel-foundation/spec.md` and `openspec/specs/rate-limit-infrastructure/spec.md` permanently, and moves `openspec/changes/otel-attribute-lint-rule/` under `archive/<date>-otel-attribute-lint-rule/`.
- [ ] 7.2 `openspec validate --specs observability-otel-foundation --strict` green.
- [ ] 7.3 `openspec validate --specs rate-limit-infrastructure --strict` green.
- [ ] 7.4 PR title retitle via `gh pr edit <pr> --title 'feat(lint): otel-attribute-lint-rule'` (or equivalent conventional prefix). Body updated to merge-ready shape with final test counts + spec-delta summary + post-merge confirmation that `OtelForbiddenAttributeRule` is active.
- [ ] 7.5 Single squash-merge to `main` at end-of-lifecycle.
