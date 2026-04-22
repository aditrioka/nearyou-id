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
 *  - Files under `.../app/moderation/` whose name starts with `Report` — these are
 *    the V9 report-submission paths (`ReportService`, `ReportRoutes`,
 *    `ReportController`, `ReportTargetResolver`, etc.) that run point-lookup
 *    existence checks (`SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL`
 *    and analogous queries for `post_replies` / `users` / `chat_messages`). Per the
 *    `visible-posts-view` spec delta shipped with V9, these must deliberately NOT
 *    go through `visible_posts` because reporting a blocked / blocked-by /
 *    shadow-banned / already-auto-hidden target is all valid behavior (four-case
 *    rationale in the `reports` capability design).
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
        // Admin module exempt (path OR package).
        if ("/app/admin/" in normalized) return true
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.app.admin" || pkg.startsWith("id.nearyou.app.admin.")) return true
        // Prefer basename of the physical path over `KtFile.name`, which is "Test.kt" for
        // synthetic in-memory files in detekt-test's `lint(String)` overload.
        val baseName = normalized.substringAfterLast('/')
        // Own-content repositories exempt. Path + filename prefix check.
        if ("/app/post/repository/" in normalized && baseName.startsWith("PostOwnContent")) {
            return true
        }
        // V9 report-submission module exempt — point-lookup existence checks on
        // posts / post_replies / users / chat_messages that must NOT go through
        // visible_posts (reporting blocked / shadow-banned / auto-hidden targets
        // is valid). Scoped to `Report*.kt` files under `.../app/moderation/` so
        // unrelated files in the same package (e.g. future
        // `ModerationDashboardReader.kt`) still fire the rule. Package-based
        // fallback (`id.nearyou.app.moderation`) mirrors admin's package check
        // for synthetic-file tests.
        val inModerationPath = "/app/moderation/" in normalized
        val inModerationPkg =
            pkg == "id.nearyou.app.moderation" || pkg.startsWith("id.nearyou.app.moderation.")
        if ((inModerationPath || inModerationPkg) && baseName.startsWith("Report")) {
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
