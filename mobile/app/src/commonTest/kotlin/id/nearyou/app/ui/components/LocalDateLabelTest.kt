package id.nearyou.app.ui.components

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage of [localDateLabel] (#507). The zone is always injected, so every case is deterministic
 * regardless of the test host's time zone (CI runs in UTC, the operator's machine in WIB).
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
}
