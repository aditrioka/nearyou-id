package id.nearyou.app.appeal

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Per-user daily appeal-submission cap (`content-moderation-appeal`). Appeals are
 * rare (at most one pending at a time), so this is a low SECONDARY anti-abuse bound
 * on rapid re-submission after a decision — NOT the primary one-pending guard (that
 * is the `appeals_one_pending_per_user` partial-unique index, V31).
 *
 * The `_day}` key marker selects the fixed-window Redis script (entries never age
 * out mid-day; the bucket expires at the caller-supplied `computeTTLToNextReset`
 * moment — see rate-limit-infrastructure spec). Delegation shape mirrors
 * [id.nearyou.app.post.PostRateLimiter].
 */
class AppealRateLimiter(
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
        const val DEFAULT_CAP: Int = 5

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_appeal_day}:{user:<uuid>}`. */
        fun keyFor(userId: UUID): String = "{scope:rate_appeal_day}:{user:$userId}"
    }
}
