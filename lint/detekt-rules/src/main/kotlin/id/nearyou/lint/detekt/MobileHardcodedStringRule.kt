package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Forbids hardcoded UI copy in mobile composables — the `openspec/project.md` § Coding Conventions
 * "Mobile strings" invariant: UI text goes through Compose Multiplatform Resources
 * (`stringResource(Res.string.X)`), never a raw literal (issue #184).
 *
 * Flags a string literal passed as:
 *  - the first positional / `text =` argument of a `Text(...)` call;
 *  - any `contentDescription =` named argument.
 *
 * A literal counts as copy only when its static parts contain a letter, so formatting-only templates
 * (`"@$username"`, `"$a · $b"`) pass. Test source sets (`src/<name>Test/`) are exempt — test
 * composables may use throwaway literals. A genuine exception uses detekt's native
 * `@Suppress("MobileHardcodedStringRule")` with a reason comment.
 *
 * ponytail: syntactic only — a literal routed through a local `val` or a positional `Icon(_, "x")`
 * contentDescription escapes; widen when a regression slips through that shape.
 */
class MobileHardcodedStringRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Hardcoded UI string — mobile UI text must come from Compose Multiplatform " +
                    "Resources (`stringResource(Res.string.X)`), not a literal. Add the key to " +
                    "`shared/resources` strings.xml. See openspec/project.md § Coding Conventions.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val path = expression.containingKtFile.virtualFilePath.replace('\\', '/')
        if (TEST_SOURCE_SET.containsMatchIn(path)) return
        val isText = (expression.calleeExpression as? KtSimpleNameExpression)?.getReferencedName() == "Text"
        expression.valueArguments.forEachIndexed { index, arg ->
            val name = arg.getArgumentName()?.asName?.asString()
            val checked = name == "contentDescription" || (isText && (name == "text" || (name == null && index == 0)))
            val literal = arg.getArgumentExpression() as? KtStringTemplateExpression ?: return@forEachIndexed
            if (checked && literal.entries.any { it is KtLiteralStringTemplateEntry && it.text.any(Char::isLetter) }) {
                report(CodeSmell(issue, Entity.from(literal), issue.description))
            }
        }
    }

    companion object {
        const val RULE_ID: String = "MobileHardcodedStringRule"

        private val TEST_SOURCE_SET: Regex = Regex("/src/\\w*Test/")
    }
}
