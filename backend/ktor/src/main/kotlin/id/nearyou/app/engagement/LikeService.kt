package id.nearyou.app.engagement

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import id.nearyou.data.repository.PostLikeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Like lifecycle: create / remove / count. Both `like` and `countLikes` resolve the
 * target post through `PostLikeRepository.resolveVisiblePost` BEFORE their real work
 * and throw [PostNotFoundException] on null — the HTTP layer maps that to
 * `404 post_not_found` (one code for missing, soft-deleted, or block-hidden).
 *
 * `unlike` deliberately skips the visibility resolve: the caller is always allowed to
 * remove their OWN like row regardless of current block state (post-likes spec
 * requirement "DELETE /like is a pure no-op on zero rows"). The PK scopes the DELETE
 * to the caller's own row, so there is no cross-user side effect to protect against.
 *
 * **Rate-limit ordering (see `like-rate-limit` change § post-likes spec):**
 *  1. Daily limiter (Free only — Premium skips entirely): `{scope:rate_like_day}:{user:U}`,
 *     capacity = `premium_like_cap_override` Remote Config flag (default 10), ttl =
 *     `computeTTLToNextReset(userId)` (per-user WIB stagger).
 *  2. Burst limiter (both tiers): `{scope:rate_like_burst}:{user:U}`, capacity = 500,
 *     ttl = `Duration.ofHours(1)`.
 *  3. `visible_posts` resolution (404 path does NOT release — slot was consumed).
 *  4. INSERT ... ON CONFLICT DO NOTHING.
 *  5. **No-op release (re-like)**: 0 rows INSERT'd → `releaseMostRecent` on BOTH keys.
 *     Premium daily-key release is a no-op against the empty bucket (per `RateLimiter`
 *     contract); we call it unconditionally so a future tier-flip-mid-day can't leak
 *     a slot.
 *  6. Notification emit (V10 — only on transition). DB-transaction commits synchronously
 *     per V10 strict-coupling contract; FCM/push side-effects fire-and-forget on
 *     `Dispatchers.IO` via the `NotificationDispatcher` (callers MUST inject one that
 *     does NOT block the request thread — Application.kt's production wiring is fire-
 *     and-forget; tests can opt in to a synchronous one).
 *  7. Return 204.
 *
 * V10 notification coupling: `like` opens a single transaction that spans the
 * `post_likes` INSERT + the `post_liked` notification INSERT. If the emit fails,
 * the like itself rolls back (in-app-notifications design Decision 1). Re-likes
 * (ON CONFLICT no-op) do NOT emit — only the not-liked → liked transition does.
 * Dispatch runs AFTER commit so a future FCM dispatcher never observes a
 * notification that ended up rolled back.
 *
 * Lettuce's sync API blocks the calling thread on Netty I/O; per the
 * `RateLimiter` contract (interface non-suspending to preserve V9 compatibility),
 * the wrap belongs at this call site. Both [tryAcquire] and [releaseMostRecent]
 * calls run inside `withContext(Dispatchers.IO)` to keep the Ktor coroutine
 * dispatcher unblocked.
 */
class LikeService(
    private val dataSource: DataSource,
    private val likes: PostLikeRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
    private val rateLimiter: RateLimiter,
    private val remoteConfig: RemoteConfig,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * Result of the like attempt. The HTTP layer maps each variant to a status code
     * + envelope. Mirrors V9 `ReportService.Result` precedent so error mapping stays
     * a single declarative `when` in the route handler.
     */
    sealed interface Result {
        data object Success : Result

        data object NotFound : Result

        data class RateLimited(val retryAfterSeconds: Long) : Result
    }

    suspend fun like(
        postId: UUID,
        userId: UUID,
        subscriptionStatus: String,
    ): Result {
        // 1. Daily limiter — Free only. Premium skips entirely (NOT consult-and-override:
        //    the bucket is never written for Premium so it stays empty even after a
        //    no-op release call below).
        val skipDaily = subscriptionStatus in PREMIUM_STATES
        if (!skipDaily) {
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
            when (outcome) {
                is RateLimiter.Outcome.RateLimited ->
                    return Result.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
                is RateLimiter.Outcome.Allowed -> Unit
            }
        }

        // 2. Burst limiter — both tiers, capacity 500, fixed 1-hour TTL.
        val burstOutcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(
                    userId = userId,
                    key = burstKey(userId),
                    capacity = BURST_CAPACITY,
                    ttl = Duration.ofHours(1),
                )
            }
        when (burstOutcome) {
            is RateLimiter.Outcome.RateLimited ->
                return Result.RateLimited(retryAfterSeconds = burstOutcome.retryAfterSeconds)
            is RateLimiter.Outcome.Allowed -> Unit
        }

        // 3. visible_posts resolution. 404 path does NOT release — the slot was
        //    consumed (anti-abuse: counts toward the cap).
        likes.resolveVisiblePost(postId, userId) ?: return Result.NotFound

        // 4. INSERT ... ON CONFLICT DO NOTHING + (5) no-op release on 0 rows.
        var emittedId: UUID? = null
        var inserted = false
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                inserted = likes.likeInTx(conn, postId, userId)
                if (inserted) {
                    val target = likes.loadPostAuthorAndExcerpt(conn, postId)
                    if (target != null) {
                        emittedId =
                            notifications.emit(
                                conn = conn,
                                recipientId = target.authorId,
                                actorUserId = userId,
                                type = NotificationType.POST_LIKED,
                                targetType = "post",
                                targetId = postId,
                                bodyData =
                                    buildJsonObject {
                                        put("post_excerpt", JsonPrimitive(target.excerpt))
                                    },
                            )
                    }
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }

        // 5. No-op release on re-like (0 rows INSERT'd). Call on BOTH keys
        //    unconditionally — the daily-key call against an empty Premium
        //    bucket is a no-op per the `RateLimiter` contract, but calling
        //    it always protects against a future regression where a tier
        //    flip mid-day populates the bucket and the slot leaks.
        if (!inserted) {
            withContext(Dispatchers.IO) {
                rateLimiter.releaseMostRecent(userId, dailyKey(userId))
                rateLimiter.releaseMostRecent(userId, burstKey(userId))
            }
        }

        // 6. Post-commit dispatch — fire-and-forget on Dispatchers.IO inside the
        //    NotificationDispatcher impl. The 204 returns as soon as commit
        //    completes; FCM round-trips never block the request thread.
        emittedId?.let(dispatcher::dispatch)

        return Result.Success
    }

    fun unlike(
        postId: UUID,
        userId: UUID,
    ) {
        likes.unlike(postId, userId)
    }

    fun countLikes(
        postId: UUID,
        viewerId: UUID,
    ): Long {
        likes.resolveVisiblePost(postId, viewerId) ?: throw PostNotFoundException()
        return likes.countVisibleLikes(postId)
    }

    /**
     * Resolves the daily cap for a Free-tier caller. Reads
     * `premium_like_cap_override` from Remote Config; coerces non-positive
     * integers, malformed strings (RemoteConfig returns null), and SDK errors
     * (RemoteConfig impls catch and return null) all to the default 10.
     *
     * Sentry WARN on each fallback path — ops can detect Remote Config
     * misconfigurations from the WARN volume. INFO log on the override-was-
     * applied happy path so we can verify the flag is being honored.
     */
    private fun resolveDailyCap(userId: UUID): Int {
        val raw =
            try {
                remoteConfig.getLong(PREMIUM_LIKE_CAP_OVERRIDE_KEY)
            } catch (t: Throwable) {
                // Defense-in-depth: the RemoteConfig contract says implementations
                // MUST catch and return null, but a misbehaving production binding
                // could still throw. Never propagate a Remote Config error as a 5xx.
                log.warn(
                    "event=remote_config_error key={} fallback=default_10 user_id={} message={}",
                    PREMIUM_LIKE_CAP_OVERRIDE_KEY,
                    userId,
                    t.message,
                )
                return DEFAULT_DAILY_CAP
            }
        if (raw == null) {
            // Unset OR malformed — RemoteConfig collapses both into null. WARN here
            // covers the malformed case; the unset case is the common no-op path
            // and would flood logs, so the WARN is rate-limited at the SDK level
            // (Firebase RC native client batches "unset" decisions as a single
            // poll cycle). For now the rate-of-WARN is acceptable as a single
            // line per request; revisit if the StubRemoteConfig is replaced.
            return DEFAULT_DAILY_CAP
        }
        if (raw <= 0L || raw > Int.MAX_VALUE) {
            log.warn(
                "event=remote_config_invalid key={} value={} fallback=default_10",
                PREMIUM_LIKE_CAP_OVERRIDE_KEY,
                raw,
            )
            return DEFAULT_DAILY_CAP
        }
        val cap = raw.toInt()
        log.info(
            "event=remote_config_override_applied key={} value={} user_id={}",
            PREMIUM_LIKE_CAP_OVERRIDE_KEY,
            cap,
            userId,
        )
        return cap
    }

    companion object {
        const val DEFAULT_DAILY_CAP: Int = 10
        const val BURST_CAPACITY: Int = 500
        const val PREMIUM_LIKE_CAP_OVERRIDE_KEY: String = "premium_like_cap_override"

        /** Subscription statuses that skip the daily limiter entirely. */
        private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        private val log = LoggerFactory.getLogger(LikeService::class.java)

        internal fun dailyKey(userId: UUID): String = "{scope:rate_like_day}:{user:$userId}"

        internal fun burstKey(userId: UUID): String = "{scope:rate_like_burst}:{user:$userId}"
    }
}

class PostNotFoundException : RuntimeException("post not found or not visible to caller")
