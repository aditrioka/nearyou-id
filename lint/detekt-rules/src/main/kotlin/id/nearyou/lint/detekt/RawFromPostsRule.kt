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
 * Forbids raw `FROM posts` / `JOIN posts` reads in business code.
 *
 * Business paths MUST query the `visible_posts` view (created in V4 migration) so that
 * `is_auto_hidden = TRUE` rows and future block-exclusion joins stay enforced at a single
 * chokepoint. See `docs/08-Roadmap-Risk.md` § Development Tools and the
 * `visible-posts-view` capability.
 *
 * Allowed exceptions:
 *  - Files under `.../app/post/repository/` whose name starts with `PostOwnContent`
 *    (own-content read paths that bypass shadow-ban visibility by design).
 *  - Files under `.../app/admin/` (admin module has full access).
 *  - Any `KtDeclaration` (or an enclosing one) annotated `@AllowRawPostsRead("<reason>")`.
 */
class RawFromPostsRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Business code must query the `visible_posts` view, not raw `posts`. " +
                    "See docs/08-Roadmap-Risk.md § Development Tools. To bypass, annotate the " +
                    "declaration `@AllowRawPostsRead(\"<reason>\")` or place the file under the " +
                    "admin module / `post/repository/PostOwnContent*.kt`.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        // `expression.text` preserves the original source form: `"..."` literals, raw strings,
        // and `$ref` placeholders are kept verbatim — enough for a case-insensitive regex on the
        // post identifier. Template literals are rare in SQL-ish strings so false negatives on
        // dynamic SQL are acceptable (docs/08: grep-level is OK for MVP).
        if (!POSTS_PATTERN.containsMatchIn(expression.text)) return

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
        // Admin module exempt.
        if ("/app/admin/" in normalized) return true
        // Own-content repositories exempt. File name check.
        val name = file.name
        if ("/app/post/repository/" in normalized && name.startsWith("PostOwnContent")) {
            return true
        }
        return false
    }

    private fun KtStringTemplateExpression.isInsideAllowedAnnotation(): Boolean {
        var ancestor: KtAnnotated? = getParentOfType<KtAnnotated>(strict = true)
        while (ancestor != null) {
            if (ancestor.annotationEntries.any { it.shortName?.asString() == ALLOW_ANNOTATION_SHORT }) {
                return true
            }
            ancestor = ancestor.getParentOfType<KtAnnotated>(strict = true)
        }
        return false
    }

    companion object {
        const val RULE_ID: String = "RawFromPostsRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowRawPostsRead"
        private val POSTS_PATTERN: Regex =
            Regex("""\b(?:FROM|JOIN)\s+posts\b""", RegexOption.IGNORE_CASE)
    }
}
