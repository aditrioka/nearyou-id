package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids content-write call sites against `posts.content` / `post_replies.content` /
 * `chat_messages.content` whose enclosing function does NOT also invoke
 * `TextModerator.moderate(...)` BEFORE the write call.
 *
 * Sibling to the existing test-time enforcement at
 * [`PostCreationModerationIntegrationTest.kt:477`](../../../../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/post/PostCreationModerationIntegrationTest.kt),
 * [`PostRepliesModerationIntegrationTest.kt:406`](../../../../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/engagement/PostRepliesModerationIntegrationTest.kt),
 * and [`ChatModerationIntegrationTest.kt:442`](../../../../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/chat/ChatModerationIntegrationTest.kt) —
 * the 3 static-source-scan integration tests catch the regression at TEST time; this
 * rule shifts the same enforcement to COMPILE time. The two layers form defense-in-
 * depth on the call-order contract: compile-time call-site mechanical (this rule,
 * `:backend:ktor` source-set scope) + test-time end-to-end behaviour (the 3 existing
 * integration tests, full call-graph including `:infra:supabase` Repository writes).
 *
 * ## Candidate write-call surfaces
 *
 * The rule's two-arm match-set (per `content-moderation-keyword-lists/spec.md` §
 * "ContentWriteRequiresModerationRule fences content-write call sites"):
 *
 *  - **Arm (a) — SQL string literal**: any `KtStringTemplateExpression` whose flat
 *    text matches `(?i)\bINSERT\s+INTO\s+(posts|post_replies|chat_messages)\b[^)]*\bcontent\b`.
 *    Covers raw JDBC INSERT call sites. As of this change the only in-`:backend:ktor`
 *    site this catches is `ChatRepository.kt`'s inlined chat-message INSERTs; the
 *    `JdbcPostRepository.kt` and `JdbcPostReplyRepository.kt` SQL literals live in
 *    `:infra:supabase` (NOT in Detekt's source-set scope).
 *  - **Arm (b) — Service-method call**: `KtCallExpression`s whose callee short-name
 *    + receiver-expression text matches one of the three production-verified shapes:
 *      - `posts.create(conn, ...)` — `CreatePostService.kt:97`
 *      - `replies.insertInTx(conn, ...)` — `ReplyService.kt:155`
 *      - `repository.sendMessage(...)` — `ChatService.kt:256`
 *
 *    These receiver names are Kotlin idiom for constructor-injected dependencies.
 *    Matching on `(receiver, callee)` short-name pairs avoids false positives on
 *    unrelated classes that happen to expose a `create(...)` method.
 *
 * ## Detection mechanism — PSI walk for `TextModerator.moderate` ancestry
 *
 * For each candidate write call, walk UP to the enclosing `KtNamedFunction` (or
 * `KtFunctionLiteral` for lambda contexts), then walk DOWN the function body
 * collecting all `KtCallExpression`s. Filter to those whose callee short-name is
 * `moderate` AND whose receiver-expression text matches `textModerator` /
 * `TextModerator`, AND whose `textOffset` PSI-precedes the candidate's `textOffset`.
 * If any qualifying call exists, the rule passes; otherwise check the annotation
 * bypass below.
 *
 * Known soundness gaps (accepted per `design.md` § Decision 7a):
 *
 *  - **Conditional moderator call**: `if (cond) textModerator.moderate(content); insertContent(content)`
 *    PSI-passes (the moderate call PSI-precedes the insert) but may not execute at
 *    runtime. Code review + the existing 3 integration tests cover the canonical 3 paths.
 *  - **Helper-extracted moderator call**: a helper function that delegates to
 *    `textModerator.moderate(content)` is invisible to this rule's function-local
 *    PSI walk. Mitigation: inline the moderate call, or annotate the write helper
 *    with `@AllowContentWriteWithoutModeration("<reason>")`.
 *
 * Closing these gaps would require Detekt type-resolution mode, which the existing
 * 7 rules deliberately avoid.
 *
 * ## Annotation bypass — `@AllowContentWriteWithoutModeration("<reason>")`
 *
 * A function annotated `@AllowContentWriteWithoutModeration("<reason>")` passes the
 * rule when ALL three conditions hold:
 *  - The reason text (after stripping surrounding quotes) is a string literal.
 *  - The reason is `isNotBlank()` (empty `""` or whitespace-only reasons fail).
 *  - The reason is one of the three enumerated values: `tombstone`, `admin_redaction`,
 *    `seed`.
 *
 * Non-enumerated reasons fire the rule with an error message naming the allowed set.
 * Empty-string reasons fire (mirror `RateLimitTtlRule`'s `@AllowDailyTtlOverride`
 * enforcement at `RateLimitTtlRule.kt:104-121`). The annotation target is
 * `FUNCTION` only — class/property-level bypass is out of scope per
 * `design.md` § Decision 7b. The annotation lives at
 * [`backend/ktor/src/main/kotlin/id/nearyou/app/lint/AllowContentWriteWithoutModeration.kt`](../../../../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/lint/AllowContentWriteWithoutModeration.kt)
 * alongside the existing `AllowMissingBlockJoin` + `AllowRawPostsRead`.
 *
 * ## Dual allowlist — test-source path + package-FQN fallback
 *
 * Mirrors the canonical 3-rule precedent
 * ([`RedisHashTagRule.kt:115-120`](RedisHashTagRule.kt),
 * [`RateLimitTtlRule.kt:92-100`](RateLimitTtlRule.kt),
 * [`BlockExclusionJoinRule.kt:176-186`](BlockExclusionJoinRule.kt)):
 *
 *  - Path-based: `containingKtFile.virtualFilePath` contains `/src/test/`.
 *  - Package-FQN fallback: `containingKtFile.packageFqName` equals `id.nearyou.test.fixtures`
 *    OR begins with `id.nearyou.test.fixtures.`. Required because Detekt's
 *    `lint(String)` overload synthesises files with `virtualFilePath = null`.
 *
 * ## Effective coverage scope
 *
 * The rule fires only on Kotlin sources scanned by the project Detekt invocation
 * (currently `:backend:ktor`'s `src/main/kotlin` per
 * [`build-logic/src/main/kotlin/nearyou.ktor.gradle.kts:20`](../../../../../../../../build-logic/src/main/kotlin/nearyou.ktor.gradle.kts)).
 * Repository-layer SQL INSERTs that live in `:infra:supabase` (`JdbcPostRepository.kt`,
 * `JdbcPostReplyRepository.kt`) are NOT scanned by this rule — the existing 3
 * integration tests remain the canonical defence for those write sites. Extending
 * the Detekt source-set scope is a separate cross-cutting change per `design.md` §
 * Decision 8.
 *
 * See the `content-moderation-keyword-lists` capability spec ADDED requirement
 * "`ContentWriteRequiresModerationRule` fences content-write call sites without
 * preceding `TextModerator.moderate`" for the authoritative invariant + scenarios.
 */
class ContentWriteRequiresModerationRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Content write into `posts.content`, `post_replies.content`, or `chat_messages.content` " +
                    "MUST be preceded by `TextModerator.moderate(content)` in the same function per " +
                    "`openspec/specs/content-moderation-keyword-lists/spec.md` § \"Moderator runs " +
                    "after length guard, before INSERT\"; OR annotate the enclosing function with " +
                    "`@AllowContentWriteWithoutModeration(\"<reason>\")` for documented carve-outs " +
                    "(allowed reasons: `tombstone`, `admin_redaction`, `seed`).",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (!INSERT_CONTENT_PATTERN.containsMatchIn(expression.text)) return
        checkCandidate(expression)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (!isServiceMethodCandidate(expression)) return
        checkCandidate(expression)
    }

    private fun isServiceMethodCandidate(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression?.text ?: return false
        if (callee !in SERVICE_METHOD_CALLEES) return false
        val parent = expression.parent as? KtDotQualifiedExpression ?: return false
        // The candidate KtCallExpression is the dotted-call's selector; confirm the call is
        // on the right side of the dot before reading the receiver text.
        if (parent.selectorExpression !== expression) return false
        val receiver = parent.receiverExpression.text
        return (receiver to callee) in SERVICE_METHOD_RECEIVER_CALLEE_PAIRS
    }

    /**
     * Common gate for both arms: a candidate PSI element (either the SQL-literal
     * `KtStringTemplateExpression` or the service-method `KtCallExpression`) passes the
     * rule when (a) the enclosing function contains a preceding `TextModerator.moderate(...)`
     * call OR (b) the enclosing function carries `@AllowContentWriteWithoutModeration("<reason>")`
     * with a non-blank, enumerated reason.
     */
    private fun checkCandidate(candidate: KtElement) {
        val enclosingFunction = enclosingFunction(candidate) ?: return
        if (hasPrecedingModerateCall(enclosingFunction, candidate)) return
        if (hasValidAllowAnnotation(enclosingFunction)) return
        report(CodeSmell(issue, Entity.from(candidate), issue.description))
    }

    private fun enclosingFunction(candidate: KtElement): KtElement? {
        val named = candidate.getParentOfType<KtNamedFunction>(strict = true)
        if (named != null) return named
        return candidate.getParentOfType<KtFunctionLiteral>(strict = true)
    }

    private fun hasPrecedingModerateCall(
        enclosingFunction: KtElement,
        candidate: KtElement,
    ): Boolean {
        val candidateOffset = candidate.textOffset
        return enclosingFunction
            .collectDescendantsOfType<KtCallExpression>()
            .any { call -> isModerateCall(call) && call.textOffset < candidateOffset }
    }

    private fun isModerateCall(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression?.text ?: return false
        if (callee != MODERATE_CALLEE) return false
        val parent = expression.parent as? KtDotQualifiedExpression ?: return false
        if (parent.selectorExpression !== expression) return false
        val receiver = parent.receiverExpression.text
        return receiver in MODERATOR_RECEIVERS
    }

    private fun hasValidAllowAnnotation(enclosingFunction: KtElement): Boolean {
        // Annotations live only on KtNamedFunction (not KtFunctionLiteral, which doesn't
        // carry annotationEntries — its KDoc target is `FUNCTION` per Decision 7b).
        val named = enclosingFunction as? KtNamedFunction ?: return false
        for (entry in named.annotationEntries) {
            if (entry.shortName?.asString() != ALLOW_ANNOTATION_SHORT) continue
            val reasonText =
                entry.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: continue
            val unwrapped =
                reasonText
                    .removeSurrounding("\"\"\"")
                    .removeSurrounding("\"")
            if (unwrapped.isBlank()) continue
            if (unwrapped !in ALLOWED_REASONS) continue
            return true
        }
        return false
    }

    private fun isAllowedPath(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        if ("/src/test/" in normalized) return true
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.test.fixtures" || pkg.startsWith("id.nearyou.test.fixtures.")) return true
        return false
    }

    companion object {
        const val RULE_ID: String = "ContentWriteRequiresModerationRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowContentWriteWithoutModeration"

        private const val MODERATE_CALLEE = "moderate"

        private val MODERATOR_RECEIVERS: Set<String> = setOf("textModerator", "TextModerator")

        private val SERVICE_METHOD_CALLEES: Set<String> = setOf("create", "insertInTx", "sendMessage")

        /**
         * Verified production receiver/callee pairs (see KDoc § Candidate write-call surfaces).
         * Match-set is exact-text on the dotted-expression receiver token; future additions
         * go through the spec amendment process.
         */
        private val SERVICE_METHOD_RECEIVER_CALLEE_PAIRS: Set<Pair<String, String>> =
            setOf(
                "posts" to "create",
                "replies" to "insertInTx",
                "repository" to "sendMessage",
            )

        private val ALLOWED_REASONS: Set<String> = setOf("tombstone", "admin_redaction", "seed")

        /**
         * SQL-INSERT-with-content-column pattern. The `[^)]*` between the table-name and
         * `\bcontent\b` is intentional and load-bearing: it stops at the FIRST `)` so that
         * `INSERT INTO posts (id, view_count) VALUES (?, ?)` does NOT match (the `content`
         * literal would have to appear inside the column list, before the closing `)`).
         * If a future contributor changes `[^)]*` to `.*?`, an unrelated `content` token
         * anywhere later in the SQL (e.g., a stored-procedure call inside VALUES) would
         * produce a false positive. The non-content-column negative scenario in
         * `ContentWriteRequiresModerationLintTest` locks this behaviour.
         */
        private val INSERT_CONTENT_PATTERN: Regex =
            Regex(
                """\bINSERT\s+INTO\s+(?:posts|post_replies|chat_messages)\b[^)]*\bcontent\b""",
                RegexOption.IGNORE_CASE,
            )
    }
}
