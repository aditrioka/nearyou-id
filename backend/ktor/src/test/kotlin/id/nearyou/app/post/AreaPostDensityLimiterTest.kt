package id.nearyou.app.post

import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.time.Duration
import java.time.Instant

/**
 * Pure unit tests for [AreaPostDensityLimiter] — pins the Layer 4 density-gate
 * mechanics without a DB (`post-area-density-cap` spec, `### Requirement: Counter
 * is a Redis hourly sliding-window limiter…` + `### Requirement: Area cell derived
 * from display_location on a degree grid`). The end-to-end gate→`moderation_queue`
 * wiring is covered by the DB-tagged `AreaPostDensityCreatePostTest`.
 *
 * No `@Tags("database")` — these run on the default fast lane (in-memory limiter).
 */
class AreaPostDensityLimiterTest : StringSpec({

    "cell id is deterministic + locale-independent (%.2f_%.2f, dot separator)" {
        // -6.2 and -6.20 are the same Double, but the format pins the 2-decimal,
        // dot-separated, ROOT-locale rendering so a `,`-locale can't split a cell.
        AreaPostDensityLimiter.cellId(-6.2, 106.8) shouldBe "-6.20_106.80"
        AreaPostDensityLimiter.cellId(-6.20, 106.80) shouldBe "-6.20_106.80"
        // Rounds to the 0.01° grid (2 decimals).
        AreaPostDensityLimiter.cellId(-6.2049, 106.8051) shouldBe "-6.20_106.81"
        AreaPostDensityLimiter.cellId(-10.50, 105.00) shouldBe "-10.50_105.00"
    }

    "equator seam: coords either side of lat 0 share one cell (no -0.00 split)" {
        // `%.2f` keeps the sign even when the magnitude rounds to 0.00, so a
        // display coord just south of the equator must not split from its
        // northern half. Indonesia straddles lat 0, so this seam is in-country.
        val south = AreaPostDensityLimiter.cellId(-0.004, 106.80)
        val north = AreaPostDensityLimiter.cellId(0.004, 106.80)
        south shouldBe "0.00_106.80"
        north shouldBe "0.00_106.80"
        south shouldBe north
        // The normalization touches only the zero token — non-zero cells and a
        // longitude crossing the prime meridian collapse the same way.
        AreaPostDensityLimiter.cellId(-0.003, -0.003) shouldBe "0.00_0.00"
        // …and never corrupts a non-zero negative magnitude (e.g. -10.00).
        AreaPostDensityLimiter.cellId(-10.004, 106.80) shouldBe "-10.00_106.80"
    }

    "Redis key is the strict two-segment hash-tag scope:axis form" {
        val key = AreaPostDensityLimiter.keyFor(-6.2, 106.8)
        key shouldBe "{scope:area_post}:{cell:-6.20_106.80}"
        key shouldMatch Regex("^\\{scope:area_post}:\\{cell:[^}]+}$")
        // No `_day}` marker → the shared limiter takes the sliding-window path.
        key.contains("_day}") shouldBe false
    }

    "boundary: 1st–50th posts Allowed, 51st OverThreshold (count > 50, not >=)" {
        val limiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter())
        repeat(50) {
            limiter.tryAcquire(-6.2, 106.8) shouldBe AreaPostDensityLimiter.Outcome.Allowed
        }
        // The 51st (in-window count becomes 51) is the first flagged.
        limiter.tryAcquire(-6.2, 106.8) shouldBe AreaPostDensityLimiter.Outcome.OverThreshold
        // …and posts beyond stay flagged while the window holds.
        limiter.tryAcquire(-6.2, 106.8) shouldBe AreaPostDensityLimiter.Outcome.OverThreshold
    }

    "distinct cells are counted independently (cell A saturation does not flag cell B)" {
        val limiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter())
        // Saturate cell A past threshold.
        repeat(51) { limiter.tryAcquire(-6.20, 106.80) }
        limiter.tryAcquire(-6.20, 106.80) shouldBe AreaPostDensityLimiter.Outcome.OverThreshold
        // A clearly-different cell (>0.01° away) is unaffected.
        limiter.tryAcquire(-6.50, 107.20) shouldBe AreaPostDensityLimiter.Outcome.Allowed
    }

    "sliding window ages out individual posts (not a fixed-block reset)" {
        var now = Instant.parse("2026-06-26T00:00:00Z")
        val limiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter(clock = { now }))

        // 50 posts at t0 — all clean (count = 50).
        repeat(50) { limiter.tryAcquire(-10.50, 105.00) shouldBe AreaPostDensityLimiter.Outcome.Allowed }

        // One more at t0+59m — in-window count becomes 51 → flagged.
        now = now.plus(Duration.ofMinutes(59))
        limiter.tryAcquire(-10.50, 105.00) shouldBe AreaPostDensityLimiter.Outcome.OverThreshold

        // At t0+61m the original t0 posts have aged out of the trailing 60-min
        // window → the cell is no longer over threshold.
        now = now.plus(Duration.ofMinutes(2)) // t0+61m
        limiter.tryAcquire(-10.50, 105.00) shouldBe AreaPostDensityLimiter.Outcome.Allowed
    }

    "fixed expiry: after the full 1h window elapses the cell count no longer exceeds threshold" {
        var now = Instant.parse("2026-06-26T00:00:00Z")
        val limiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter(clock = { now }))

        repeat(50) { limiter.tryAcquire(-10.50, 105.00) }
        limiter.tryAcquire(-10.50, 105.00) shouldBe AreaPostDensityLimiter.Outcome.OverThreshold

        // Advance past the whole window — every entry ages out.
        now = now.plus(Duration.ofMinutes(61))
        limiter.tryAcquire(-10.50, 105.00) shouldBe AreaPostDensityLimiter.Outcome.Allowed
    }
})
