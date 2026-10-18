package id.nearyou.app.ui.components

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Unit coverage of [localDateLabel] (#507), [localTimeLabel] and [localDaysAgo] (#286). The zone is always
 * injected, so every case is deterministic regardless of the test host's time zone (CI runs in UTC, the
 * operator's machine in WIB).
 */
class LocalDateLabelTest {
    private val wib = TimeZone.of("Asia/Jakarta")

    @Test
    fun `an instant past WIB midnight renders the next WIB day and not the UTC day`() {
        assertEquals("2026-10-28", localDateLabel("2026-10-27T18:30:00Z", wib))
        assertEquals("2026-10-27", localDateLabel("2026-10-27T18:30:00Z", TimeZone.UTC))
    }

    @Test
    fun `the WIB day flips exactly at 17h UTC`() {
        assertEquals("2026-10-27", localDateLabel("2026-10-27T16:59:59.999999Z", wib))
        assertEquals("2026-10-28", localDateLabel("2026-10-27T17:00:00Z", wib))
    }

    @Test
    fun `an offset instant is converted to the zone instead of cut at T`() {
        assertEquals("2026-07-01", localDateLabel("2026-07-01T23:59:59.999+07:00", wib))
        assertEquals("2026-06-30", localDateLabel("2026-07-01T00:30:00+07:00", TimeZone.UTC))
    }

    @Test
    fun `a bare date or empty string is returned unchanged`() {
        assertEquals("2026-07-01", localDateLabel("2026-07-01", wib))
        assertEquals("", localDateLabel("", wib))
    }

    // #286: the chat bubble / conversation-row clock time, "HH.mm" in the zone.
    @Test
    fun `the clock time is zero-padded HH dot mm in the zone and null for a non-instant`() {
        assertEquals("09.05", localTimeLabel("2026-10-12T02:05:00Z", wib))
        assertEquals("02.05", localTimeLabel("2026-10-12T02:05:59Z", TimeZone.UTC))
        assertNull(localTimeLabel("2026-10-12", wib))
    }

    // #286: the day distance behind the chat day chip and the row label, counted in calendar days of the zone.
    @Test
    fun `days ago counts calendar days in the zone and not 24-hour spans`() {
        val now = Instant.parse("2026-10-12T01:00:00Z") // 08.00 WIB on the 12th
        assertEquals(0, localDaysAgo("2026-10-11T17:00:00Z", now, wib)) // 00.00 WIB on the 12th
        assertEquals(1, localDaysAgo("2026-10-11T16:59:00Z", now, wib)) // 23.59 WIB on the 11th
        assertEquals(3, localDaysAgo("2026-10-09T02:00:00Z", now, wib))
        assertNull(localDaysAgo("not-an-instant", now, wib))
    }
}
