package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Forbids `RateLimiter.tryAcquire(userId, key, capacity, ttl)` calls whose `key` argument
 * is a Kotlin string literal containing the `{ip:...}` axis segment. Such calls MUST go
 * through [`tryAcquireByKey(key, capacity, ttl)`](../../../../../../../../core/domain/src/main/kotlin/id/nearyou/app/core/domain/ratelimit/RateLimiter.kt)
 * — the axis-agnostic entry point introduced under `health-check-endpoints`
 * ([PR #54](https://github.com/aditrioka/nearyou-id/pull/54)) for IP / geocell / fingerprint /
 * global-circuit-breaker bucketing.
 *
 * ## Why this rule exists
 *
 * The `rate-limit-infrastructure` capability spec already has three layers of defence
 * around IP-axis bucketing:
 *
 *  1. **Interface contract** — `RateLimiter` defines `tryAcquire(userId, ...)` for the
 *     user axis and `tryAcquireByKey(...)` for everything else. The KDoc + spec scenario
 *     "tryAcquireByKey omits userId from telemetry" documents the contract.
 *  2. **Runtime telemetry log** — `RedisRateLimiter.admit()` emits structured DEBUG
 *     logs containing `user_id=<id>` only on `tryAcquire` paths. The runtime scenario
 *     forbids the literal sentinel UUID `00000000-0000-0000-0000-000000000000` from
 *     appearing anywhere in the log surface.
 *  3. **Compile-time value-shape** — [`OtelForbiddenAttributeRule`](OtelForbiddenAttributeRule.kt)
 *     Mode B fires on any `{ip:<value>}` literal whose `<value>` is neither 16-lowercase-hex
 *     (canonical `IpHasher.hash` output) nor a Kotlin template placeholder.
 *
 * This rule closes the **compile-time method-choice** gap: nothing today fires when the
 * WRONG method is used with a CORRECTLY-shaped IP key. A future maintainer could write
 * `tryAcquire(UUID.nameUUIDFromBytes(ip.toByteArray()), "{scope:foo}:{ip:abc123def4567890}",
 * capacity, ttl)` — the IP value is properly hashed (passes `OtelForbiddenAttributeRule`),
 * the literal sentinel UUID is not used (passes the structured-log scenario), but the
 * call still bypasses the architectural intent. This rule catches that mechanically.
 *
 * Together the four layers form defense-in-depth: interface contract, value-shape lint,
 * method-choice lint (this rule), and runtime structured-log surface.
 *
 * ## Detection
 *
 * The rule fires on any `KtCallExpression` where ALL of the following hold:
 *
 *  - The callee is a `KtSimpleNameExpression` with text `tryAcquire` (short-name match;
 *    no type-resolution — see "Scope" below).
 *  - The call has at least 2 value arguments (defensive — prevents
 *    IndexOutOfBoundsException on malformed call shapes).
 *  - The `key` argument is a `KtStringTemplateExpression` whose flat textual content
 *    matches the regex `\{[^}]*ip:`. The `key` argument is resolved by name first
 *    (`key = ...`); if no named argument exists, falls back to positional index 1.
 *  - The containing file is NOT on the path / package allowlist (see "Allowlist" below).
 *
 * On fire, the finding's `Entity.from(...)` points to the `key`-argument PSI element
 * (the `KtValueArgument` wrapping the string literal), so the IDE caret lands on the
 * offending value. Mirrors `RedisHashTagRule` (fires on the literal Redis-key string)
 * and `RawFromPostsRule` (fires on the literal SQL fragment) — not on the enclosing
 * `tryAcquire(...)` call.
 *
 * ## Scope — intentionally narrow to `ip:`
 *
 * The regex matches `\{[^}]*ip:` — fires only on keys containing the `ip:` axis prefix.
 * Other non-`user:` axes (e.g., `{geocell:abc}`, `{fingerprint:xyz}`, `{global:1}`) are
 * NOT detected. The IP-axis is the only axis with a documented production
 * `tryAcquireByKey` call site at the time of this change (`/health/...` anti-scrape via
 * `health-check-endpoints`). A future change MAY widen the regex once a second
 * non-`user:` axis ships a non-trivial `tryAcquireByKey` call site (rule of three).
 *
 * TODO(future-extension): when geocell / fingerprint / global axes ship production
 * `tryAcquireByKey` call sites, broaden the regex to `\{[^}]*(?!user:)[a-z_]+:` (or
 * equivalent) and update the test fixture set's currently-passing negative cases
 * (`{geocell:abc}`, etc.) to expect a finding. See `design.md` § Decision 3 in the
 * `rate-limit-ip-axis-method-lint-rule` change for the rationale.
 *
 * Effective enforcement scope MATCHES the project Detekt source-set configuration: as
 * of this change, that is `:backend:ktor`'s `src/main/kotlin` only (per
 * [`build-logic/src/main/kotlin/nearyou.ktor.gradle.kts:20`](../../../../../../../../build-logic/src/main/kotlin/nearyou.ktor.gradle.kts)).
 * `:core:domain` and `:infra:redis` DEFINE the interface but do not consume it — no
 * coverage gap. A future change MAY extend the Detekt source scope to additional
 * modules if regression risk surfaces.
 *
 * ## Allowlist
 *
 * The rule does NOT fire when the containing file is on the allowlist:
 *
 *  - Any path containing `/src/test/` — broad test-fixture allowlist. Tests of the
 *    limiter (e.g., `RedisRateLimiterTelemetryTest`) and of this rule itself
 *    legitimately need wrong-method fixtures.
 *  - Synthetic-file harness via package-FQN fallback: package equals
 *    `id.nearyou.test.fixtures` OR starts with `id.nearyou.test.fixtures.`. The
 *    detekt-test `lint(String)` overload gives synthetic files no real
 *    `virtualFilePath`, so the path check fails — the FQN fallback covers them.
 *
 * Mirrors the canonical 3-rule precedent: `RedisHashTagRule.isAllowedPath()`,
 * `RateLimitTtlRule.isAllowedPath()`, and `BlockExclusionJoinRule.isAllowedPath()` all
 * use the same dual-check shape with `id.nearyou.test.fixtures.*` as the fallback FQN.
 * (`OtelForbiddenAttributeRule` is the outlier — it uses `id.nearyou.lint.detekt.*`
 * because its KDoc embeds raw IP literals that would otherwise self-trigger; this rule
 * has no analogous self-fixture concern.)
 *
 * ## No annotation-bypass mechanism
 *
 * Unlike `OtelForbiddenAttributeRule` (`@AllowForbiddenSpanAttribute`),
 * `RateLimitTtlRule` (`@AllowDailyTtlOverride`), and `RedisHashTagRule`
 * (`@AllowRawRedisKey`), this rule does NOT introduce an annotation-bypass. Every
 * IP-axis use case should go through `tryAcquireByKey` per the spec contract — that's
 * the entire point of the method's existence. The standard Detekt
 * `@Suppress("IpAxisMustUseTryAcquireByKey")` mechanism remains available; code-review
 * is the canonical defence against misuse. See `design.md` § Decision 5 for the
 * rationale.
 *
 * ## Out of scope
 *
 *  - Runtime-constructed `key` values via string concatenation
 *    (`tryAcquire(userId, "{scope:foo}:{ip:" + hashedIp + "}", ...)`). The rule
 *    inspects Kotlin string literals (`KtStringTemplateExpression`); concatenation
 *    chains are invisible. `RedisHashTagRule` already enforces literal-form keys at
 *    construction sites, so concatenation is independently blocked.
 *  - Val-extraction bypass (`val k = "{...ip:...}"; tryAcquire(userId, k, ...)`). The
 *    `key` argument is then a `KtNameReferenceExpression`, not a
 *    `KtStringTemplateExpression`. Acknowledged as a partial gap covered by code review
 *    + `OtelForbiddenAttributeRule` IP-axis Mode B on the val-declaration site (when
 *    the value is raw / off-canonical). See `proposal.md` § Out of scope.
 *  - Short-name collisions with `java.util.concurrent.Semaphore.tryAcquire(...)`.
 *    None of `Semaphore`'s signatures take a `String` second argument — the argument-
 *    shape gate (`as? KtStringTemplateExpression`) eliminates the false-positive
 *    surface entirely.
 *
 * Composition with sibling rules: orthogonal. A literal can fire this rule (wrong
 * method) AND `OtelForbiddenAttributeRule` Mode B (raw IP value) AND `RedisHashTagRule`
 * (legacy `rate:` prefix) independently — each rule enforces an independent invariant
 * and there is no cross-suppression.
 *
 * See the `rate-limit-infrastructure` capability spec ADDED requirement
 * "`IpAxisMustUseTryAcquireByKeyRule` fences user-axis method on IP-axis keys" for the
 * authoritative invariant + scenarios.
 */
class IpAxisMustUseTryAcquireByKeyRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Use `RateLimiter.tryAcquireByKey(key, capacity, ttl)` for IP-axis " +
                    "bucketing — `tryAcquire(userId, key, ...)` is the user-axis entry " +
                    "point. The `{ip:...}` axis segment in the key MUST go through " +
                    "`tryAcquireByKey` so per-call telemetry does not include an " +
                    "IP-derived `user_id`. See the `rate-limit-infrastructure` capability " +
                    "spec § \"Interface in :core:domain\" for the contract.",
            debt = Debt.TEN_MINS,
        )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression as? KtSimpleNameExpression ?: return
        if (callee.text != TARGET_CALLEE) return
        if (expression.valueArguments.size < MIN_ARG_COUNT) return

        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return

        val keyArg = findKeyArgument(expression) ?: return
        val keyExpr = keyArg.getArgumentExpression() as? KtStringTemplateExpression ?: return

        // KtStringTemplateExpression.text includes surrounding quote chars. Strip them
        // (triple-quote first — longer marker — then single quote) so the regex sees the
        // literal's content only. Template entries are rendered as their literal source
        // (`$id` or `${...}`), so the regex matches the static `{ip:` axis prefix
        // regardless of the value's runtime resolution. Same flat-content extraction
        // pattern as `RedisHashTagRule` + `OtelForbiddenAttributeRule`.
        val unquoted =
            keyExpr.text
                .removeSurrounding("\"\"\"")
                .removeSurrounding("\"")

        if (!IP_AXIS_PATTERN.containsMatchIn(unquoted)) return

        report(
            CodeSmell(
                issue,
                Entity.from(keyArg),
                issue.description,
            ),
        )
    }

    private fun findKeyArgument(call: KtCallExpression): KtValueArgument? {
        val args = call.valueArguments
        // Named-argument lookup wins over positional: a named `key = ...` ANYWHERE in
        // the argument list satisfies the lookup regardless of position. Same
        // precedence as `RateLimitTtlRule.argumentForName`.
        for (arg in args) {
            val argName = arg.getArgumentName()?.asName?.asString()
            if (argName == KEY_ARG_NAME) return arg
        }
        // Fall back to positional: only valid if NONE of the args are named (mixed
        // positional+named is legal in Kotlin but the positional index of the target
        // shifts in unpredictable ways; we conservatively skip).
        if (args.any { it.getArgumentName() != null }) return null
        return args.getOrNull(KEY_POSITIONAL_INDEX)
    }

    private fun isAllowedPath(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        if ("/src/test/" in normalized) return true
        // Synthetic-file harness via package-FQN fallback: detekt-test's `lint(String)`
        // overload gives synthetic files no real `virtualFilePath`, so the path check
        // fails on positive-case fixtures. The FQN fallback covers them — fixtures opt
        // into the test-path gate by declaring `package id.nearyou.test.fixtures.*` at
        // the top of the inline-Kotlin string.
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.test.fixtures" || pkg.startsWith("id.nearyou.test.fixtures.")) return true
        return false
    }

    companion object {
        const val RULE_ID: String = "IpAxisMustUseTryAcquireByKeyRule"

        private const val TARGET_CALLEE = "tryAcquire"
        private const val KEY_ARG_NAME = "key"

        // RateLimiter.tryAcquire(userId, key, capacity, ttl) — `key` is positional index 1.
        private const val KEY_POSITIONAL_INDEX = 1

        // At least `userId` + `key` must be present. Defensive — without this gate, a
        // malformed call like `tryAcquire()` or `tryAcquire(userId)` would trip the
        // `valueArguments[1]` lookup.
        private const val MIN_ARG_COUNT = 2

        // IP-axis match: `{<anything-not-}>ip:`. The `[^}]*` allows for an optional
        // `scope:` prefix segment followed by axis segments before `ip:` — handles
        // both `{ip:...}` directly and `{scope:foo}:{ip:...}` composite shapes.
        // Method-choice gate, NOT value-shape — passes regardless of the value after
        // `ip:` (`OtelForbiddenAttributeRule` Mode B handles value-shape).
        private val IP_AXIS_PATTERN: Regex = Regex("""\{[^}]*ip:""")
    }
}
