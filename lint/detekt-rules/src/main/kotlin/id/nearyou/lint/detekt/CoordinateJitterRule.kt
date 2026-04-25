package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids Kotlin string literals that reference the `actual_location` column in business code.
 *
 * The `coordinate-jitter` capability specifies that non-admin read paths MUST query
 * `display_location` (the HMAC-fuzzed 50–500 m offset computed by `JitterEngine`) and NEVER
 * the raw `actual_location` column. Prior to this rule the invariant was enforced only by
 * code review + a Phase 2 audit item (`docs/08-Roadmap-Risk.md` Phase 2 item 15). This rule
 * moves it to commit time, mirroring the shape of `RawFromPostsRule` and `BlockExclusionJoinRule`.
 *
 * Protected pattern: any Kotlin string literal whose source text matches `\bactual_location\b`
 * (case-insensitive). SQL identifiers are case-insensitive by convention, so the regex
 * catches `actual_location`, `ACTUAL_LOCATION`, and `Actual_Location`.
 *
 * Allowed contexts (rule does NOT fire):
 *
 *  - **Admin module**: files under `/app/admin/` (by path) OR whose package is
 *    `id.nearyou.app.admin` / a sub-package thereof. The admin module has full coordinate
 *    access by design — admins see raw locations for moderation / audit tooling.
 *
 *  - **Test fixtures**: files whose `virtualFilePath` contains `/src/test/`. Tests seed
 *    data via raw SQL INSERT statements that legitimately reference `actual_location` as a
 *    column name. Migration smoke tests also embed SQL that touches `actual_location` to
 *    assert schema invariants. This is fixture code, not a production leak surface.
 *
 *  - **Post write-path repositories**: files whose basename starts with one of
 *    `JdbcPostRepository`, `CreatePostService`, `PostOwnContent`. These are the sanctioned
 *    INSERT-into-posts code paths; they write `actual_location` as part of post creation and
 *    own-content repositories read it back for the authoring user's own posts. Mirrors the
 *    `PostOwnContent*` exemption in `RawFromPostsRule`.
 *
 *  - **V9 report-submission module**: files whose basename starts with `Report` AND live
 *    under `/app/moderation/` OR in the `id.nearyou.app.moderation` package. Per
 *    `RawFromPostsRule`'s allowlist rationale, report-submission paths do point-lookup
 *    existence checks against `posts` / `post_replies` / `users` / `chat_messages` that must
 *    deliberately NOT go through `visible_posts` (reporting a blocked / shadow-banned /
 *    auto-hidden target is valid). Those same queries can mention `actual_location` if the
 *    report carries a geotagged snapshot for moderation context. Scoping to `Report*.kt`
 *    keeps the exemption tight — unrelated files in the same package (e.g. a future
 *    `ModerationDashboardReader.kt`) still fire the rule.
 *
 *  - **`@AllowActualLocationRead("<reason>")` annotation**: any `KtAnnotated` ancestor
 *    (class, function, property) annotated with `@AllowActualLocationRead("<reason>")`
 *    exempts string literals inside that declaration. Mirror of `@AllowMissingBlockJoin` +
 *    `@AllowRawPostsRead`. Required for future admin tools that fall outside the blanket
 *    path allowlist.
 *
 * ### Why `.sql` migration file scanning is deliberately NOT in scope
 *
 * Detekt is a Kotlin linter — it visits Kotlin PSI, not raw SQL. The V11 migration's trigger
 * body references `NEW.actual_location` as part of the `posts_set_city_tg` fallback ladder;
 * that file is reviewed in PRs, not lint-gated here. What *is* gated: Kotlin string-literal
 * copies of that SQL (in migration smoke tests, repos, or anywhere else Kotlin code embeds
 * SQL referring to `actual_location`). That covers the leak surface that actually matters —
 * a runtime Kotlin reader accidentally projecting `actual_location` into a non-admin
 * business response.
 *
 * ### Why positive `display_location` enforcement is NOT in scope
 *
 * The rule is purely negative — it forbids `actual_location`. It does not require
 * `display_location` to appear. Kotlin code can legitimately contain neither (e.g., a SELECT
 * of just `created_at`). Positive enforcement of "every read path surfaces display_location"
 * is what the Phase 2 audit item in `docs/08-Roadmap-Risk.md` + the end-to-end scenario in
 * the `coordinate-jitter` capability's "actual_location absent from nearby response" test do.
 *
 * ### Composition with sibling rules
 *
 * - `RawFromPostsRule` — "use the view". Can compose: a query against `visible_posts` that
 *   projects `actual_location` still fires *this* rule, even though `RawFromPostsRule`
 *   passes. That's intentional: `visible_posts` is `SELECT * FROM posts WHERE …`, so it
 *   exposes `actual_location` if a caller asks for it.
 * - `BlockExclusionJoinRule` — "join the blocks table". Orthogonal. A business query reading
 *   `actual_location` with bidirectional `user_blocks` joins would still fire *this* rule.
 *
 * See the `coordinate-jitter` capability spec ADDED requirements ("CoordinateJitterRule
 * fences actual_location reads in Kotlin source") for the authoritative invariant + scenarios.
 */
class CoordinateJitterRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Non-admin code MUST query `display_location` (the HMAC-fuzzed coordinate), " +
                    "never `actual_location`. To bypass, annotate the declaration " +
                    "`@AllowActualLocationRead(\"<reason>\")`, place the file under the admin " +
                    "module, or use a post write-path filename prefix (JdbcPostRepository, " +
                    "CreatePostService, PostOwnContent*). See the `coordinate-jitter` " +
                    "capability spec.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        // Multi-line `"...\n" + "actual_location..."` concatenation: walk to the chain root
        // and analyze the combined text so split tokens still trigger. Only the LEFTMOST
        // string in a chain reports — otherwise N strings produce N findings for the same
        // query. Same approach as BlockExclusionJoinRule.combinedTextAndLeftmost.
        val (combined, isLeftmost) = expression.combinedTextAndLeftmost()
        if (!isLeftmost) return
        if (!ACTUAL_LOCATION_PATTERN.containsMatchIn(combined)) return

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
        if ("/src/test/" in normalized) return true
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.app.admin" || pkg.startsWith("id.nearyou.app.admin.")) return true
        // Prefer basename of the physical path over `KtFile.name`, which is "Test.kt" for
        // synthetic in-memory files in detekt-test's `lint(String)` overload.
        val baseName = normalized.substringAfterLast('/')
        if (POST_WRITE_PATH_PREFIXES.any { baseName.startsWith(it) }) return true
        // V9 report-submission module: Report*.kt under moderation path or package.
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

    /**
     * Walk up the binary `+` expression chain and gather the text of the whole chain. Returns
     * the combined text plus a flag indicating whether the receiver is the leftmost string in
     * the chain (used for de-duplicated reporting — only the leftmost element reports for the
     * whole chain). Mirror of the same helper in `BlockExclusionJoinRule`.
     */
    private fun KtStringTemplateExpression.combinedTextAndLeftmost(): Pair<String, Boolean> {
        val parent = parent
        if (parent !is KtBinaryExpression || !parent.isPlus()) {
            return text to true
        }
        var root: KtExpression = parent
        while (true) {
            val next = root.parent
            if (next is KtBinaryExpression && next.isPlus()) {
                root = next
            } else {
                break
            }
        }
        var leftmost: KtExpression = root
        while (leftmost is KtBinaryExpression && leftmost.isPlus()) {
            leftmost = leftmost.left ?: break
        }
        return root.text to (leftmost === this)
    }

    private fun KtBinaryExpression.isPlus(): Boolean = operationToken === KtTokens.PLUS

    companion object {
        const val RULE_ID: String = "CoordinateJitterRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowActualLocationRead"

        private val POST_WRITE_PATH_PREFIXES =
            listOf("JdbcPostRepository", "CreatePostService", "PostOwnContent")

        private val ACTUAL_LOCATION_PATTERN: Regex =
            Regex("""\bactual_location\b""", RegexOption.IGNORE_CASE)
    }
}
