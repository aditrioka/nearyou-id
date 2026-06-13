package id.nearyou.app.post

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.TargetType
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.Verdict
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext

/**
 * Premium post-editing orchestration (the `premium-post-editing` capability;
 * `docs/05` § Post Edit History + § Transactional Atomicity, verbatim).
 *
 * Mirrors [CreatePostService]: a single pooled connection on the bounded
 * [dbDispatcher] (`docs/11` §3.2), `autoCommit=false` / commit / rollback, the
 * `TextModerator.moderate` → `Verdict` gate (Reject → 400, Flag → in-tx
 * `moderation_queue` upsert) and the post-commit fire-and-forget [Layer3Moderator]
 * dispatch with OTel context propagation.
 *
 * Gate order ([edit]; design D2 — all BEFORE any post lookup so a Free / over-limit
 * caller learns NOTHING about the target post):
 *  1. Premium gate (`subscriptionStatus` in [PREMIUM_STATES]) → 403 `premium_required`.
 *  2. Per-user edit rate limit (design D8; distinct from the daily-post-cap limiter)
 *     → 429 + `Retry-After`.
 *  3. [ContentLengthGuard] (empty / over-length) → 400.
 *  4. The single-connection `FOR UPDATE` transaction.
 *
 * Inside the transaction (design D4 / D7 / D9 / D1):
 *  - select-for-edit `FOR UPDATE` (window + author + not-deleted).
 *  - 0 rows → author-scoped disambiguation (D7): author's own non-deleted row past
 *    the window → 409 `edit_window_expired`; else → uniform 404.
 *  - locked row: normalized new content == current → 400 `no_changes` (D9, before
 *    moderation, no snapshot/update); else moderate (D1): Reject → 400, Flag →
 *    in-tx queue row; insert the before-edit snapshot; update content; commit.
 *  - single app-level retry on the `post_edits_temporal_idx` `unique_violation`
 *    (sub-µs collision); persistent collision → 409 "Coba lagi sebentar." (D4).
 */
class PostEditService(
    private val dataSource: DataSource,
    private val posts: PostRepository,
    private val contentGuard: ContentLengthGuard,
    private val textModerator: TextModerator,
    private val moderationQueue: ModerationQueueRepository,
    private val rateLimiter: PostEditRateLimiter = PostEditRateLimiter(),
    private val remoteConfig: RemoteConfig,
    private val layer3DispatcherScope: Layer3DispatcherScope? = null,
    private val layer3Moderator: Layer3Moderator? = null,
    private val clock: () -> Instant = Instant::now,
    // Pool-bounded JDBC/Redis-sync dispatcher (docs/11 §3.2); production passes
    // DbDispatchers.db — the default keeps direct-construction tests working.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Validates + applies a content edit inside a single race-safe DB transaction.
     * Returns the post id + the new content for the route to render.
     *
     * Exceptions propagate to the route / StatusPages:
     *  - [PostEditPremiumRequiredException] → 403 `premium_required`
     *  - [PostEditRateLimitedException] → 429 + `Retry-After`
     *  - [ContentEmptyException] / [ContentTooLongException] → 400
     *  - [PostEditNoChangesException] → 400 `no_changes`
     *  - [ContentModeratedProfanityException] → 400 `content_moderated_profanity`
     *  - [PostEditWindowExpiredException] → 409 `edit_window_expired`
     *  - [PostEditNotFoundException] → 404 (uniform — non-leaky per D7)
     *  - [PostEditConflictException] → 409 "Coba lagi sebentar."
     */
    suspend fun edit(
        authorId: UUID,
        postId: UUID,
        rawContent: String?,
        subscriptionStatus: String,
    ): EditedPost {
        // 1. Premium gate — BEFORE any post lookup (a Free caller learns nothing
        //    about the target). Accepts premium_active + premium_billing_retry (D2).
        if (subscriptionStatus !in PREMIUM_STATES) {
            throw PostEditPremiumRequiredException()
        }

        // 2. Per-user edit rate limit (D8) — distinct limiter key from the daily-post
        //    cap. Cap is ops-tunable via Remote Config (resolveEditCap). Runs on the
        //    bounded dispatcher (Lettuce sync blocks the calling thread).
        val cap = resolveEditCap(authorId)
        val rlOutcome =
            withContext(dbDispatcher) {
                rateLimiter.tryAcquire(authorId, capacityOverride = cap)
            }
        if (rlOutcome is PostEditRateLimiter.Outcome.RateLimited) {
            throw PostEditRateLimitedException(rlOutcome.retryAfterSeconds)
        }

        // 3. Length + normalize (throws ContentEmpty / ContentTooLong → 400). BEFORE
        //    the transaction so an empty/over-length edit consumes no DB/lock budget.
        val newContent = contentGuard.enforce(CONTENT_KEY, rawContent)

        // 4. Race-safe transaction with a single app-level retry on the temporal
        //    unique_violation edge (D4).
        var attempt = 0
        while (true) {
            try {
                return runEditTransaction(authorId, postId, newContent)
            } catch (ex: SQLException) {
                // post_edits_temporal_idx sub-µs collision (clock_timestamp() tie under
                // FOR UPDATE serialization). Retry ONCE; persistent collision → 409.
                if (ex.sqlState == UNIQUE_VIOLATION_SQLSTATE && attempt == 0) {
                    attempt++
                    log.warn(
                        "event=post_edit_temporal_collision_retry post_id={} attempt={}",
                        postId,
                        attempt,
                    )
                    continue
                }
                if (ex.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                    throw PostEditConflictException()
                }
                throw ex
            }
        }
    }

    /**
     * One attempt of the edit transaction. Throws [PostEditNoChangesException] /
     * [ContentModeratedProfanityException] / [PostEditWindowExpiredException] /
     * [PostEditNotFoundException] for the terminal failure modes, or rethrows a
     * temporal [SQLException] for the caller's single retry. On success, fires the
     * post-commit Layer-3 dispatch and returns the edited post.
     */
    private suspend fun runEditTransaction(
        authorId: UUID,
        postId: UUID,
        newContent: String,
    ): EditedPost {
        // Moderation verdict computed INSIDE the locked section (after the no-op check)
        // but referenced after commit for the Layer-3 dispatch decision.
        var moderationVerdict: Verdict = Verdict.Allow
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val locked = posts.selectForEdit(conn, postId = postId, authorId = authorId)
                    if (locked == null) {
                        // 0-row FOR UPDATE — disambiguate (D7) WITHOUT leaking existence.
                        val eligibility = posts.eligibilityForEdit(conn, postId = postId, authorId = authorId)
                        // Author's own, non-deleted row merely past the window → 409;
                        // everything else (non-author / non-existent / author's own
                        // soft-deleted) → uniform 404.
                        if (eligibility != null && !eligibility.softDeleted) {
                            throw PostEditWindowExpiredException()
                        }
                        throw PostEditNotFoundException()
                    }

                    // No-op edit (D9): normalized new == current (from the locked row).
                    // Checked BEFORE moderation; no snapshot, no update.
                    if (newContent == locked.content) {
                        throw PostEditNoChangesException()
                    }

                    // Re-moderate the edited content (D1) — closes the create-clean-then-
                    // edit-toxic laundering path. Reject → 400; Flag → in-tx queue row;
                    // Allow → proceed. The UPDATE below keeps this preceding moderate()
                    // call so ContentWriteRequiresModerationRule is satisfied.
                    val verdict = textModerator.moderate(newContent)
                    if (verdict is Verdict.Reject) {
                        throw ContentModeratedProfanityException(verdict.matchedKeywords)
                    }
                    moderationVerdict = verdict

                    // Snapshot the BEFORE-edit content + location, then update.
                    posts.insertEditSnapshot(conn, postId = postId)
                    posts.updateContent(conn, postId = postId, newContent = newContent)
                    if (verdict is Verdict.Flag) {
                        moderationQueue.upsertUuIteKeywordMatchRow(
                            conn = conn,
                            targetType = ReportTargetType.POST,
                            targetId = postId,
                        )
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
        }

        // Layer 3 (Perspective / OpenAI Moderation) async dispatch — fire-and-forget,
        // AFTER the UPDATE/commit, mirroring CreatePostService. Lives here so a
        // rolled-back edit (including the Reject short-circuit, which throws before
        // this point) never produces a Layer 3 moderation row. Passing
        // `coroutineContext` propagates the OTel trace context (design D1 / 3.3).
        @Suppress("NAME_SHADOWING")
        val layer3DispatcherScope = layer3DispatcherScope

        @Suppress("NAME_SHADOWING")
        val layer3Moderator = layer3Moderator
        if (layer3DispatcherScope != null && layer3Moderator != null) {
            layer3DispatcherScope.dispatch(coroutineContext) {
                layer3Moderator.moderate(TargetType.POST, postId, newContent)
            }
        }
        // moderationVerdict is recorded for parity with the create path's in-tx Flag
        // handling; the post-commit dispatch is unconditional on a committed row
        // (matches CreatePostService — Layer 3 runs for Allow AND Flag).
        check(moderationVerdict !is Verdict.Reject) { "Reject must short-circuit before commit" }

        return EditedPost(id = postId, content = newContent, updatedAt = clock())
    }

    /**
     * Resolves the per-user edit cap (design D8) from Remote Config, mirroring
     * [id.nearyou.app.engagement.ReplyService] cap resolution: any failure mode
     * (unset / null / non-positive / oversized / SDK error) coerces to the
     * [PostEditRateLimiter.DEFAULT_CAP] ops baseline.
     */
    private fun resolveEditCap(userId: UUID): Int {
        val raw =
            try {
                remoteConfig.getLong(POST_EDIT_CAP_OVERRIDE_KEY)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} fallback=default user_id={} message={}",
                    POST_EDIT_CAP_OVERRIDE_KEY,
                    userId,
                    t.message,
                )
                return PostEditRateLimiter.DEFAULT_CAP
            }
        if (raw == null || raw <= 0L || raw > MAX_OVERRIDE_CAP) {
            return PostEditRateLimiter.DEFAULT_CAP
        }
        return raw.toInt()
    }

    companion object {
        const val CONTENT_KEY: String = "post.content"

        /** Remote Config key for the ops-tunable per-user edit cap (design D8). */
        const val POST_EDIT_CAP_OVERRIDE_KEY: String = "post_edit_cap_override"

        /** SQLState for a PostgreSQL UNIQUE-violation (post_edits_temporal_idx). */
        private const val UNIQUE_VIOLATION_SQLSTATE: String = "23505"

        /** Anti-typo upper-bound clamp for the override flag (mirrors ReplyService). */
        private const val MAX_OVERRIDE_CAP: Long = 10_000L

        // Mirrors CreatePostService.PREMIUM_STATES — the premium-gate value set is
        // duplicated per service (there is NO shared constant today; design D2). A
        // DRY refactor is deliberately out of scope.
        private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        private val log = LoggerFactory.getLogger(PostEditService::class.java)
    }
}

/** The post id + new content of a successful edit, for the route to render. */
data class EditedPost(
    val id: UUID,
    val content: String,
    val updatedAt: Instant,
)

/**
 * Thrown when a non-Premium caller attempts an edit. Mapped at StatusPages to HTTP
 * 403 with `error.code = "premium_required"`. Checked BEFORE any post lookup (D2),
 * so it never reveals anything about the target post.
 */
class PostEditPremiumRequiredException :
    RuntimeException("post editing requires a Premium subscription")

/**
 * Thrown when the per-user edit rate limit is exceeded (design D8). Mapped at
 * StatusPages to HTTP 429 with a `Retry-After` header carrying [retryAfterSeconds].
 */
class PostEditRateLimitedException(val retryAfterSeconds: Long) :
    RuntimeException("post edit rate limit exceeded; retry after $retryAfterSeconds s")

/**
 * Thrown when the normalized new content equals the post's current content (design
 * D9). Mapped at StatusPages to HTTP 400 with `error.code = "no_changes"`. No
 * snapshot is written and `posts.updated_at` is unchanged.
 */
class PostEditNoChangesException :
    RuntimeException("edit content is identical to the current content")

/**
 * Thrown when the requester's own post exists and is not soft-deleted but was created
 * more than 30 minutes ago (design D7). Mapped at StatusPages to HTTP 409 with
 * `error.code = "edit_window_expired"` — safe to reveal to the owner.
 */
class PostEditWindowExpiredException :
    RuntimeException("the 30-minute edit window has expired")

/**
 * Thrown for every non-leaky 0-row edit cause (design D7): non-author, non-existent,
 * or the author's own soft-deleted post. Mapped at StatusPages to a uniform HTTP 404
 * that does NOT confirm post existence.
 */
class PostEditNotFoundException :
    RuntimeException("post not found or not editable")

/**
 * Thrown when the `post_edits_temporal_idx` `unique_violation` persists after the
 * single app-level retry (design D4). Mapped at StatusPages to HTTP 409 with the
 * canonical Bahasa Indonesia message "Coba lagi sebentar."
 */
class PostEditConflictException :
    RuntimeException("concurrent edit temporal collision; retry")
