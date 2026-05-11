# Tasks — `otel-attribute-lint-rule`

## 1. Rule implementation

- [ ] 1.1 Create `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`. Model shape after `RawXForwardedForRule` / `CoordinateJitterRule` (`visitStringTemplateExpression` + path allowlist + `@AllowForbiddenSpanAttribute` annotation walk for Mode A; additionally PSI parent-walk to detect `tryAcquireByKey(...)` call context for Mode B). Issue `Severity.Defect`, `Debt.TEN_MINS` (mirrors existing rules).
- [ ] 1.2 Mode A — Tier 1 forbidden-attribute keys: regex match the 10 keys listed in `specs/observability-otel-foundation/spec.md` § "Tier 1 — forbidden-attribute-key literals". Implementation MAY use a `Set<String>` exact-match (preferred) or an alternation regex.
- [ ] 1.3 Mode A — Tier 2 sensitive-value patterns: 3 regex constants per `specs/observability-otel-foundation/spec.md` § "Tier 2 — sensitive-value regex patterns" (PEM marker; JWT shape; Redis URI with userinfo).
- [ ] 1.4 Mode B — IP-axis `tryAcquireByKey` call-site check: walk PSI parents from the string-literal `KtStringTemplateExpression` to find the enclosing `KtCallExpression`; if the call-expression callee identifier is `tryAcquireByKey` AND the string-literal is the first argument (the `key` parameter), apply the IP-axis regex per `specs/rate-limit-infrastructure/spec.md` § "`OtelForbiddenAttributeRule` fences raw IP literal in `{ip:<value>}` rate-limit-key segments".
- [ ] 1.5 Path allowlist: file `virtualFilePath` substring check for `/infra/otel/src/` AND `/lint/detekt-rules/src/`. Synthetic-file-harness fallback via package-FQN check (`id.nearyou.lint.detekt.*`).
- [ ] 1.6 Annotation bypass: walk `KtAnnotated` ancestors looking for `@AllowForbiddenSpanAttribute`; require non-empty reason (mirror `RedisHashTagRule`'s `@AllowRawRedisKey` enforcement).
- [ ] 1.7 Register `OtelForbiddenAttributeRule(config)` in `NearYouRuleSetProvider.instance(config)` alongside the 6 existing rules.
- [ ] 1.8 KDoc header: explain the three enforcement modes (Tier 1 / Tier 2 / IP-axis), the path + annotation allowlists, the relationship to the runtime `ForbiddenAttributeStripper` (complementary defense-in-depth), and an explicit "why dynamic-key construction is NOT in scope" paragraph.

## 2. Rule tests

- [ ] 2.1 Create `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt`. Model shape after `RawXForwardedForRuleTest` / `RedisHashTagRuleTest` (`StringSpec` + `rule.lint(code)` / `rule.lint(path)` + `cleanupDir` helper).
- [ ] 2.2 Tier 1 positive-fail cases: one test per forbidden key (the 11 entries mirroring `ForbiddenAttributeStripper.FORBIDDEN_KEYS`: 8 network-semconv keys `client.address`, `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`, `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr` + 3 user-id typo-defensive variants `user_id`, `user_uuid`, `user.uuid`).
- [ ] 2.3 Tier 1 positive-pass cases: `:infra:otel` source path allowlist suppresses; `:lint:detekt-rules` source path allowlist suppresses.
- [ ] 2.4 Tier 2 positive-fail cases: PEM marker (`-----BEGIN RSA PRIVATE KEY-----`); JWT-shape literal `eyJhbGc.eyJzdWI.sig`; Redis URI with userinfo `redis://default:pw@host:6379/0`.
- [ ] 2.5 Sanctioned `UserIdHasher.hash` consumption positive-pass: `span.setAttribute("user.id", UserIdHasher.hash(userId))` → no fire (the literal `"user.id"` is NOT in Tier 1 per Decision 4 in design.md).
- [ ] 2.6 IP-axis Mode B positive-fail: `rateLimiter.tryAcquireByKey(key = "{scope:health}:{ip:1.2.3.4}", capacity = 60, ttl = Duration.ofSeconds(60))` fires.
- [ ] 2.7 IP-axis Mode B IPv6 positive-fail: `{ip:[2001:db8::1]}` literal in `tryAcquireByKey` first arg fires.
- [ ] 2.8 IP-axis Mode B canonical positive-pass: `{ip:abcdef0123456789}` (16-hex) literal in `tryAcquireByKey` first arg → no fire.
- [ ] 2.9 IP-axis Mode B template-string positive-pass: `"{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"` literal in `tryAcquireByKey` first arg → no fire (the value between braces is the helper-consumption placeholder, not a raw literal).
- [ ] 2.10 IP-axis Mode B 15-hex / 17-hex / uppercase-hex positive-fail: all three off-canonical shapes fire.
- [ ] 2.11 IP-axis Mode B no-op on non-IP-axis call: `tryAcquire(userId, "{scope:rate_like_day}:{user:${'$'}userId}", ...)` does NOT fire on the IP-axis check.
- [ ] 2.12 IP-axis Mode B no-op on `{ip:...}` literal outside `tryAcquireByKey` parent: `val s = "{ip:1.2.3.4}"` not passed to `tryAcquireByKey` → IP-axis-mode does NOT fire (no Tier 1 / Tier 2 OTel match either).
- [ ] 2.13 Annotation bypass non-empty reason positive-pass: `@AllowForbiddenSpanAttribute("admin span exempt — design Decision N")` on function → does NOT fire on `"client.address"` inside.
- [ ] 2.14 Annotation bypass empty-reason still fires: `@AllowForbiddenSpanAttribute("")` → fires on `"client.address"` inside (mirrors `@AllowRawRedisKey` empty-reason rejection).
- [ ] 2.15 Synthetic-file-harness package-FQN fallback: package `id.nearyou.lint.detekt.fixtures.Allowed` is allowlisted.
- [ ] 2.16 Composition with `CoordinateJitterRule`: a fixture with both `"actual_location"` AND `"client.address"` produces exactly 2 findings (1 per rule), no cross-suppression.
- [ ] 2.17 Composition with `RedisHashTagRule`: a fixture with `"rate:user:${'$'}userId"` (legacy non-hash-tagged) → fires `RedisHashTagRule` but NOT the new IP-axis mode (separate concerns).
- [ ] 2.18 Unrelated string literal: `"Processing request"` / `"INSERT INTO posts ..."` → no fire.
- [ ] 2.19 Synchronization guard test: a test asserting the rule's Tier 1 list contains every entry in `ForbiddenAttributeStripper.FORBIDDEN_KEYS` (per design Decision 3). Test failure → manually re-sync.
- [ ] 2.20 `./gradlew :lint:detekt-rules:test` green.

## 3. Verify no regressions on the backend

- [ ] 3.1 `./gradlew detekt` green across backend + infra + lint modules with `OtelForbiddenAttributeRule` active. Today's writer surface is 1 sanctioned `setAttribute` (`AuthPlugin.kt:115` using `UserIdHasher.hash`) + 1 sanctioned `tryAcquireByKey` (`HealthRoutes.kt:169` using `IpHasher.hash`). Both should pass by construction.
- [ ] 3.2 If any pre-existing call site fires the rule, the implementation MUST fix the call site (preferred — convert to canonical helper consumption) OR add a path allowlist / annotation in the same PR with a justification comment. Zero call-site behavior changes expected today.
- [ ] 3.3 Spot-check `grep -rn "setAttribute\|tryAcquireByKey" backend/ infra/ --include='*.kt'`: enumerate all hits, confirm each is either canonical-helper-consumed OR in an allowlisted path.

## 4. OpenSpec validation

- [ ] 4.1 `openspec validate otel-attribute-lint-rule --strict` green.
- [ ] 4.2 Verify the MODIFIED requirement block in `specs/observability-otel-foundation/spec.md` matches the existing requirement header exactly (whitespace-insensitive).

## 5. FOLLOW_UPS.md hygiene

- [ ] 5.1 At archive time, delete `FOLLOW_UPS.md` item 4 (`observability-otel-attribute-detekt-rule`) — its full scope (forbidden span attribute lint + IP-axis lint per its action item 3) is fulfilled by this change.
- [ ] 5.2 Verify the related FOLLOW_UPS items NOT folded in remain intact: item 7 (`tryacquirebykey-ip-derived-uuid-detekt-rule`) is a different invariant (method choice, not key shape) and stays open.
- [ ] 5.3 If after deletion the file is empty, delete the file itself per repo convention.

## 6. Pre-archive smoke (N/A for lint-only change)

- [ ] 6.1 N/A — this change ships no runtime code path. The lint rule changes affect only build-time enforcement; there is no staging deploy surface to smoke. Mark `N/A` in the archive commit body per `openspec/project.md` § Staging deploy timing convention.

## 7. Archive (same branch, same PR)

- [ ] 7.1 `openspec archive otel-attribute-lint-rule` — lands the spec deltas into `openspec/specs/observability-otel-foundation/spec.md` and `openspec/specs/rate-limit-infrastructure/spec.md` permanently, and moves `openspec/changes/otel-attribute-lint-rule/` under `archive/<date>-otel-attribute-lint-rule/`.
- [ ] 7.2 `openspec validate --specs observability-otel-foundation --strict` green.
- [ ] 7.3 `openspec validate --specs rate-limit-infrastructure --strict` green.
- [ ] 7.4 PR title retitle via `gh pr edit <pr> --title 'feat(lint): otel-attribute-lint-rule'` (or equivalent conventional prefix). Body updated to merge-ready shape with final test counts + spec-delta summary + post-merge confirmation that `OtelForbiddenAttributeRule` is active.
- [ ] 7.5 Single squash-merge to `main` at end-of-lifecycle.
