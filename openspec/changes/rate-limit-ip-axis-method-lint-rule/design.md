## Context

The `rate-limit-infrastructure` capability already has THREE layers of defence around IP-axis bucketing:

1. **Interface contract** — [`RateLimiter`](../../../core/domain/src/main/kotlin/id/nearyou/app/core/domain/ratelimit/RateLimiter.kt) defines two distinct entry points: `tryAcquire(userId, key, capacity, ttl)` for user-axis call sites, and `tryAcquireByKey(key, capacity, ttl)` for axis-agnostic call sites (IP, geocell, fingerprint, global circuit-breaker). The KDoc + spec scenario "tryAcquireByKey omits userId from telemetry" documents that IP-axis MUST use `tryAcquireByKey`.
2. **Runtime telemetry log** — [`RedisRateLimiter.admit()`](../../../infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) emits structured DEBUG logs that include `key=<key>` but only include `user_id=<id>` when the call came in via `tryAcquire`. The spec scenario forbids the literal sentinel UUID `00000000-0000-0000-0000-000000000000` from appearing anywhere in the log surface.
3. **Compile-time value-shape Detekt rule** — `OtelForbiddenAttributeRule` (shipped under `observability-otel-foundation`, with cross-cuttingly enforced IP-axis Mode B added by the just-shipped `otel-attribute-lint-rule` PR [#99](https://github.com/aditrioka/nearyou-id/pull/99)) fires on any Kotlin string literal containing `{ip:<value>}` where `<value>` is not the canonical 16-hex `IpHasher.hash` output (or a Kotlin template-string placeholder).

The gap that this change closes is **the compile-time method-choice axis**: nothing today fires when the WRONG method is used with a CORRECTLY-shaped IP key. A future maintainer can write `tryAcquire(UUID.nameUUIDFromBytes(ip.toByteArray()), "{scope:foo}:{ip:abc123def4567890}", capacity, ttl)` — the IP value is properly hashed (passes `OtelForbiddenAttributeRule`), the literal sentinel UUID is not used (passes the structured-log scenario), but the call still bypasses the architectural intent: the IP-axis bucket goes through the user-keyed method, the per-call telemetry mistakenly logs an IP-derived `user_id`, and the visible code shape no longer signals "this is an IP-axis call site." The pattern is small enough to slip through code review and large enough to silently re-introduce the tech debt that `health-check-endpoints` (PR [#54](https://github.com/aditrioka/nearyou-id/pull/54)) explicitly factored out.

A focused fourth-layer Detekt rule closes the gap mechanically. It mirrors the structural shape of the existing eight rules in [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`](../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/): `BlockExclusionJoinRule`, `CoordinateJitterRule`, `OtelForbiddenAttributeRule`, `RateLimitTtlRule`, `RawFromPostsRule`, `RawXForwardedForRule`, `RedisHashTagRule`, plus the `NearYouRuleSetProvider`. The naming convention adopted is `<RuleName>Rule.kt` for the rule + `<RuleName>LintTest.kt` for the test fixture set.

## Goals / Non-Goals

**Goals:**

- Mechanically prevent the regression where `tryAcquire(...)` is called with a `key` containing the `{ip:...}` axis segment.
- Match the structural pattern of the just-shipped `OtelForbiddenAttributeRule` to keep the project Detekt ruleset consistent (review surface, allowlist mechanism, NearYouRuleSetProvider registration, test class shape, composition test).
- Cover the three known regression vectors from the FOLLOW_UPS finding: (a) IP-derived UUID via `UUID.nameUUIDFromBytes(ip.toByteArray())`; (b) literal sentinel UUID `00000000-...`; (c) genuine `userId` value paired with an `{ip:...}` key (defensive — surfaces obvious copy-paste errors at compile time).
- Coexist with the existing runtime structured-log defence and the `OtelForbiddenAttributeRule` value-shape defence — three independent layers, no cross-suppression.

**Non-Goals:**

- Runtime span-attribute / log-line stripping. The existing structured-log scenario "tryAcquireByKey omits userId from telemetry" + the existing `OtelForbiddenAttributeRule` IP-axis Mode B remain the runtime / value-shape backstops. This rule is the call-site method-choice complement.
- Generalising to all non-`user:` axes (geocell, fingerprint, global circuit-breaker, generic). Scope is intentionally narrow to `ip:` only — see Decision 3.
- An annotation-bypass mechanism (e.g., `@AllowIpAxisOnTryAcquire("<reason>")` mirroring `@AllowForbiddenSpanAttribute`). Scope intentionally excludes this — see Decision 5.
- Mobile-side enforcement. Mobile is currently DESIGN per [`openspec/project.md`](../../../openspec/project.md) § Mobile + Admin Status; once mobile rate-limit code exists, the same rule applies cross-cuttingly via the project Detekt config, no rule changes needed.
- SQL migration file scanning. Detekt visits Kotlin PSI; `.sql` files are reviewed in PR.
- Refactoring any existing call site. There is currently no production `tryAcquire(...)` call with an `{ip:...}` key — this is regression prevention, not cleanup.

## Decisions

### Decision 1: Detect on `tryAcquire` short-name + `key` argument literal shape — not full type-resolution

**Choice:** Match `KtCallExpression`s whose callee is a `KtSimpleNameExpression` with text `tryAcquire`, AND whose `key` argument (positional index 1, OR named argument `key`) is a `KtStringTemplateExpression` whose flat textual content matches the regex `\{[^}]*ip:`.

**Alternatives considered:**

- (a) Full Detekt type-resolution mode — verify the callee resolves to `RateLimiter.tryAcquire`. This would eliminate any short-name collision risk (e.g., `Semaphore.tryAcquire` from `java.util.concurrent`).
- (b) PSI heuristic — match short name, then check the `key` argument's static type. Same as (a) but cheaper.
- (c) Receiver-type heuristic — walk the PSI to find the receiver expression and check its declared type.

**Rationale for short-name + literal-shape over (a)/(b)/(c):**

- The only other `tryAcquire` short-name in the project codebase is `java.util.concurrent.Semaphore.tryAcquire`, which has signatures `tryAcquire()`, `tryAcquire(int permits)`, `tryAcquire(long timeout, TimeUnit unit)` — none take a `String` second argument. The argument-shape gate (`KtStringTemplateExpression` containing `{ip:`) eliminates the false-positive surface entirely.
- Mirror-precedent: `RawFromPostsRule`, `BlockExclusionJoinRule`, `RedisHashTagRule`, `RawXForwardedForRule`, `RateLimitTtlRule`, and `OtelForbiddenAttributeRule` all use PSI/short-name + literal-shape heuristics, NOT type-resolution. The project Detekt config does not enable type-resolution; adding it would require a build-graph dependency on the analysed module's classpath, a cross-cutting change explicitly out of scope per the existing rules' shipped pattern.
- Performance: short-name + literal-shape is O(1) per visit. Type-resolution is O(N) per visit and pulls the full classpath on each Detekt run.

**Trade-off accepted:** if a future contributor adds a third-party library exposing a `tryAcquire(receiver, String, ..., ...)` method whose `String` argument happens to look like `{ip:...}` for an unrelated reason, the rule will produce a false positive. The risk is hypothetical; the existing `RawFromPostsRule`, `BlockExclusionJoinRule`, etc. accept the identical trade-off and have lived with it across V5–V13 without incident.

### Decision 2: Fire on the `key`-argument expression, not on the call expression

**Choice:** The Detekt finding's `Entity.from(...)` location points to the `key`-argument PSI element (the `KtStringTemplateExpression`), not the enclosing `KtCallExpression`.

**Rationale:** A reviewer running into the lint failure should see the IDE caret on the actual offending value — the IP-axis literal — not on the `tryAcquire` callee. This matches the precedent set by `RedisHashTagRule` (which fires on the literal Redis-key string, not on the surrounding `RedisCommand.evalsha(...)` call) and `RawFromPostsRule` (which fires on the literal SQL fragment, not on the Connection method call). The error message includes the recommended fix: "Use `RateLimiter.tryAcquireByKey(key, capacity, ttl)` for IP-axis bucketing — `tryAcquire(userId, key, ...)` is the user-axis entry point. See `openspec/specs/rate-limit-infrastructure/spec.md` § \"Interface in :core:domain\" for the contract."

### Decision 3: Scope intentionally narrow to `ip:` only

**Choice:** The rule's regex matches `\{[^}]*ip:` — fires only on keys containing the `ip:` axis prefix. Other non-`user:` axes (e.g., `{geocell:abc}`, `{fingerprint:xyz}`, `{global:1}`) are NOT detected.

**Alternatives considered:**

- (α) Generalise to fire on ANY non-`user:` axis under `tryAcquire`. Catches all regressions across all axes.
- (β) Require an allowlist of "permitted axes for `tryAcquire`" with a default-deny posture.
- (γ) Current choice — narrow `ip:` scope.

**Rationale for narrow `ip:` scope:**

- The only axis with a documented production `tryAcquireByKey` call site at the time of this change is `ip:` (via `health-check`). The other axes (geocell, fingerprint, global) are described in the spec as *future* call sites — implementing a rule against keys that don't yet exist would be premature.
- The FOLLOW_UPS entry that motivates this change explicitly scopes itself to the `ip:` axis: *"a Detekt rule `IpAxisMustUseTryAcquireByKeyRule` … that fires on calls to `RateLimiter.tryAcquire(...)` whose `key` argument matches the regex `\{[^}]*ip:`"*. Honouring the scoped trigger keeps this change small and avoids re-litigating axis design.
- Generalising to (α) or (β) would force a design conversation about what counts as "user axis" semantically (does `{user:0}` for a global circuit-breaker count? is `{rate_like_day}` a user axis?) — that conversation belongs in a future change once a second axis ships, per the rule of three.
- Mitigation for the narrow scope: a TODO/KDoc comment in the rule body documents the future-extension path. A future change MAY widen the regex when a second non-`user:` axis ships a non-trivial `tryAcquireByKey` call site.

### Decision 4: Handle named-argument and positional-argument forms uniformly

**Choice:** The PSI walk first checks for a named argument `key = ...` (handles `tryAcquire(userId = u, key = "{scope:...}", ...)`); if absent, falls back to positional index 1 (handles `tryAcquire(userId, "{scope:...}", capacity, ttl)`).

**Rationale:** The existing project codebase mixes both forms (`like-rate-limit`, `reply-rate-limit`, `chat-rate-limit` use a mix; `RateLimiter` interface KDoc shows the positional form). The rule must handle both to avoid silent gaps. Test fixture set covers both shapes (positive case A.1 is positional, A.5 is named).

**Trade-off accepted:** if the `key` parameter name is ever renamed (unlikely — the interface is in `:core:domain` and a rename would itself require an OpenSpec change), the rule's named-argument fallback would break silently. Mitigation: the test fixture set's named-argument case (A.5) would catch the regression on the next `:lint:detekt-rules:test` run.

### Decision 5: No annotation-bypass mechanism in this change

**Choice:** Unlike `OtelForbiddenAttributeRule` (`@AllowForbiddenSpanAttribute("<reason>")`), `RateLimitTtlRule` (`@AllowDailyTtlOverride`), and `RedisHashTagRule` (`@AllowRawRedisKey`), this rule does NOT introduce an annotation-bypass.

**Rationale:**

- There is no production call site that legitimately needs `tryAcquire` with an `{ip:...}` key. Every IP-axis use case should go through `tryAcquireByKey` per the spec contract — that's the entire point of the method's existence.
- Adding the annotation surface preemptively would invite misuse: a future contributor hitting the lint failure could add `@AllowIpAxisOnTryAcquire("temporary workaround")` rather than fixing the call site. The escalation cost (modify the rule + add the annotation + amend the test) is the right friction for catching regressions.
- The path-based test allowlist (`**/src/test/**`) covers the legitimate case where a fixture intentionally exercises the wrong-method path to verify the rule fires.

**Trade-off accepted:** if a genuine production exception arises (currently unimaginable), the contributor will need to file a follow-up change adding the annotation, modifying the rule, and amending this design doc's Decision 5. That escalation is the desired behaviour — the cost matches the architectural surprise.

### Decision 6: Test-source path allowlist via `**/src/test/**`

**Choice:** The rule short-circuits (`return` early) on any `KtFile` whose `virtualFilePath` contains `/src/test/`. This mirrors the existing path-allowlist pattern from `OtelForbiddenAttributeRule` (allows `/src/test/` + `/infra/otel/src/main/` + `/lint/detekt-rules/src/main/`) and `RawFromPostsRule` (allows admin module paths).

**Rationale:**

- Test fixtures (e.g., `RedisRateLimiterTelemetryTest`, `RateLimiterTest`, the new `IpAxisMustUseTryAcquireByKeyLintTest` itself) need to construct fixture call sites that intentionally exercise the wrong-method path to verify the rule fires. The path allowlist is the canonical mechanism.
- Production code paths — `:backend:ktor` (any sub-package), `:infra:redis`, `:infra:otel`, `:core:domain` — are NOT allowlisted; the rule fires on any `tryAcquire(..., "{...ip:...}", ...)` call in those paths, which is the desired behaviour.
- Why NOT additionally allowlist `:lint:detekt-rules/src/main/` (as `OtelForbiddenAttributeRule` does): the lint-rule source itself is a fixture-construction site for the rule's KDoc examples — but the rule under design here does NOT embed live `tryAcquire` calls in its KDoc (the KDoc references the regex literal, not Kotlin syntax). The narrower allowlist (`**/src/test/**` only) is sufficient for this rule and avoids the precedent of "every new rule allowlists its own source path."
- Why NOT additionally allowlist `**/dev/scripts/**` (or other dev-only paths): those are not Kotlin sources Detekt scans by default — the project Detekt config scopes to module sourceSets only.

### Decision 7: NearYouRuleSetProviderTest extension matches the just-shipped pattern

**Choice:** The provider-registration assertion lives in `NearYouRuleSetProviderTest` (extending the existing class shipped under `otel-attribute-lint-rule`) — one new test method asserting `provider.instance(Config.empty()).rules.any { it is IpAxisMustUseTryAcquireByKeyRule }`.

**Rationale:** Mirror precedent — `OtelForbiddenAttributeRule` registration was asserted exactly this way in PR [#99](https://github.com/aditrioka/nearyou-id/pull/99). Keeps the project Detekt-ruleset test surface consistent.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| False positive on `Semaphore.tryAcquire(...)` short-name collision | The argument-shape gate (`KtStringTemplateExpression` containing `{ip:`) cannot match `Semaphore.tryAcquire`'s signatures (none take a `String` second argument). Decision 1 trade-off explicitly accepted. |
| Maintainer disables the rule by adding an unused `@Suppress("IpAxisMustUseTryAcquireByKey")` annotation at the call site | Detekt's standard `@Suppress` mechanism is a known escape hatch across all rules; the project Detekt config does NOT disallow it. Code-review remains the canonical defence against `@Suppress` misuse, mirroring how the project handles `@Suppress("RawFromPosts")` and `@Suppress("BlockExclusionJoin")`. |
| Rule fires on a future `tryAcquire(userId, "{scope:foo}:{ip:abc}", ...)` call site that legitimately wants both user-axis and IP-axis bucketing in the same call | No such pattern exists today — the per-axis design is one bucket per call. If a future change introduces multi-axis bucketing, this rule's failure forces the design conversation through the OpenSpec channel. Acceptable. |
| `key`-argument literal is split via concatenation: `tryAcquire(userId, "{scope:foo}:" + ipSegment, capacity, ttl)` | The rule's `KtStringTemplateExpression`-only match does NOT detect concatenation. Mitigation: `RedisHashTagRule` already enforces literal-form keys at the construction site, so concatenation is independently blocked. The two rules combine to close the loophole. Out of scope for this rule per § Out of scope in `proposal.md`. |
| Rule scope (narrow `ip:` only) misses a future `tryAcquire(userId, "{scope:foo}:{geocell:abc}", ...)` regression | Decision 3 trade-off explicitly accepted. The rule's KDoc carries a TODO documenting the future-extension path; the test fixture set's negative case B.3 (`{geocell:abc}` passes) is the regression-test placeholder for when the scope widens. |
| Adding a 9th Detekt rule increases the per-PR Detekt runtime | Each rule is O(1) per PSI visit; project Detekt runtime is dominated by file-walk + config parsing, not by per-rule logic. Empirical baseline: `OtelForbiddenAttributeRule` (the most recent rule, with regex Tier 1 + Tier 2 + IP-axis Mode B) added <100ms to the project Detekt run (per PR [#99](https://github.com/aditrioka/nearyou-id/pull/99) CI logs). The new rule is significantly simpler (one regex check, no tiering) — expected addition: <20ms. Acceptable. |

## Open Questions

- **None at proposal time.** The design replicates an established precedent (`OtelForbiddenAttributeRule`'s structural shape) and addresses a single known regression vector. All judgment calls (scope to `ip:` only, no annotation bypass, test-only allowlist) have explicit rationale in the Decisions section.
