package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Forbids direct reads of the `X-Forwarded-For` header outside the canonical
 * `ClientIpExtractor`.
 *
 * Per the project critical-invariant in
 * [`CLAUDE.md`](../../../../../../../../CLAUDE.md) and
 * [`openspec/project.md`](../../../../../../../../openspec/project.md): client IP
 * MUST be read via the `clientIp` request-context value populated from
 * `CF-Connecting-IP` (with the documented precedence ladder). Direct `X-Forwarded-For`
 * reads bypass that ladder and the spoof-protection layer that the extractor enforces.
 *
 * The rule fires on Kotlin string literals containing `"X-Forwarded-For"` — the
 * grep-level shape catches `request.headers["X-Forwarded-For"]`,
 * `call.request.header("X-Forwarded-For")`, and
 * `call.request.headers.get("X-Forwarded-For")` uniformly.
 *
 * ## Allow-list
 *
 *  - `ClientIpExtractor.kt` (the canonical extractor) — exact filename match.
 *  - Synthetic in-memory test files (filename `Test.kt` from detekt-test's
 *    `lint(String)` overload) are NOT exempt — tests that exercise the rule
 *    naturally land at the `Test.kt` virtual path, but they always pass code
 *    fixtures that should fire OR not fire on their own merits; only the
 *    canonical extractor source path is structurally safe to bypass.
 */
class RawXForwardedForRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Direct reads of the `X-Forwarded-For` header are forbidden. " +
                    "Use `call.clientIp` (populated by the `ClientIpExtractor` Ktor plugin) " +
                    "instead. See docs/05-Implementation.md § Cloudflare-Fronted IP Extraction.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isExtractorFile(file)) return
        if (!XFF_PATTERN.containsMatchIn(expression.text)) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                issue.description,
            ),
        )
    }

    private fun isExtractorFile(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        val baseName = normalized.substringAfterLast('/')
        return baseName == EXTRACTOR_FILE_NAME
    }

    companion object {
        const val RULE_ID: String = "RawXForwardedForRule"
        const val EXTRACTOR_FILE_NAME: String = "ClientIpExtractor.kt"
        // Case-insensitive — header names are case-insensitive per HTTP RFC, and
        // both `X-Forwarded-For` and `x-forwarded-for` appear in the wild.
        private val XFF_PATTERN: Regex = Regex("""X-Forwarded-For""", RegexOption.IGNORE_CASE)
    }
}
