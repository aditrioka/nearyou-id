package id.nearyou.app.data.dataexport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage of [exportDeadlineLabel] — the pure ISO-8601 → date-portion helper for the ready-banner
 * deadline line. Deterministic (no wall clock, no timezone), so the cases below fully specify it.
 */
class ExportDeadlineLabelTest {
    @Test
    fun `keeps only the date portion of an ISO instant`() {
        assertEquals("2026-07-01", exportDeadlineLabel("2026-07-01T00:00:00Z"))
        assertEquals("2026-07-01", exportDeadlineLabel("2026-07-01T23:59:59.999+07:00"))
    }

    @Test
    fun `returns a bare date or empty string unchanged`() {
        assertEquals("2026-07-01", exportDeadlineLabel("2026-07-01"))
        assertEquals("", exportDeadlineLabel(""))
    }
}
