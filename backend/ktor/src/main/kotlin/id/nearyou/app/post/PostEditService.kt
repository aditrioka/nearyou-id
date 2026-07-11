package id.nearyou.app.post

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.Layer3DispatcherScope
import id.nearyou.app.moderation.Layer3Moderator
import id.nearyou.app.moderation.TargetType
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.moderation.Verdict
import id.nearyou.app.subscription.PREMIUM_STATES
import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.ReportTargetType
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Premium post-editing orchestration (the `premium-post-editing` capability;
 * `docs/05` § Post Edit History + § Transactional Atomicity, verbatim).
 *
 * Mirrors [CreatePostService]: the `TextModerator.moderate` → `Verdict` gate runs
 * BEFORE the connection is opened (so a pooled connection / `FOR UPDATE` row lock is
 * never held across the moderator's Redis/Remote-Config I/O — `docs/11` §3.2), then a
 * single pooled connection on the bounded [dbDispatcher] does `autoCommit=false` /
 * commit / rollback, and the post-commit fire-and-forget [Layer3Moderator] dispatch
 * propagates OTel context.
 *
 * Gate order ([edit]; design D2 — all BEFORE any post lookup so a Free / over-limit
 * caller learns NOTHING about the target post):
 *  1. Premium gate (`subscriptionStatus` in [PREMIUM_STATES]) → 403 `premium_required`.
 *  2. Per-user edit rate limit (design D8; distinct from the daily-post-cap limiter)
 *     → 429 + `Retry-After`.
 *  3. [ContentLengthGuard] (empty / over-length) → 400.
 *  4. Re-moderation of the new content (design D1) → Reject 400; Allow/Flag carry into
 *     the transaction. Runs OUTSIDE any connection (mirrors [CreatePostService]).
 *  5. The single-connection `FOR UPDATE` transaction.
 *
 * Inside the transaction (design D4 / D7 / D9 / D1):
 *  - select-for-edit `FOR UPDATE` (window + author + not-deleted).
 *  - 0 rows → author-scoped disambiguation (D7): author's own non-deleted row past
 *    the window → 409 `edit_window_expired`; else → uniform 404.
 *  - locked row: normalized new content == current → 400 `no_changes` (D9; no
 *    snapshot/update). Identical-to-live content is never a Reject verdict, so the
 *    no-op path is reached for genuine re-saves even though moderation ran first.
 *  - else: insert the before-edit snapshot; update content; on a Flag verdict, write
 *    the in-tx `moderation_queue` row; commit.
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
     *  - [ContentModeratedProfanityException] → 400 `content_moderated_profanity`
     *  - [PostEditNoChangesException] → 400 `no_changes`
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

        // 4. Re-moderate the edited content (D1) — BEFORE opening the connection, so a
        //    pooled connection / FOR UPDATE row lock is NEVER held across the
        //    moderator's Redis/Remote-Config I/O (docs/11 §3.2; mirrors
        //    CreatePostService, which moderates outside its INSERT transaction). Reject
        //    → 400; Allow/Flag carry into the transaction (Flag writes the in-tx
        //    moderation_queue row). Runs on the bounded dispatcher (cold-cache I/O).
        val verdict = withContext(dbDispatcher) { textModerator.moderate(newContent) }
        if (verdict is Verdict.Reject) {
            throw ContentModeratedProfanityException(verdict.matchedKeywords)
        }

        // 5. Race-safe transaction with a single app-level retry on the temporal
        //    unique_violation edge (D4).
        var attempt = 0
        while (true) {
            try {
                return runEditTransaction(authorId, postId, newContent, verdict)
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
     * One attempt of the edit transaction. The new content has already been moderated
     * (see [edit] step 4) — [verdict] is Allow or Flag here (Reject short-circuits
     * before the connection is opened). Throws [PostEditNoChangesException] /
     * [PostEditWindowExpiredException] / [PostEditNotFoundException] for the terminal
     * failure modes, or rethrows a temporal [SQLException] for the caller's single
     * retry. On success, fires the post-commit Layer-3 dispatch and returns the edited
     * post.
     */
    private suspend fun runEditTransaction(
        authorId: UUID,
        postId: UUID,
        newContent: String,
        verdict: Verdict,
    ): EditedPost {
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
                    // No snapshot, no update. Moderation already ran before the
                    // transaction (step 4); identical-to-live content is never Reject,
                    // so this path is reached for genuine re-saves.
                    if (newContent == locked.content) {
                        throw PostEditNoChangesException()
                    }

                    // Snapshot the BEFORE-edit content + location, then update. The
                    // edited content was moderated before the transaction (D1); a Flag
                    // verdict records the in-tx moderation_queue row here.
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
        // rolled-back edit (including the pre-transaction Reject short-circuit) never
        // produces a Layer 3 moderation row. Runs for Allow AND Flag (the create-path
        // posture). Passing `coroutineContext` propagates the OTel trace context
        // (design D1 / 3.3).
        @Suppress("NAME_SHADOWING")
        val layer3DispatcherScope = layer3DispatcherScope

        @Suppress("NAME_SHADOWING")
        val layer3Moderator = layer3Moderator
        if (layer3DispatcherScope != null && layer3Moderator != null) {
            layer3DispatcherScope.dispatch(coroutineContext) {
                layer3Moderator.moderate(TargetType.POST, postId, newContent)
            }
        }

        return EditedPost(id = postId, content = newContent, updatedAt = clock())
    }

    /**
     * Resolves the per-user edit cap (design D8) from Remote Config, mirroring
     * [id.nearyou.app.engagement.ReplyService] cap resolution: any failure mode
     * (unset / null / non-positive / oversized / SDK error) coerces to the
     * [PostEditRateLimiter.DEFAULT_CAP] ops baseline, with the same structured
     * `remote_config_*` audit logs for ops parity.
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
            if (raw != null) {
                log.warn(
                    "event=remote_config_invalid key={} value={} fallback=default",
                    POST_EDIT_CAP_OVERRIDE_KEY,
                    raw,
                )
            }
            return PostEditRateLimiter.DEFAULT_CAP
        }
        log.info(
            "event=remote_config_override_applied key={} value={}",
            POST_EDIT_CAP_OVERRIDE_KEY,
            raw,
        )
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
