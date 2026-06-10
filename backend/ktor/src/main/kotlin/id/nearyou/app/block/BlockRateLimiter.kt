package id.nearyou.app.block

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Block/unblock mutation rate limit — 30 per rolling hour per user, one shared
 * bucket for both directions (docs/02 §4 + docs/05 § Block: "30 block/unblock
 * per hour"). Same delegation shape as
 * [id.nearyou.app.moderation.ReportRateLimiter] / [id.nearyou.app.follow.FollowRateLimiter].
 *
 * Added by the 2026-06-10 holistic audit (finding 03-#3) — prescribed in the
 * canonical docs, never shipped.
 */
class BlockRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val cap: Int = DEFAULT_CAP,
    val window: Duration = DEFAULT_WINDOW,
) {
    fun tryAcquire(userId: UUID): Outcome {
        val key = keyFor(userId)
        return when (val outcome = rateLimiter.tryAcquire(userId, key, cap, window)) {
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
        const val DEFAULT_CAP: Int = 30
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_block}:{user:<uuid>}`. */
        fun keyFor(userId: UUID): String = "{scope:rate_block}:{user:$userId}"
    }
}
