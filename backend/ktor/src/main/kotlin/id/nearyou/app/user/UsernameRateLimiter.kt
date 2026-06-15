package id.nearyou.app.user

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.UUID

/**
 * Layer-2 limiters for `premium-username-customization` (docs/05 § Username
 * customization rate limits). Two independent buckets wrapping the shared
 * [RateLimiter] (Redis in prod, [InMemoryRateLimiter] in tests):
 *
 *  - **Failed change attempts — 10 / hour (sliding).** Anti-probing. The service
 *    acquires a slot before validation and [releaseFailedChange]s it on success,
 *    so only FAILED attempts accumulate; the 11th failure in the window → 429.
 *    Hourly window uses a fixed `Duration.ofHours(1)` — no `_day}` marker, so the
 *    sliding-window script applies (mirrors `SearchRateLimiter`).
 *  - **Availability probes — 3 / day (fixed window).** The `_day}` scope marker
 *    selects the fixed-window script; the bucket expires at the caller-supplied
 *    `computeTTLToNextReset` moment (per-user WIB stagger), mirroring
 *    `PostRateLimiter`. The 4th probe in a day → 429.
 *
 * The 1-change-per-30-days cooldown is NOT here — it is DB-authoritative on
 * `users.username_last_changed_at` (design D3), so it survives a Redis flush.
 */
class UsernameRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val failedChangeCap: Int = DEFAULT_FAILED_CHANGE_CAP,
    val failedChangeWindow: Duration = DEFAULT_FAILED_CHANGE_WINDOW,
    val probeCap: Int = DEFAULT_PROBE_CAP,
) {
    /** Acquire a failed-attempt slot (hourly). Released on a successful change. */
    fun tryAcquireFailedChange(userId: UUID): Outcome =
        rateLimiter.tryAcquire(userId, failedChangeKey(userId), failedChangeCap, failedChangeWindow).toOutcome()

    /** Release the most-recent failed-attempt slot — called when a change succeeds (only failures count). */
    fun releaseFailedChange(userId: UUID) {
        rateLimiter.releaseMostRecent(userId, failedChangeKey(userId))
    }

    /**
     * Acquire an availability-probe slot (daily). [ttl] is
     * `computeTTLToNextReset(userId, now)` — the per-user WIB-staggered window.
     */
    fun tryAcquireProbe(
        userId: UUID,
        ttl: Duration,
    ): Outcome = rateLimiter.tryAcquire(userId, probeKey(userId), probeCap, ttl).toOutcome()

    private fun RateLimiter.Outcome.toOutcome(): Outcome =
        when (this) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = remaining)
            is RateLimiter.Outcome.RateLimited -> Outcome.RateLimited(retryAfterSeconds = retryAfterSeconds)
        }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val DEFAULT_FAILED_CHANGE_CAP: Int = 10
        val DEFAULT_FAILED_CHANGE_WINDOW: Duration = Duration.ofHours(1)
        const val DEFAULT_PROBE_CAP: Int = 3

        /** Hourly sliding bucket: `{scope:rate_username_change}:{user:<uuid>}`. */
        fun failedChangeKey(userId: UUID): String = "{scope:rate_username_change}:{user:$userId}"

        /** Daily fixed-window bucket (`_day}` marker): `{scope:rate_username_probe_day}:{user:<uuid>}`. */
        fun probeKey(userId: UUID): String = "{scope:rate_username_probe_day}:{user:$userId}"
    }
}
