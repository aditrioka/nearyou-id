package id.nearyou.app.post

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import java.time.Duration
import java.util.Locale

/**
 * Per-area post-density gate — Layer 4 "Per Area (anti local spam)"
 * (docs/05-Implementation.md § Anti-Spam). When a ~1.1 km grid cell accumulates
 * more than [AREA_POST_THRESHOLD] posts within the rolling [AREA_POST_WINDOW],
 * the *next* post created in that cell is routed to the `moderation_queue` for
 * human review — a **soft flag, NOT a hard reject** (the post is still created
 * and returned `201`; the poster is never told). Applies to ALL tiers
 * (area-keyed, not user-keyed — Premium is NOT exempt, unlike the daily cap).
 *
 * The cell is **area-keyed**, not user-keyed, so this delegates to the shared
 * [RateLimiter]'s axis-agnostic [RateLimiter.tryAcquireByKey] entry point — NOT
 * the user-axis `tryAcquire` (whose userId drives WIB-stagger + per-user
 * telemetry, inappropriate for an area bucket). Mirrors the
 * [id.nearyou.app.referral.ReferralTicketRateLimiter] wrapper shape; Redis-backed
 * in production, [InMemoryRateLimiter] default for tests. This is NOT a parallel
 * Redis-counter mechanism — it reuses the registered rate-limit pattern
 * (docs/11 §3.3).
 *
 * Window semantics: the key `{scope:area_post}:{cell:<lat>_<lng>}` does NOT end
 * in `_day}`, so the shared limiter takes its **sliding-window** path (strictly
 * better than docs/05's literal fixed INCR+EXPIRE — no boundary-reset gaming;
 * design Decision 2). Because this is an *hourly* limit it deliberately does NOT
 * use the per-user daily reset stagger (`computeTTLToNextReset`) — the window is
 * a plain [Duration.ofHours] (1). `RateLimitTtlRule` keys off the `_day}` marker,
 * which this key lacks, so no allowlist annotation is required.
 *
 * Threshold semantics: capacity is [AREA_POST_THRESHOLD] (= 50). The 1st–50th
 * posts in a cell/window are [Outcome.Allowed] (clean); the 51st onward
 * (in-window count > 50) are [Outcome.OverThreshold] (flagged). The boundary is
 * exact: the post whose creation makes the in-window count become 51 is the first
 * flagged post.
 */
class AreaPostDensityLimiter(
    private val rateLimiter: RateLimiter = InMemoryRateLimiter(),
    val threshold: Int = AREA_POST_THRESHOLD,
    val window: Duration = AREA_POST_WINDOW,
) {
    /**
     * Record one to-be-created post in the cell derived from its [displayLat] /
     * [displayLng] (the HMAC-fuzzed `display_location`, NEVER `actual_location` —
     * spatial-fuzzing invariant) and return whether the cell is now over the
     * density threshold.
     */
    fun tryAcquire(
        displayLat: Double,
        displayLng: Double,
    ): Outcome =
        when (rateLimiter.tryAcquireByKey(keyFor(displayLat, displayLng), threshold, window)) {
            is RateLimiter.Outcome.Allowed -> Outcome.Allowed
            // The limiter "rate-limited" the cell — for the area gate that means
            // the density threshold is exceeded, which the service maps to a
            // moderation_queue write, NOT a 429.
            is RateLimiter.Outcome.RateLimited -> Outcome.OverThreshold
        }

    sealed interface Outcome {
        /** The cell is within the density threshold — no queue routing. */
        data object Allowed : Outcome

        /** The cell exceeds the density threshold — route the post to manual review. */
        data object OverThreshold : Outcome
    }

    companion object {
        /** docs/05 § Layer 4: "Max 50 new posts in a 1 km radius / 1 h". */
        const val AREA_POST_THRESHOLD: Int = 50

        /** Rolling window for the density count (hourly — skips the daily WIB stagger). */
        val AREA_POST_WINDOW: Duration = Duration.ofHours(1)

        /**
         * Grid resolution: `display_location` is rounded to a 0.01° lat/lng grid.
         * At Indonesian latitudes (~6°S–6°N) 0.01° ≈ 1.10–1.11 km in both axes,
         * a near-square proxy for docs/05's "1 km radius" without per-call
         * trigonometry (design Decision 1). The rounding is performed by the
         * `%.2f` format in [cellId] (2 decimals == 0.01°).
         */
        const val AREA_CELL_PRECISION_DEGREES: Double = 0.01

        /**
         * Deterministic, locale-independent cell id: the rounded lat/lng pair via
         * a fixed [Locale.ROOT] `%.2f_%.2f` format, so `-6.20` and `-6.2` always
         * collapse to one identical key (a `,` decimal separator under another
         * locale would split a cell). Two posts whose `display_location` rounds to
         * the same 0.01° grid cell share one counter.
         */
        fun cellId(
            displayLat: Double,
            displayLng: Double,
        ): String = String.format(Locale.ROOT, "%.2f_%.2f", displayLat, displayLng)

        /**
         * Strict two-segment hash-tag form `{scope:area_post}:{cell:<lat>_<lng>}`
         * (RedisHashTagRule). Single-key INCR (no cross-slot multi-key op); the
         * scope does NOT end in `_day`, so it stays a sliding window. The cell id is
         * bound to a local `val` first so the key uses simple-name interpolation
         * (`$cell`) only: RedisHashTagRule requires a literal `{scope:…}:{axis:…}`
         * shape and rejects block interpolation (`${…}`) in the key, since the inner
         * braces break its structural two-segment match.
         */
        fun keyFor(
            displayLat: Double,
            displayLng: Double,
        ): String {
            val cell = cellId(displayLat, displayLng)
            return "{scope:area_post}:{cell:$cell}"
        }
    }
}
