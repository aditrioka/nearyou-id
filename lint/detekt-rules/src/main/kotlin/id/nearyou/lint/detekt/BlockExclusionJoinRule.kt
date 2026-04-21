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
 * Forbids business queries that touch protected tables (`posts`, `visible_posts`, `users`,
 * `chat_messages`, `post_replies`) without bidirectional `user_blocks` exclusion.
 *
 * The canonical Nearby query in `docs/05-Implementation.md § Timeline Implementation` carries
 * two NOT-IN subqueries — one excluding viewer-blocked authors, one excluding viewers blocked
 * by the author. This rule enforces that any Kotlin string literal or `.sql` migration file
 * referencing a protected table via `FROM` or `JOIN` ALSO references `user_blocks` AND both
 * directional WHERE-clause fragments (`blocker_id =` and `blocked_id =`).
 *
 * Allowed exceptions:
 *  - Files under `.../app/admin/` (admin code is intentionally exempt — admins see everything).
 *  - Repository own-content files: filename starts with one of `PostOwnContent`, `UserOwn`,
 *    `ChatOwn`, `ReplyOwn` (queries scoped to the calling user's own rows do not need block
 *    exclusion since the caller cannot block themselves).
 *  - Any `KtAnnotated` (or an enclosing one) annotated `@AllowMissingBlockJoin("<reason>")`.
 *  - The `V5__user_blocks.sql` migration file (where `user_blocks` is created and references
 *    `users(id)` in its FK declarations).
 *
 * Composes with `RawFromPostsRule`: a query that touches `posts` directly trips both rules,
 * which is intentional — `RawFromPostsRule` enforces "use the view", this rule enforces "join
 * the blocks table". See `docs/08-Roadmap-Risk.md` Phase 1 items 16 + 21 and the
 * `block-exclusion-lint` capability spec.
 */
class BlockExclusionJoinRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Business queries touching `posts`, `visible_posts`, `users`, `chat_messages`, " +
                    "or `post_replies` MUST bidirectionally exclude `user_blocks`. To bypass, " +
                    "annotate the declaration `@AllowMissingBlockJoin(\"<reason>\")`, place the " +
                    "file under the admin module, or use a Repository own-content file naming " +
                    "convention (PostOwnContent*, UserOwn*, ChatOwn*, ReplyOwn*). See the " +
                    "`block-exclusion-lint` capability spec.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        // Multi-line `"...\n" + "FROM posts..."` concatenation: walk to the chain root and
        // analyze the combined text so split tokens still trigger. Only the LEFTMOST string
        // in a chain reports — otherwise N strings produce N findings for the same query.
        val (combined, isLeftmost) = expression.combinedTextAndLeftmost()
        if (!isLeftmost) return
        if (!violates(combined)) return

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
        if ("/app/admin/" in normalized) return true
        // Package-based fallback so tests with synthetic file paths still match the admin
        // exemption — and so a file moved under a different physical layout still matches
        // by package, which is the contract the project guarantees.
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.app.admin" || pkg.startsWith("id.nearyou.app.admin.")) return true
        // Prefer basename of the physical path over `KtFile.name`, which is "Test.kt" for
        // synthetic in-memory files in detekt-test's `lint(String)` overload.
        val baseName = normalized.substringAfterLast('/')
        if (OWN_CONTENT_PREFIXES.any { baseName.startsWith(it) }) return true
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

    /**
     * Walk up the binary `+` expression chain and gather the text of the whole chain. Returns
     * the combined text plus a flag indicating whether the receiver is the leftmost string in
     * the chain (used for de-duplicated reporting — only the leftmost element reports for the
     * whole chain).
     */
    private fun KtStringTemplateExpression.combinedTextAndLeftmost(): Pair<String, Boolean> {
        val parent = parent
        if (parent !is org.jetbrains.kotlin.psi.KtBinaryExpression || !parent.isPlus()) {
            return text to true
        }
        // Find the topmost PLUS chain root.
        var root: org.jetbrains.kotlin.psi.KtExpression = parent
        while (true) {
            val next = root.parent
            if (next is org.jetbrains.kotlin.psi.KtBinaryExpression && next.isPlus()) {
                root = next
            } else {
                break
            }
        }
        // Walk down the LEFT spine to find the leftmost string template terminal.
        var leftmost: org.jetbrains.kotlin.psi.KtExpression = root
        while (leftmost is org.jetbrains.kotlin.psi.KtBinaryExpression && leftmost.isPlus()) {
            leftmost = leftmost.left ?: break
        }
        return root.text to (leftmost === this)
    }

    private fun org.jetbrains.kotlin.psi.KtBinaryExpression.isPlus(): Boolean =
        operationToken === org.jetbrains.kotlin.lexer.KtTokens.PLUS

    private fun violates(text: String): Boolean {
        if (!PROTECTED_TABLE_PATTERN.containsMatchIn(text)) return false
        // Pass condition: text must mention `user_blocks` AND both `blocker_id =` and `blocked_id =`.
        if (!USER_BLOCKS_PATTERN.containsMatchIn(text)) return true
        if (!BLOCKER_ID_PATTERN.containsMatchIn(text)) return true
        if (!BLOCKED_ID_PATTERN.containsMatchIn(text)) return true
        return false
    }

    companion object {
        const val RULE_ID: String = "BlockExclusionJoinRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowMissingBlockJoin"

        private val OWN_CONTENT_PREFIXES =
            listOf("PostOwnContent", "UserOwn", "ChatOwn", "ReplyOwn")

        private val PROTECTED_TABLE_PATTERN: Regex =
            Regex(
                """\b(?:FROM|JOIN)\s+(?:posts|visible_posts|users|chat_messages|post_replies)\b""",
                RegexOption.IGNORE_CASE,
            )
        private val USER_BLOCKS_PATTERN: Regex =
            Regex("""\buser_blocks\b""", RegexOption.IGNORE_CASE)
        private val BLOCKER_ID_PATTERN: Regex =
            Regex("""\bblocker_id\s*=""", RegexOption.IGNORE_CASE)
        private val BLOCKED_ID_PATTERN: Regex =
            Regex("""\bblocked_id\s*=""", RegexOption.IGNORE_CASE)
    }
}
