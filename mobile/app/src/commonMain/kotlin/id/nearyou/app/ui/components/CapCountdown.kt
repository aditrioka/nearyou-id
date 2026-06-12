package id.nearyou.app.ui.components

/**
 * The pure split of a cap countdown into hours + minutes (`mobile-cap-upsell-dialog` § "The countdown
 * derives from Retry-After and ticks to the reset"). [hours] is 0 below one hour — the dialog renders
 * the `cap_countdown_minutes` form then, `cap_countdown_hours_minutes` otherwise (frame 18's
 * "14 j 19 mnt" treatment).
 */
data class CapCountdown(
    val hours: Int,
    val minutes: Int,
)

/**
 * `Retry-After` seconds → remaining whole minutes, rounded **up** (the countdown never shows a
 * zero-minute value while time remains) and **floored to 1**: the shipped client maps a 429 whose
 * `Retry-After` is absent/stripped/unparseable to `RateLimited(0)`, and an unfloored zero would
 * flash-dismiss the dialog on entry (the shipped detail banner's 0→"1 jam" floor precedent). Pure +
 * deterministic — no wall clock.
 */
fun capCountdownMinutes(retryAfterSeconds: Long): Int = if (retryAfterSeconds <= 0) 1 else ((retryAfterSeconds + 59) / 60).toInt()

/** Remaining whole minutes → the hours+minutes split (floored to 1 minute). Pure + deterministic. */
fun capCountdown(totalMinutes: Int): CapCountdown {
    val floored = maxOf(totalMinutes, 1)
    return CapCountdown(hours = floored / 60, minutes = floored % 60)
}
