package id.nearyou.app.engagement

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.PostReplyRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Reply lifecycle: rate-limit gate / create / list / soft-delete.
 *
 * **Rate-limit ordering (see `reply-rate-limit` change § post-replies spec):**
 *  1. Auth (route-layer).
 *  2. UUID validation (route-layer).
 *  3. **Daily limiter** (Free only — Premium skips entirely): `{scope:rate_reply_day}:{user:U}`,
 *     capacity = `premium_reply_cap_override` Remote Config flag (default 20), ttl =
 *     `computeTTLToNextReset(userId)` (per-user WIB stagger). Implemented by [tryRateLimit].
 *     The route MUST call this BEFORE JSON body parse + content guard so a Free attacker
 *     spamming oversized payloads still consumes slots and hits 429 at the cap rather than
 *     burning unlimited "invalid_request" responses (design.md Decision 5).
 *  4. JSON body parse + V8 content-length guard (route-layer; runs AFTER limiter).
 *  5. `visible_posts` resolution (404 path does NOT release — slot was consumed).
 *  6. INSERT + V10 emit in one transaction.
 *  7. Return 201.
 *
 * **No `releaseMostRecent` on any path.** Unlike [LikeService.like], the reply handler has
 * no idempotent re-action equivalent (no `INSERT ... ON CONFLICT DO NOTHING` no-op). Every
 * successful POST is a real new row. 404 / 400 / 5xx all keep the slot consumed (anti-abuse).
 *
 * V10 notification coupling: [post] opens a single transaction for the reply INSERT +
 * `post_replied` emit. Emit failure rolls back the reply (and the slot stays consumed —
 * the limiter ran successfully before the transaction; the rollback is a downstream
 * outcome). Dispatch runs AFTER commit.
 *
 * Lettuce's sync API blocks the calling thread on Netty I/O; per the `RateLimiter`
 * contract (interface non-suspending to preserve V9 compatibility), the wrap belongs at
 * this call site. [tryRateLimit] runs `tryAcquire` inside `withContext(Dispatchers.IO)`.
 */
class ReplyService(
    private val dataSource: DataSource,
    private val replies: PostReplyRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
    private val rateLimiter: RateLimiter,
    private val remoteConfig: RemoteConfig,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * Outcome of [tryRateLimit]. Mirrors [LikeService.Result.RateLimited] / [Allowed]
     * shape for HTTP-layer mapping consistency. The route handler maps
     * [RateLimitOutcome.RateLimited] → HTTP 429 + `Retry-After` header; [Allowed]
     * advances to body parse + content guard + [post].
     */
    sealed interface RateLimitOutcome {
        data object Allowed : RateLimitOutcome

        data class RateLimited(val retryAfterSeconds: Long) : RateLimitOutcome
    }

    /**
     * Runs the daily limiter for the reply endpoint. MUST be invoked by the route
     * BEFORE JSON body parsing + content-length guard. Premium tiers (`premium_active`,
     * `premium_billing_retry`) skip the limiter entirely — the bucket is never written
     * for Premium.
     *
     * **Defensive null-tier fallback:** if `subscriptionStatus` is unexpectedly null /
     * empty (auth-path defect), the handler treats the caller as Free and applies the
     * cap (per spec § Daily rate limit "Defect-mode behavior"). The underlying defect
     * MUST be fixed in the auth-jwt path; this is a guardrail.
     */
    suspend fun tryRateLimit(
        userId: UUID,
        subscriptionStatus: String?,
    ): RateLimitOutcome {
        if (subscriptionStatus in PREMIUM_STATES) {
            return RateLimitOutcome.Allowed
        }
        if (subscriptionStatus.isNullOrBlank()) {
            log.warn(
                "event=reply_rate_limit_null_tier user_id={} fallback=apply_free_cap",
                userId,
            )
        }
        val cap = resolveDailyCap(userId)
        val outcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(
                    userId = userId,
                    key = dailyKey(userId),
                    capacity = cap,
                    ttl = computeTTLToNextReset(userId, clock()),
                )
            }
        return when (outcome) {
            is RateLimiter.Outcome.RateLimited ->
                RateLimitOutcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
            is RateLimiter.Outcome.Allowed -> RateLimitOutcome.Allowed
        }
    }

    /**
     * Inserts a reply + emits the V10 `post_replied` notification in one transaction.
     *
     * Caller MUST have already invoked [tryRateLimit] and observed [RateLimitOutcome.Allowed]
     * (the limiter is route-orchestrated to satisfy the spec's "limiter BEFORE body parse"
     * ordering). [post] does NOT consult the limiter and does NOT call `releaseMostRecent`
     * on any path — once a slot is acquired, it stays consumed even if this method
     * throws [PostNotFoundException] or rolls back the transaction.
     */
    fun post(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        replies.resolveVisiblePost(postId, authorId) ?: throw PostNotFoundException()
        var emittedId: UUID? = null
        val row =
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val inserted = replies.insertInTx(conn, postId = postId, authorId = authorId, content = content)
                    val parentAuthor = replies.loadParentAuthorId(conn, postId)
                    if (parentAuthor != null) {
                        emittedId =
                            notifications.emit(
                                conn = conn,
                                recipientId = parentAuthor,
                                actorUserId = authorId,
                                type = NotificationType.POST_REPLIED,
                                targetType = "post",
                                targetId = postId,
                                bodyData =
                                    buildJsonObject {
                                        put("reply_id", JsonPrimitive(inserted.id.toString()))
                                        put("reply_excerpt", JsonPrimitive(inserted.content.firstCodePoints(80)))
                                    },
                            )
                    }
                    conn.commit()
                    inserted
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
        emittedId?.let(dispatcher::dispatch)
        return row
    }

    fun list(
        postId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorReplyId: UUID?,
        limit: Int,
    ): List<PostReplyRow> {
        replies.resolveVisiblePost(postId, viewerId) ?: throw PostNotFoundException()
        return replies.listByPost(
            postId = postId,
            viewerId = viewerId,
            cursorCreatedAt = cursorCreatedAt,
            cursorReplyId = cursorReplyId,
            limit = limit,
        )
    }

    fun softDelete(
        replyId: UUID,
        authorId: UUID,
    ) {
        replies.softDeleteOwn(replyId, authorId)
    }

    /**
     * Resolves the daily cap for a Free-tier caller. Reads
     * `premium_reply_cap_override` from Remote Config; coerces unset / null /
     * non-positive / oversized integers and SDK errors all to the default 20.
     * Mirrors [LikeService.resolveDailyCap] byte-for-byte semantics — same
     * fallback ladder, same audit-log shape, same upper-bound clamp at 10,000.
     *
     * The clamp threshold (10,000) prevents accidental cap removal via a typo
     * (e.g., `2000000000` instead of `20`). No abuse signal supports a Free user
     * posting >1000 replies/day.
     */
    private fun resolveDailyCap(userId: UUID): Int {
        val raw =
            try {
                remoteConfig.getLong(PREMIUM_REPLY_CAP_OVERRIDE_KEY)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} fallback=default_20 user_id={} message={}",
                    PREMIUM_REPLY_CAP_OVERRIDE_KEY,
                    userId,
                    t.message,
                )
                return DEFAULT_DAILY_CAP
            }
        if (raw == null) {
            return DEFAULT_DAILY_CAP
        }
        if (raw <= 0L || raw > MAX_OVERRIDE_CAP) {
            log.warn(
                "event=remote_config_invalid key={} value={} fallback=default_20 reason={}",
                PREMIUM_REPLY_CAP_OVERRIDE_KEY,
                raw,
                if (raw <= 0L) "non_positive" else "above_clamp_threshold",
            )
            return DEFAULT_DAILY_CAP
        }
        val cap = raw.toInt()
        log.info(
            "event=remote_config_override_applied key={} value={} user_id={}",
            PREMIUM_REPLY_CAP_OVERRIDE_KEY,
            cap,
            userId,
        )
        return cap
    }

    companion object {
        const val PAGE_SIZE: Int = 30
        const val DEFAULT_DAILY_CAP: Int = 20
        const val PREMIUM_REPLY_CAP_OVERRIDE_KEY: String = "premium_reply_cap_override"

        /**
         * Upper-bound clamp for the override flag — values above this threshold
         * fall back to [DEFAULT_DAILY_CAP] (anti-typo guard; spec § Flag oversized
         * integer scenario). 10,000 chosen because no abuse signal supports a Free
         * user posting >1000 replies/day; 10x headroom above that for ops dial.
         */
        private const val MAX_OVERRIDE_CAP: Long = 10_000L

        /** Subscription statuses that skip the daily limiter entirely. */
        private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        private val log = LoggerFactory.getLogger(ReplyService::class.java)

        internal fun dailyKey(userId: UUID): String = "{scope:rate_reply_day}:{user:$userId}"
    }
}

/**
 * Private surrogate-pair-safe truncation helper, mirroring the same 80-code-point
 * rule used in `:infra:supabase` for the like excerpt. Kept local to avoid a
 * one-function shared-util module for a 10-line helper.
 */
private fun String.firstCodePoints(n: Int): String {
    if (n <= 0) return ""
    var cpCount = 0
    var i = 0
    while (i < length && cpCount < n) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        cpCount++
    }
    return substring(0, i)
}
