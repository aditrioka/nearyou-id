package id.nearyou.app.core.domain.ratelimit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Tests for [computeTTLToNextReset]. Pure-function semantics — no clock manipulation
 * beyond the `now` parameter.
 *
 * Reference times (UTC ↔ WIB, WIB = UTC+7):
 *  - 2026-01-01T00:00:00Z = 2026-01-01T07:00:00 WIB.
 *  - 2026-01-01T16:59:59Z = 2026-01-01T23:59:59 WIB.
 *  - 2026-01-01T17:00:00Z = 2026-01-02T00:00:00 WIB (exact midnight WIB).
 *  - 2026-01-01T17:00:01Z = 2026-01-02T00:00:01 WIB (1 second after midnight WIB).
 */
class ComputeTtlToNextResetTest : StringSpec({

    "Same user same offset across calls" {
        val u = UUID.randomUUID()
        val now1 = Instant.parse("2026-01-01T07:00:00Z")
        val now2 = now1.plusSeconds(1)
        val d1 = computeTTLToNextReset(u, now1)
        val d2 = computeTTLToNextReset(u, now2)
        // Same user => same per-day offset. d2 must be exactly 1s shorter than d1
        // (assuming both still in the same WIB day, which is the case here).
        d1.minus(d2) shouldBe Duration.ofSeconds(1)
    }

    "Pure function returns byte-identical Duration on repeated calls" {
        val u = UUID.randomUUID()
        val now = Instant.parse("2026-03-15T03:14:15Z")
        val d1 = computeTTLToNextReset(u, now)
        val d2 = computeTTLToNextReset(u, now)
        d1 shouldBe d2
    }

    "Offset bounded to [0, 3600) seconds at fixed now=07:00 WIB" {
        // now = 2026-01-01T07:00:00 WIB — exactly 17h before next midnight WIB.
        // Effective reset = next midnight + offset, where offset ∈ [0, 3600).
        // So Duration ∈ [17h, 17h + 1h).
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val lower = Duration.ofHours(17)
        val upper = Duration.ofHours(18)
        repeat(1000) {
            val d = computeTTLToNextReset(UUID.randomUUID(), now)
            (d >= lower) shouldBe true
            (d < upper) shouldBe true
        }
    }

    "Different users produce different offsets at high probability" {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        var differingPairs = 0
        repeat(1000) {
            val u1 = UUID.randomUUID()
            val u2 = UUID.randomUUID()
            val d1 = computeTTLToNextReset(u1, now)
            val d2 = computeTTLToNextReset(u2, now)
            if (d1 != d2) differingPairs++
        }
        // Spec scenario: at least 999 of 1000. The collision probability per pair
        // is 1/3600 (offsets share a 3600-bucket space), so the expected collision
        // count in 1000 pairs is ~0.28; observing >= 999 differing is overwhelmingly
        // likely on any reasonable hashCode distribution. If this ever flakes, the
        // hashCode distribution is the suspect, not the assertion.
        (differingPairs >= 999) shouldBe true
    }

    "Crossing midnight WIB rolls the window" {
        // Pick a user whose offset is 0 to make the math obvious.
        val u = userIdWithOffset(0)
        val nowJustBefore = Instant.parse("2026-01-01T16:59:59Z") // = 23:59:59 WIB Jan 1
        val nowJustAfter = Instant.parse("2026-01-01T17:00:01Z") // = 00:00:01 WIB Jan 2

        val dBefore = computeTTLToNextReset(u, nowJustBefore)
        val dAfter = computeTTLToNextReset(u, nowJustAfter)

        // Just before midnight: TTL ≈ 1s (offset 0 → reset at 00:00:00 Jan 2 WIB).
        dBefore shouldBe Duration.ofSeconds(1)
        // Just after midnight: TTL ≈ 24h - 1s (next reset is 00:00:00 Jan 3 WIB).
        dAfter shouldBe Duration.ofHours(24).minusSeconds(1)
    }

    "Exactly at midnight WIB rolls forward 24h + offset" {
        // Spec carve-out: if now is exactly at midnight WIB, next_midnight = now + 24h,
        // so TTL = 24h + offset. Use a deterministic user with offset 0.
        val u = userIdWithOffset(0)
        val nowAtMidnightWib = Instant.parse("2026-01-01T17:00:00Z") // = 00:00:00 WIB Jan 2
        val d = computeTTLToNextReset(u, nowAtMidnightWib)
        d shouldBe Duration.ofHours(24)
    }

    "Returns strictly positive Duration" {
        val u = UUID.randomUUID()
        // Sample a handful of moments around midnight to cover edge cases.
        val moments =
            listOf(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T16:59:59Z"),
                Instant.parse("2026-01-01T17:00:00Z"),
                Instant.parse("2026-01-01T17:00:01Z"),
                Instant.parse("2026-12-31T23:59:59Z"),
            )
        for (now in moments) {
            val d = computeTTLToNextReset(u, now)
            (!d.isNegative && !d.isZero) shouldBe true
        }
    }
})

/**
 * Search the UUID space for a value whose hashCode produces the requested offset.
 * Naive linear scan; finds a match within a few thousand attempts on average.
 */
private fun userIdWithOffset(targetOffsetSeconds: Long): UUID {
    repeat(100_000) {
        val candidate = UUID.randomUUID()
        if (candidate.hashCode().toLong().absoluteValue % 3600L == targetOffsetSeconds) {
            return candidate
        }
    }
    error("Could not find UUID with offset=$targetOffsetSeconds in 100k attempts")
}
