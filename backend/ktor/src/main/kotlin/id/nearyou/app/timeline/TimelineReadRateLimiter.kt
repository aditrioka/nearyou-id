package id.nearyou.app.timeline

import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Per-user Layer 2 read-side rate limiter for the three authenticated timeline
 * endpoints (`GET /api/v1/timeline/{nearby,following,global}`). Owns the bucket-key
 * contract, soft/hard cap semantics, Premium short-circuit, and `X-Session-Id`
 * sanitization.
 *
 * **Caps (per the `timeline-read-rate-limit` capability spec):**
 *  - Rolling: 150 posts / 1-hour rolling window per user, aggregated across all
 *    three endpoints. Hard cap. On `RateLimited` the route returns
 *    `posts: [], next_cursor: null, upsell.hard: true` with HTTP 200.
 *  - Session: 50 posts / 1-hour rolling window per `(user, session-id)`. Soft cap.
 *    On `RateLimited` the route still admits the request and returns posts; the
 *    response carries `upsell.soft: true` so the mobile UI can surface a banner.
 *
 * **Premium bypass.** `subscription_status IN ('premium_active', 'premium_billing_retry')`
 * skips BOTH buckets entirely (no Redis calls). Mirrors the existing like / reply /
 * chat limiter pattern. Tier is read from the auth-time `UserPrincipal`; this
 * class never issues a fresh `users` SELECT.
 *
 * **Limiter ordering** (full sequence in the spec § "Limiter ordering"):
 *  1. Premium short-circuit (zero Redis calls).
 *  2. Sanitize `X-Session-Id` header.
 *  3. [preCheck] — rolling pre-check first; on `RateLimited` short-circuits and
 *     returns [PreCheckOutcome.HardCapped] WITHOUT consulting the session bucket.
 *     If admitted, the session pre-check runs and sets [PreCheckOutcome.Admit.softCapReached].
 *  4. Route executes the timeline DB query → `posts: List<...>` of length `N`.
 *  5. [postIncrement] issues `(N - 1).coerceAtLeast(0)` parallel best-effort
 *     `tryAcquire` calls per bucket via `coroutineScope { … awaitAll() }`. Outcome
 *     ignored — the response is already shaped.
 *  6. Build the response with the optional [Upsell] field per `softCapReached` /
 *     hard-cap state.
 *
 * **Hash-tag key shape** (per the `RedisHashTagRule` strict regex
 * `^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$`):
 *  - Rolling: `{scope:rate_timeline_rolling}:{user:<uuid>}` — single-pair shape
 *    identical to like-rate-limit's daily / burst keys.
 *  - Session: `{scope:rate_timeline_session}:{session:<uuid>__<sanitized_session_id>}` —
 *    both segments wrapped, separated by exactly one `:`. The `<uuid>__<sid>`
 *    composite (joined by exactly two underscores) is the partition axis; the
 *    axis name `session` describes the partition dimension. Encoding the
 *    session-id INTO the partition-axis value (instead of as a trailing
 *    segment) keeps the rule's strict regex passing without modification.
 *
 * **Lettuce sync API blocks Netty I/O.** The shared `RateLimiter` interface is
 * non-suspending; the wrap belongs at this call site. All `tryAcquire` calls run
 * inside `withContext(Dispatchers.IO)` to keep the Ktor coroutine dispatcher
 * unblocked. This mirrors the `LikeService` precedent.
 *
 * **Stateless above the Redis seam.** The class holds no per-request state — the
 * `userId` and sanitized session-id flow through method parameters. Safe to bind
 * as a Koin `single` (no per-request scope needed).
 */
class TimelineReadRateLimiter(
    private val rateLimiter: RateLimiter,
) {
    /**
     * Outcome of [preCheck]. The route handler dispatches on this value:
     *  - [Admit] — proceed with the timeline DB query. `softCapReached` mirrors the
     *    session-bucket pre-check `RateLimited` outcome and drives `upsell.soft`.
     *  - [HardCapped] — return the empty + `upsell.hard = true` body without executing
     *    the timeline DB query.
     */
    sealed interface PreCheckOutcome {
        data class Admit(val softCapReached: Boolean) : PreCheckOutcome

        data object HardCapped : PreCheckOutcome
    }

    /**
     * Pre-check both buckets and return [PreCheckOutcome]. See the class KDoc for
     * the full ordering. Premium short-circuits immediately with zero Redis calls.
     *
     * @param viewer the auth-time principal; tier is read from
     *   [UserPrincipal.subscriptionStatus] (no fresh DB SELECT).
     * @param sanitizedSessionId the output of [sanitizeSessionId] applied to the
     *   raw `X-Session-Id` header value; never `null`, may be the literal
     *   `"no-session"` fallback.
     */
    suspend fun preCheck(
        viewer: UserPrincipal,
        sanitizedSessionId: String,
    ): PreCheckOutcome {
        // 1. Premium short-circuit — zero Redis calls. Mirrors LikeService precedent.
        if (viewer.subscriptionStatus in PREMIUM_STATES) {
            return PreCheckOutcome.Admit(softCapReached = false)
        }

        // 2. Rolling pre-check (hard cap).
        val rollingOutcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(
                    userId = viewer.userId,
                    key = rollingKey(viewer.userId),
                    capacity = ROLLING_CAPACITY,
                    ttl = WINDOW_TTL,
                )
            }
        if (rollingOutcome is RateLimiter.Outcome.RateLimited) {
            // Per spec § "Limiter ordering" the session pre-check is NOT consulted
            // when the rolling pre-check rate-limits — saves one Redis round-trip and
            // ensures the response carries only `upsell.hard = true` (never `soft`).
            return PreCheckOutcome.HardCapped
        }

        // 3. Session pre-check (soft cap; advisory only — never blocks).
        val sessionOutcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(
                    userId = viewer.userId,
                    key = sessionKey(viewer.userId, sanitizedSessionId),
                    capacity = SESSION_CAPACITY,
                    ttl = WINDOW_TTL,
                )
            }
        return PreCheckOutcome.Admit(softCapReached = sessionOutcome is RateLimiter.Outcome.RateLimited)
    }

    /**
     * Best-effort post-increment after the timeline DB query returns `postCount`
     * posts. Issues `(postCount - 1).coerceAtLeast(0)` parallel `tryAcquire` calls
     * on EACH of the rolling and session buckets — the pre-check already consumed 1
     * slot per bucket, so this method is responsible for slots 2..N.
     *
     * The Lua sliding-window in [RedisRateLimiter] enforces `bucket.size ≤ capacity`
     * — once a bucket fills mid-loop, subsequent `tryAcquire` calls return
     * `RateLimited` and add nothing. Outcome of each call is ignored: the response
     * is already shaped.
     *
     * Premium short-circuits identically to [preCheck] — zero Redis calls.
     *
     * `postCount ≤ 1` skips the loop entirely:
     *  - 0 posts → no extra increments needed (pre-check consumed 1 slot regardless).
     *  - 1 post → the pre-check's slot already counts that 1 post.
     */
    suspend fun postIncrement(
        viewer: UserPrincipal,
        sanitizedSessionId: String,
        postCount: Int,
    ) {
        if (viewer.subscriptionStatus in PREMIUM_STATES) {
            return
        }
        val extra = (postCount - 1).coerceAtLeast(0)
        if (extra == 0) {
            return
        }
        val rollingKeyStr = rollingKey(viewer.userId)
        val sessionKeyStr = sessionKey(viewer.userId, sanitizedSessionId)
        coroutineScope {
            (1..extra).flatMap {
                listOf(
                    async(Dispatchers.IO) {
                        rateLimiter.tryAcquire(viewer.userId, rollingKeyStr, ROLLING_CAPACITY, WINDOW_TTL)
                    },
                    async(Dispatchers.IO) {
                        rateLimiter.tryAcquire(viewer.userId, sessionKeyStr, SESSION_CAPACITY, WINDOW_TTL)
                    },
                )
            }.awaitAll()
        }
    }

    companion object {
        const val ROLLING_CAPACITY: Int = 150
        const val SESSION_CAPACITY: Int = 50

        // Hourly rolling window. Both buckets share the same TTL — 1 hour from
        // the most-recent ZADD. NOT WIB-staggered (those are daily caps); the
        // RateLimitTtlRule Detekt rule fires only on keys whose literal contains
        // the substring `_day}`, so the rule does NOT fire on these hourly keys.
        val WINDOW_TTL: Duration = Duration.ofHours(1)

        /** Subscription statuses that skip both buckets entirely. */
        private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        // Validation regex for the `X-Session-Id` header value:
        //   - Length 1..64 (UUID is 36; modest headroom for client-side variants).
        //   - Alphanumeric + hyphen only — excludes Redis-key-structural chars
        //     (`{`, `}`, `:`, `\`, control chars) so a malicious client cannot
        //     inject brace characters into the rendered key.
        // Case-sensitive by design (UUIDs are typically lowercase but accept either).
        private val SID_PATTERN: Regex = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Constant fallback flowed into the session-bucket key when no valid SID is supplied. */
        const val NO_SESSION: String = "no-session"

        @Suppress("unused") // Future use — currently no logger calls fire from this class.
        private val log = LoggerFactory.getLogger(TimelineReadRateLimiter::class.java)

        /**
         * Validate + sanitize the raw `X-Session-Id` header value.
         *  - `null` or empty → [NO_SESSION].
         *  - Matches [SID_PATTERN] → returned unchanged.
         *  - Otherwise → [NO_SESSION].
         *
         * The rendered key flows directly into Redis without any further escaping;
         * this function is the sole boundary that ensures a malicious client cannot
         * inject `{`, `}`, `:`, or other key-structural bytes.
         */
        fun sanitizeSessionId(raw: String?): String {
            if (raw.isNullOrEmpty()) return NO_SESSION
            return if (SID_PATTERN.matches(raw)) raw else NO_SESSION
        }

        /** Rolling-bucket Redis key. See class KDoc § "Hash-tag key shape". */
        fun rollingKey(userId: UUID): String = "{scope:rate_timeline_rolling}:{user:$userId}"

        /**
         * Session-bucket Redis key. See class KDoc § "Hash-tag key shape".
         *
         * The composite axis value is built into a separate string before being
         * interpolated as a simple-name into the rate-limit key literal. This
         * follows the workaround documented in `RedisHashTagRule`'s KDoc — block
         * interpolation `${userId}` inside the rate-limit key literal would trip
         * the rule's strict regex (the source text contains literal `{}` from
         * the block form). Build the value out first, then drop it in via
         * simple-name interpolation.
         */
        fun sessionKey(
            userId: UUID,
            sanitizedSessionId: String,
        ): String {
            val sessionAxisValue = userId.toString() + "__" + sanitizedSessionId
            return "{scope:rate_timeline_session}:{session:$sessionAxisValue}"
        }
    }
}
