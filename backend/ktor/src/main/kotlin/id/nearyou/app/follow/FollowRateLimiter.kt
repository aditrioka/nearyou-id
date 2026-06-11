package id.nearyou.app.follow

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Follow/unfollow mutation rate limit — 50 per rolling hour per user, ONE
 * shared bucket for both directions (docs/05 § Follow Schema: "Follow/unfollow
 * rate limit: 50/hour"). The shared bucket is what cuts the follow↔unfollow
 * loop abuse vector: each cycle re-emits a `followed` notification + a real
 * FCM push to the victim, so the loop must burn quota on BOTH legs.
 *
 * Shape mirrors [id.nearyou.app.moderation.ReportRateLimiter] (the V9 hourly
 * pattern): delegates to the shared [RateLimiter] seam — Redis-backed in
 * production, [InMemoryRateLimiter] default for tests.
 *
 * Added by the 2026-06-10 holistic audit (finding 03-#3): docs/05 prescribed
 * the cap but no change ever shipped it (like/reply/chat each got their
 * `*-rate-limit` follow-up; follow/block never did).
 */
class FollowRateLimiter(
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
        const val DEFAULT_CAP: Int = 50
        val DEFAULT_WINDOW: Duration = Duration.ofHours(1)

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_follow}:{user:<uuid>}`. */
        fun keyFor(userId: UUID): String = "{scope:rate_follow}:{user:$userId}"
    }
}
