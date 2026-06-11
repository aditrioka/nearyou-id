package id.nearyou.app.post

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Post-creation daily cap — 10 per day for Free users, Premium skips entirely
 * (docs/05 § Layer 2: "Post | 10/day Free, unlimited Premium"). Added by the
 * 2026-06-10 holistic audit (finding 02-M2): POST /api/v1/posts was the most
 * expensive UNCAPPED write (dual-PostGIS INSERT + V11 reverse-geocode trigger +
 * moderation + tsvector) while the far cheaper like (10/day) and reply (20/day)
 * writes shipped limiters.
 *
 * The `_day}` key marker selects the fixed-window Redis script (entries never
 * age out mid-day; the bucket expires at the caller-supplied
 * `computeTTLToNextReset` moment — see rate-limit-infrastructure spec).
 * Delegation shape mirrors [id.nearyou.app.follow.FollowRateLimiter].
 */
class PostRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
) {
    fun tryAcquire(
        userId: UUID,
        ttl: Duration,
    ): Outcome {
        val key = keyFor(userId)
        return when (val outcome = rateLimiter.tryAcquire(userId, key, cap, ttl)) {
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
        const val DEFAULT_CAP: Int = 10

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_post_day}:{user:<uuid>}`. */
        fun keyFor(userId: UUID): String = "{scope:rate_post_day}:{user:$userId}"
    }
}
