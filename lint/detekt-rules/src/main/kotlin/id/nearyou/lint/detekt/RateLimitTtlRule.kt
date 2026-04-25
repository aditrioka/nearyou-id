package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids `RateLimiter.tryAcquire(...)` calls whose `key` argument is a daily-window
 * rate-limit key but whose `ttl` argument is NOT a `computeTTLToNextReset(...)` call.
 *
 * The canonical formula in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md)
 * §1751-1755 distributes daily-quota resets across the 00:00–01:00 WIB window via
 * `offset_seconds = abs(userId.hashCode().toLong()) % 3600`. Hardcoding `Duration.ofDays(1)`
 * or `86400` at a daily-window call site reintroduces the thundering-herd flush at midnight
 * that the WIB stagger is designed to prevent. This rule enforces the helper-call
 * convention at commit time.
 *
 * A "daily-window key" is detected by either substring `_day}` or a regex match against
 * `^\{scope:rate_[a-z_]+_day\}` in the text of the `key` argument expression. The
 * `key` and `ttl` arguments are looked up by parameter name first; if missing, by
 * positional index (`tryAcquire(userId, key, capacity, ttl)`).
 *
 * Allowed contexts (rule does NOT fire):
 *
 *  - **Test fixtures**: files whose `virtualFilePath` contains `/src/test/`. Tests
 *    construct daily-key calls with hardcoded TTL to exercise edge cases — they're not
 *    a runtime leak surface.
 *
 *  - **`@AllowDailyTtlOverride("<non-empty reason>")` annotation**: any `KtAnnotated`
 *    ancestor (function, class, property) annotated with `@AllowDailyTtlOverride("<reason>")`
 *    where `<reason>` is a non-empty, non-blank string literal. Empty-reason silent-bypass
 *    via `@AllowDailyTtlOverride()` or `@AllowDailyTtlOverride("")` is rejected — the
 *    annotation parameter is declared `String` (no default), and the rule additionally
 *    requires the value to be non-blank to close the bypass loophole.
 *
 * Composition with `RedisHashTagRule`: the two rules are orthogonal. A daily-key call
 * with hardcoded TTL trips this rule; a malformed-hash-tag literal trips the other.
 *
 * See the `rate-limit-infrastructure` capability spec ADDED requirement
 * "RateLimitTtlRule Detekt rule fences daily-window TTL" for the authoritative invariant.
 */
class RateLimitTtlRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Daily-window `RateLimiter.tryAcquire(...)` call MUST pass " +
                    "`computeTTLToNextReset(userId)` as `ttl` — hardcoded `Duration.ofDays(1)`, " +
                    "`86400`, or any other fixed value reintroduces the WIB-midnight thundering " +
                    "herd. To bypass, annotate the enclosing declaration " +
                    "`@AllowDailyTtlOverride(\"<non-empty reason>\")`. See the " +
                    "`rate-limit-infrastructure` capability spec.",
            debt = Debt.TEN_MINS,
        )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != TARGET_CALLEE) return

        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        val keyArg = expression.argumentForName(KEY_ARG_NAME, KEY_POSITIONAL_INDEX) ?: return
        val ttlArg = expression.argumentForName(TTL_ARG_NAME, TTL_POSITIONAL_INDEX) ?: return

        val keyText = keyArg.text
        if (!DAILY_KEY_PATTERN.containsMatchIn(keyText)) return

        val ttlText = ttlArg.text
        if (COMPUTE_TTL_PATTERN.containsMatchIn(ttlText)) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                issue.description,
            ),
        )
    }

    private fun isAllowedPath(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        if ("/src/test/" in normalized) return true
        // Synthetic-file harness via package-FQN fallback (mirror of
        // `BlockExclusionJoinRule.isAllowedPath`): detekt-test's `lint(String)` overload
        // gives files no real virtualFilePath. Allow synthetic test fixtures whose package
        // FQN starts with `id.nearyou.test.fixtures.` to opt into the test-path gate.
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.test.fixtures" || pkg.startsWith("id.nearyou.test.fixtures.")) return true
        return false
    }

    private fun KtCallExpression.isInsideAllowedAnnotation(): Boolean {
        var ancestor: KtAnnotated? = getParentOfType<KtAnnotated>(strict = true)
        while (ancestor != null) {
            for (entry in ancestor.annotationEntries) {
                if (entry.shortName?.asString() != ALLOW_ANNOTATION_SHORT) continue
                val reasonArg = entry.valueArguments.firstOrNull()?.getArgumentExpression()
                val reasonText = reasonArg?.text ?: continue
                // Strip surrounding triple-quotes first (longer marker), then single quotes.
                val unwrapped =
                    reasonText
                        .removeSurrounding("\"\"\"")
                        .removeSurrounding("\"")
                if (unwrapped.isNotBlank()) return true
            }
            ancestor = ancestor.getParentOfType<KtAnnotated>(strict = true)
        }
        return false
    }

    private fun KtCallExpression.argumentForName(
        name: String,
        positionalIndex: Int,
    ): KtExpression? {
        val args = valueArguments
        // Named argument lookup wins over positional; a named arg ANYWHERE in the list
        // satisfies the lookup regardless of position.
        for (arg in args) {
            val argName = arg.getArgumentName()?.asName?.asString()
            if (argName == name) return arg.getArgumentExpression()
        }
        // Fall back to positional: only valid if NONE of the args are named (mixed
        // positional+named is allowed in Kotlin but the positional index of the target
        // shifts in unpredictable ways; we conservatively skip).
        if (args.any { it.getArgumentName() != null }) return null
        return args.getOrNull(positionalIndex)?.getArgumentExpression()
    }

    companion object {
        const val RULE_ID: String = "RateLimitTtlRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowDailyTtlOverride"

        private const val TARGET_CALLEE = "tryAcquire"
        private const val KEY_ARG_NAME = "key"
        private const val TTL_ARG_NAME = "ttl"

        // Function signature: tryAcquire(userId, key, capacity, ttl).
        private const val KEY_POSITIONAL_INDEX = 1
        private const val TTL_POSITIONAL_INDEX = 3

        // Daily-window key marker: either substring `_day}` or the strict scope-prefix.
        private val DAILY_KEY_PATTERN: Regex =
            Regex("""(_day}|\{scope:rate_[a-z_]+_day})""")

        // Detect `computeTTLToNextReset(...)` in the ttl argument's text. Short-name match;
        // a fully-qualified call like `id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset(u)`
        // also matches.
        private val COMPUTE_TTL_PATTERN: Regex = Regex("""\bcomputeTTLToNextReset\s*\(""")
    }
}
