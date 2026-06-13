package id.nearyou.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage of the pure cap-countdown formatter (`mobile-cap-upsell-dialog` § "The countdown
 * derives from Retry-After and ticks to the reset"): seconds → round-UP minutes with the ≥1-minute
 * floor (a proxy-stripped `Retry-After` mapped to 0 must never flash-dismiss), and the
 * hours+minutes split that drives the `cap_countdown_hours_minutes` / `cap_countdown_minutes`
 * rendering (frame 18's "14 j 19 mnt").
 */
class CapCountdownTest {
    @Test
    fun frame18Fixture_51540s_is14h19m() {
        // 14 h 19 min = 51_540 s — the exact frame-18 "14 j 19 mnt" fixture.
        assertEquals(859, capCountdownMinutes(51_540))
        assertEquals(CapCountdown(hours = 14, minutes = 19), capCountdown(859))
    }

    @Test
    fun underAnHour_rendersMinutesOnly() {
        assertEquals(19, capCountdownMinutes(1_140))
        assertEquals(CapCountdown(hours = 0, minutes = 19), capCountdown(19))
    }

    @Test
    fun subMinuteRemainders_roundUp_neverZero() {
        assertEquals(1, capCountdownMinutes(59))
        assertEquals(CapCountdown(hours = 0, minutes = 1), capCountdown(1))
    }

    @Test
    fun justOverAnHour_roundsUpInto1h1m() {
        assertEquals(61, capCountdownMinutes(3_601))
        assertEquals(CapCountdown(hours = 1, minutes = 1), capCountdown(61))
    }

    @Test
    fun exactlyAnHour_is1h0m() {
        assertEquals(60, capCountdownMinutes(3_600))
        assertEquals(CapCountdown(hours = 1, minutes = 0), capCountdown(60))
    }

    @Test
    fun zeroAndNegative_floorToOneMinute() {
        // The shipped client maps an absent/stripped/unparseable Retry-After to RateLimited(0);
        // the floor keeps the dialog from flash-dismissing on entry (the banner's 0→"1 jam" precedent).
        assertEquals(1, capCountdownMinutes(0))
        assertEquals(1, capCountdownMinutes(-5))
        assertEquals(CapCountdown(hours = 0, minutes = 1), capCountdown(0))
    }
}
