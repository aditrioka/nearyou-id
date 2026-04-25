package id.nearyou.app.core.domain.ratelimit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.absoluteValue

private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")

/**
 * Per-user TTL until the next daily rate-limit reset, with WIB-midnight stagger.
 *
 * The stagger formula (`offset_seconds = abs(userId.hashCode().toLong()) % 3600`)
 * distributes resets linearly across the 00:00–01:00 WIB window, preventing a
 * thundering-herd flush at midnight when every Free user's daily quota would
 * otherwise reset simultaneously. See `docs/05-Implementation.md` § Rate Limiting
 * Implementation §1751-1755 for the canonical formula.
 *
 * Contract:
 *  - Pure function. Depends only on [userId] and [now]. No file/network/DB I/O.
 *  - Deterministic: repeated calls with the same `(userId, now)` return byte-identical
 *    [Duration]. The same user has a stable per-day offset across calls.
 *  - Always returns a strictly positive [Duration].
 *
 * The `now` parameter is exposed so tests can verify behavior across midnight WIB
 * without manipulating the system clock.
 *
 * The `.toLong()` cast on `userId.hashCode()` matters: `Int.MIN_VALUE.absoluteValue`
 * returns `Int.MIN_VALUE` itself (overflow); widening to Long first avoids that pitfall.
 */
fun computeTTLToNextReset(
    userId: UUID,
    now: Instant = Instant.now(),
): Duration {
    val offsetSeconds = userId.hashCode().toLong().absoluteValue % 3600L
    val nowZdt = now.atZone(WIB)
    val todayMidnight = nowZdt.toLocalDate().atStartOfDay(WIB)
    // Per spec: "next 00:00:00 AT or AFTER now" with the carve-out that, if `now`
    // is exactly at today's midnight, the next midnight is `now + 24h`. Both
    // branches collapse to "tomorrow's midnight" because `todayMidnight <= nowZdt`
    // always holds (atStartOfDay is the floor of nowZdt's date).
    val nextMidnight = todayMidnight.plusDays(1)
    val effectiveReset = nextMidnight.plusSeconds(offsetSeconds)
    return Duration.between(nowZdt, effectiveReset)
}
