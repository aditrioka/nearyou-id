package id.nearyou.app.image

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Image-upload limiters (design D5), both **consumed at attempt** (no `releaseMostRecent` —
 * a later moderation/size/type rejection still counts, bounding per-image Vision/Cloudflare
 * cost against abuse):
 *
 *  - **Throttle** — 1 per 60s, sliding window. Key `{scope:rate_image_upload_throttle}:{user:U}`
 *    (no `_day}` marker → sliding).
 *  - **Daily quota** — N/day (default 50, override `premium_image_upload_cap_override`),
 *    fixed-window (the `_day}` key marker). Key `{scope:rate_image_upload_day}:{user:U}`,
 *    `ttl = computeTTLToNextReset(userId)` (per-user WIB-midnight stagger).
 *
 * Delegation shape mirrors [id.nearyou.app.post.PostRateLimiter].
 */
class ImageUploadRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
) {
    fun tryAcquireThrottle(userId: UUID): Outcome =
        wrap(rateLimiter.tryAcquire(userId, throttleKey(userId), THROTTLE_CAPACITY, Duration.ofSeconds(THROTTLE_SECONDS)))

    fun tryAcquireDaily(
        userId: UUID,
        cap: Int,
        ttl: Duration,
    ): Outcome = wrap(rateLimiter.tryAcquire(userId, dailyKey(userId), cap, ttl))

    private fun wrap(outcome: RateLimiter.Outcome): Outcome =
        when (outcome) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = outcome.remaining)
            is RateLimiter.Outcome.RateLimited -> Outcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
        }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val DEFAULT_DAILY_CAP: Int = 50
        const val THROTTLE_CAPACITY: Int = 1
        const val THROTTLE_SECONDS: Long = 60L

        /** Fixed-window daily-cap key (the `_day}` marker selects fixed-window Redis semantics). */
        fun dailyKey(userId: UUID): String = "{scope:rate_image_upload_day}:{user:$userId}"

        /** Sliding-window 60s throttle key. */
        fun throttleKey(userId: UUID): String = "{scope:rate_image_upload_throttle}:{user:$userId}"
    }
}
