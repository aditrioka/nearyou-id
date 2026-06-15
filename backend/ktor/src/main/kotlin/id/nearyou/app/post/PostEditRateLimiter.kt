package id.nearyou.app.post

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Per-user rate limiter for the Premium post-edit endpoint (`PATCH
 * /api/v1/posts/{post_id}`, design D8). DISTINCT from the daily-post-cap
 * [PostRateLimiter]: a different scope hash-tag (`{scope:rate_post_edit}` vs
 * `{scope:rate_post_day}`) and a SHORT ROLLING window (not the daily WIB-staggered
 * window) — the threat it bounds is rapid re-edits WITHIN the 30-minute edit window,
 * each of which triggers synchronous moderation I/O (Redis / Remote Config /
 * Secret Manager on a cold cache) plus an external Perspective (Layer-3) call. A
 * rolling window caps that burst; a daily counter would be the wrong semantic.
 *
 * The key does NOT carry the [RateLimiter.FIXED_WINDOW_KEY_MARKER] (`_day}`), so the
 * shared limiter applies SLIDING-window counting (window == ttl) — slots age out
 * after [window], matching the [id.nearyou.app.moderation.ReportRateLimiter] rolling
 * pattern. Honors the Redis cluster hash-tag invariant (`{scope:<value>}`).
 *
 * The cap is ops-tunable (design D8): [PostEditService] resolves an override from
 * Remote Config and passes it here per call; the [cap] constructor default is the
 * ops baseline used when no override is configured.
 */
class PostEditRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    /**
     * Attempt to claim one edit slot for [userId]. [capacityOverride] (when non-null)
     * replaces the constructor [cap] for this call — the service passes the
     * Remote-Config-resolved value (design D8).
     */
    fun tryAcquire(
        userId: UUID,
        capacityOverride: Int? = null,
    ): Outcome {
        val key = keyFor(userId)
        val capacity = capacityOverride ?: cap
        return when (val outcome = rateLimiter.tryAcquire(userId, key, capacity, window)) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = outcome.remaining)
            is RateLimiter.Outcome.RateLimited ->
                Outcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
        }
    }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        /** Ops-baseline cap when no Remote Config override is set (design D8). */
        const val DEFAULT_CAP: Int = 20

        /** Rolling window the edit cap is measured over. */
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)

        /**
         * Redis cluster hash-tag form, slot-stable: `{scope:rate_post_edit}:{user:<uuid>}`.
         * Distinct scope from the daily-post-cap `{scope:rate_post_day}` so the two
         * limiters never share a bucket.
         */
        fun keyFor(userId: UUID): String = "{scope:rate_post_edit}:{user:$userId}"
    }
}
