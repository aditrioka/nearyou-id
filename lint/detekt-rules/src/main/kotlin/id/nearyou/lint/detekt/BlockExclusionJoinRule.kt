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
 *
 * ### Why `follows` is deliberately NOT a protected table
 *
 * Readers encountering this rule after the V6 `follows` migration may wonder whether `follows`
 * is a missing entry in the protected-table set. It is not — the exclusion is deliberate, for
 * three reasons:
 *
 * 1. `follows` rows carry only `(follower_id, followee_id, created_at)` — no user-visible
 *    content (no `content`, `bio`, `display_name`, etc.). Protecting content tables prevents
 *    leaking text through a block; `follows` has nothing to leak.
 * 2. Business queries against `follows` are always caller-scoped: one side of the WHERE filter
 *    is `= :viewer` (the caller's own UUID). The caller can never observe a `follows` row
 *    whose composite identity doesn't involve themselves.
 * 3. Content surfaced THROUGH `follows` — the Following timeline — lands on `visible_posts`,
 *    where this rule re-checks for bidirectional `user_blocks` exclusion. The protection is
 *    applied at the content surface, not the relationship surface.
 *
 * Adding `follows` to the protected set would force every follow/unfollow endpoint to wrap
 * its `INSERT INTO follows` / `DELETE FROM follows` / `SELECT ... FROM follows` in an
 * irrelevant `user_blocks` bidirectional join, breaking those endpoints' semantics. See the
 * `block-exclusion-lint` spec ADDED requirement and the `follows_is_deliberately_not_protected_table`
 * test fixture — both encode this decision in code as a guardrail against a future
 * contributor adding `follows` out of misplaced completeness.
 *
 * ### Why `post_likes` is deliberately NOT a protected table
 *
 * V7 introduced `post_likes` and the same question arises: should the protected-table set
 * grow to include it? It should not — the exclusion is deliberate, for three reasons that
 * parallel the `follows` argument above:
 *
 * 1. `post_likes` rows carry only `(post_id, user_id, created_at)` — no user-visible
 *    content (no text body, no display name). Protecting content tables prevents leaking
 *    text through a block; `post_likes` has nothing to leak.
 * 2. Business queries against `post_likes` are always caller-scoped or aggregate: the
 *    write paths (`POST /like`, `DELETE /like`) filter on `user_id = :caller`; the count
 *    read (`GET /likes/count`) filters on `post_id = :post_id` and JOINs `visible_users`
 *    for shadow-ban exclusion. A per-viewer `user_blocks` filter would leak the caller's
 *    block list via a counter delta (see `post-likes` spec — deliberate privacy tradeoff).
 * 3. Content surfaced THROUGH `post_likes` — the `liked_by_viewer` LEFT JOIN in both
 *    timelines — lands on `visible_posts`, where this rule re-checks for bidirectional
 *    `user_blocks` exclusion. The protection is applied at the content surface, not the
 *    relationship surface.
 *
 * Adding `post_likes` to the protected set would force `DELETE FROM post_likes WHERE
 * user_id = :caller` to wrap in an irrelevant `user_blocks` bidirectional join — blocking
 * callers from ever cleaning up their own likes, since they may be in a current block
 * relationship with the post author. The `post_likes_is_deliberately_not_protected_table`
 * test fixture encodes this decision in code as a guardrail against future contributors
 * adding `post_likes` out of misplaced completeness.
 *
 * ### `post_replies` — protected table, active as of V8
 *
 * `post_replies` has been in the protected-table regex since this rule was first written
 * (docs/05-Implementation.md §1833), but had no production Kotlin reader until V8. With
 * V8 (`post-replies-v8`), `JdbcPostReplyRepository.listByPost` is the first live reader —
 * a `FROM post_replies JOIN visible_users` literal that carries both `blocker_id =` and
 * `blocked_id =` fragments. From V8 forward the rule actively gates `post_replies` reads
 * in production. The positive-pass fixture `reply_list_query_with_bidirectional_block_exclusion`
 * and positive-fail fixture `reply_list_query_missing_blocked_id_fails` lock the canonical
 * literal shape — a future refactor that drops one of the NOT-IN subqueries trips the fail
 * fixture.
 *
 * `DELETE FROM post_replies` is NEVER produced by repository code (post-replies-v8 design
 * Decision 2 — soft-delete only, via UPDATE). The single legitimate hard-delete site is
 * the future tombstone/cascade worker, which lives in the admin module and is exempt via
 * the admin-path allowlist.
 *
 * ### Why `admin_regions` (and reference-data tables generally) is deliberately NOT protected
 *
 * V11 introduced `admin_regions` (kabupaten/kota + province polygons) and the same question
 * arises: should the protected-table set grow to include it? It should not — `admin_regions`
 * carries administrative boundary data, not user-visible content. Queries against it are
 * reference-data lookups (e.g., reverse-geocode inside the `posts_set_city_tg` BEFORE INSERT
 * trigger, admin-module polygon audits) that have no per-viewer surface and therefore no
 * meaningful concept of "viewer-blocked author."
 *
 * Adding `admin_regions` to the protected set would force the V11 trigger function and every
 * admin-module polygon SELECT to wrap in a nonsensical `user_blocks` bidirectional join —
 * `admin_regions` has no `author_id`, no user-scoped columns, and no privacy-affecting
 * contents. The rule is about preventing user-visible content from leaking through a block;
 * administrative boundary geometry is neither content nor blockable.
 *
 * The broader principle: reference-data tables (`admin_regions`, `reserved_usernames`,
 * `follows` — see above, etc.) stay outside the protected set. Content surfaces (`posts`,
 * `visible_posts`, `users`, `chat_messages`, `post_replies`) stay inside. Future contributors
 * adding spatial / reference tables should not add them to `PROTECTED_TABLE_PATTERN`; content
 * surfaced THROUGH those tables is re-checked at the content surface.
 *
 * The `admin_regions_is_deliberately_not_protected_table` test fixture encodes this decision
 * in code. See the `block-exclusion-lint` ADDED requirement "admin_regions is NOT a protected
 * table" (global-timeline-with-region-polygons spec delta) and the `region-polygons` capability.
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

    private fun KtBinaryExpression.isPlus(): Boolean = operationToken === KtTokens.PLUS

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
