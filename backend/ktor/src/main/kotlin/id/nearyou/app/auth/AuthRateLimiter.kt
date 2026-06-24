package id.nearyou.app.auth

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration

/**
 * App-level defense-in-depth limiter on the three UNAUTHENTICATED auth-exchange
 * endpoints — `/signin`, `/signup`, `/refresh` — keyed by the HASHED client IP
 * (the only axis available before the caller is authenticated). Added by the
 * 2026-06-10 holistic audit (finding 01-#16; issue #214): these endpoints had
 * NO protection at any layer (docs/05 §Layer-1 per-IP is unbuilt; no Cloudflare
 * rate rules; `api-staging` CNAMEs straight to Cloud Run). This is the cheap,
 * always-present in-process floor — NOT a replacement for the pre-launch
 * Cloudflare / Cloud Armor edge layer (docs/08 #39); both layers are wanted.
 *
 * All three scopes are burst / short-window (none ends in `_day`), so they take
 * the sliding window — `RateLimitTtlRule` passes a hardcoded [Duration] ttl on a
 * non-`_day` key. Numbers were finalized in the change's design.md minding the
 * docs/05 §Layer-1 CGNAT note: signin 10/min, signup 5/hour, refresh **60/min**
 * (a per-MINUTE flood cap, NOT per-hour — a per-hour refresh cap accumulates
 * across CGNAT / public-WiFi peers, since refresh fires automatically for every
 * active user on each ~15-min token TTL, and would log innocent users out).
 *
 * Keys conform to the two-segment hash-tag standard
 * `{scope:rate_auth_<endpoint>}:{ip:<hashed-ip>}` (`RedisHashTagRule`); the
 * `{ip:…}` value is the `IpHasher` 16-hex hash, never a raw IP
 * (`OtelForbiddenAttributeRule`). The IP/axis-agnostic [RateLimiter.tryAcquireByKey]
 * is used (the user-axis `tryAcquire` would trip `IpAxisMustUseTryAcquireByKeyRule`).
 * Delegation shape mirrors [id.nearyou.app.post.PostRateLimiter]. The default
 * constructor uses an [InMemoryRateLimiter] so test fixtures need no Redis;
 * production routes through the shared Redis-backed `rateLimiter`.
 */
class AuthRateLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val signinCap: Int = SIGNIN_CAP,
    val signupCap: Int = SIGNUP_CAP,
    val refreshCap: Int = REFRESH_CAP,
) {
    fun trySignin(hashedIp: String): Outcome = acquire(signinKey(hashedIp), signinCap, SIGNIN_WINDOW)

    fun trySignup(hashedIp: String): Outcome = acquire(signupKey(hashedIp), signupCap, SIGNUP_WINDOW)

    fun tryRefresh(hashedIp: String): Outcome = acquire(refreshKey(hashedIp), refreshCap, REFRESH_WINDOW)

    private fun acquire(
        key: String,
        cap: Int,
        ttl: Duration,
    ): Outcome =
        when (val outcome = rateLimiter.tryAcquireByKey(key, cap, ttl)) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed(remaining = outcome.remaining)
            is RateLimiter.Outcome.RateLimited ->
                Outcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
        }

    sealed interface Outcome {
        data class Allowed(val remaining: Int) : Outcome

        data class RateLimited(val retryAfterSeconds: Long) : Outcome
    }

    companion object {
        const val SIGNIN_CAP: Int = 10
        const val SIGNUP_CAP: Int = 5
        const val REFRESH_CAP: Int = 60

        /** signin: interactive, gated by a Google/Apple ceremony — low legitimate per-IP rate. */
        val SIGNIN_WINDOW: Duration = Duration.ofMinutes(1)

        /** signup: account creation is rare per real user; bounds mass-registration. */
        val SIGNUP_WINDOW: Duration = Duration.ofHours(1)

        /** refresh: per-MINUTE (CGNAT-safe) flood cap, deliberately not per-hour — see KDoc. */
        val REFRESH_WINDOW: Duration = Duration.ofMinutes(1)

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_auth_signin}:{ip:<hashed-ip>}`. */
        fun signinKey(hashedIp: String): String = "{scope:rate_auth_signin}:{ip:$hashedIp}"

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_auth_signup}:{ip:<hashed-ip>}`. */
        fun signupKey(hashedIp: String): String = "{scope:rate_auth_signup}:{ip:$hashedIp}"

        /** Redis cluster hash-tag form, slot-stable: `{scope:rate_auth_refresh}:{ip:<hashed-ip>}`. */
        fun refreshKey(hashedIp: String): String = "{scope:rate_auth_refresh}:{ip:$hashedIp}"
    }
}
