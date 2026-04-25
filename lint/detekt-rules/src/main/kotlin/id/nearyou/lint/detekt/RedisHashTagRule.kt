package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids Redis rate-limit key strings that don't conform to the
 * `{scope:<scope>}:{<axis>:<value>}` hash-tag format.
 *
 * Upstash Redis Cluster uses CRC16 hash slots; multi-key Lua scripts and MULTI/EXEC
 * operations only succeed when all keys map to the same slot. The hash-tag convention
 * `{...}:{...}` instructs the cluster to compute the slot from the wrapped substring
 * only — co-locating both segments. Production keys MUST use this form; otherwise
 * production traffic on Upstash cluster mode would raise CROSSSLOT errors mid-call.
 *
 * The rule fires on Kotlin string literals (`KtStringTemplateExpression`) whose source
 * text either:
 *
 *  - Starts with `rate:` — the legacy non-hash-tagged form (e.g., `rate:user:$id`).
 *    Pre-V9 limiter keys may have used this; new keys MUST use the wrapped form.
 *
 *  - Starts with `{scope:` AND does NOT match the strict `{scope:NAME}:{AXIS:VAL}`
 *    pattern (i.e., the second segment is not properly wrapped, or there's a stray
 *    space or the structure is malformed).
 *
 * Conforming literals (rule passes): `"{scope:rate_like_day}:{user:$id}"`,
 * `"{scope:rate_like_burst}:{user:$userId}"`,
 * `"{scope:rate_report}:{user:$userId}"` (V9 precedent).
 *
 * Non-conforming literals (rule fires): `"rate:user:$id"`, `"{scope:rate_x}:user:$id"`,
 * `"{scope:rate_x}:{user $id}"` (missing colon inside second segment).
 *
 * **Interpolation note:** the rule inspects the literal's source text. Simple-name
 * interpolation `$userId` is part of the AXIS:VALUE wrapped segment and matches the
 * pattern. Block interpolation `${userId}` introduces literal `{}` braces in the
 * source text that confuse the strict structural check; those literals will fire as
 * a false positive. Workaround: use simple-name interpolation, OR refactor the value
 * computation outside the literal, OR (last resort) annotate with
 * `@AllowRawRedisKey("...")` if the use case is genuinely outside the convention.
 *
 * Allowed contexts (rule does NOT fire):
 *
 *  - **Test fixtures**: files whose `virtualFilePath` contains `/src/test/`. Tests
 *    construct legacy or malformed keys to verify the rule itself fires.
 *
 *  - **`@AllowRawRedisKey("<non-empty reason>")` annotation**: any `KtAnnotated`
 *    ancestor annotated with `@AllowRawRedisKey("<reason>")` where `<reason>` is a
 *    non-empty, non-blank string literal. Empty-reason silent-bypass is rejected
 *    (same enforcement as `RateLimitTtlRule`'s `@AllowDailyTtlOverride`).
 *
 *  - **Synthetic-file harness via package-FQN fallback**: detekt-test's `lint(String)`
 *    overload gives files no real virtualFilePath. Synthetic fixtures with package
 *    `id.nearyou.test.fixtures.*` are treated as test paths.
 *
 * Composition with sibling rules: orthogonal to `RateLimitTtlRule` (which fences
 * daily-window TTL usage). A literal can fire only this rule, only that rule, or both
 * — they enforce independent invariants.
 *
 * See the `rate-limit-infrastructure` capability spec ADDED requirement
 * "RedisHashTagRule Detekt rule fences rate-limit key shape" for the authoritative
 * invariant + scenarios.
 */
class RedisHashTagRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Redis rate-limit key string MUST use hash-tag format " +
                    "`{scope:<scope>}:{<axis>:<value>}` for Upstash cluster slot " +
                    "co-location. Legacy `rate:...` form and malformed `{scope:` literals " +
                    "are rejected. To bypass, annotate the enclosing declaration " +
                    "`@AllowRawRedisKey(\"<non-empty reason>\")`. See the " +
                    "`rate-limit-infrastructure` capability spec.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)

        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        // The text of a KtStringTemplateExpression includes surrounding quote chars.
        // Strip them so the structural check operates on the literal's content only.
        val unquoted =
            expression.text
                .removeSurrounding("\"\"\"")
                .removeSurrounding("\"")

        val firesLegacy = unquoted.startsWith(LEGACY_PREFIX)
        val firesMalformedScope =
            unquoted.startsWith(SCOPE_PREFIX) && !STRICT_HASH_TAG_PATTERN.matches(unquoted)
        if (!firesLegacy && !firesMalformedScope) return

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
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.test.fixtures" || pkg.startsWith("id.nearyou.test.fixtures.")) return true
        return false
    }

    private fun KtStringTemplateExpression.isInsideAllowedAnnotation(): Boolean {
        var ancestor: KtAnnotated? = getParentOfType<KtAnnotated>(strict = true)
        while (ancestor != null) {
            for (entry in ancestor.annotationEntries) {
                if (entry.shortName?.asString() != ALLOW_ANNOTATION_SHORT) continue
                val reasonArg = entry.valueArguments.firstOrNull()?.getArgumentExpression()
                val reasonText = reasonArg?.text ?: continue
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

    companion object {
        const val RULE_ID: String = "RedisHashTagRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowRawRedisKey"

        private const val LEGACY_PREFIX = "rate:"
        private const val SCOPE_PREFIX = "{scope:"

        // Strict structural pattern for a conforming hash-tag rate-limit key:
        //   {scope:NAME}:{AXIS:VAL}
        // where NAME, AXIS, VAL are non-brace sequences (allowing simple-name
        // interpolation `$id` but NOT block interpolation `${id}` — see KDoc).
        // Anchored to the full literal so a key fragment embedded in a longer string
        // doesn't slip through.
        private val STRICT_HASH_TAG_PATTERN: Regex =
            Regex("""^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$""")
    }
}
