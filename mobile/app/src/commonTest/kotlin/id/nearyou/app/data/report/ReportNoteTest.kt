package id.nearyou.app.data.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The report-note code-point gate (relocated from `ProfileUiStateTest` alongside the helpers, which moved
 * from `screens.profile.ProfileUiState` to `data.report.ReportNote`). Pins the ≤200 **code-point** bound
 * (NOT UTF-16 `length`) so the shared report dialog accepts 200 emoji.
 */
class ReportNoteTest {
    @Test
    fun `report note count uses code points not UTF-16 length`() {
        // 200 surrogate-pair emoji = 200 code points but 400 UTF-16 units — a regression to `.length`
        // would count 400 and wrongly disable submit. This pins the code-point semantics.
        val emoji = "😀" // 😀 — one code point, two UTF-16 units
        val note = emoji.repeat(200)
        assertEquals(400, note.length)
        assertEquals(200, reportNoteCodePointCount(note))
        assertTrue(isReportNoteWithinLimit(note), "200 emoji code points is within the limit")
        assertFalse(isReportNoteWithinLimit(note + emoji), "201 emoji code points exceeds the limit")
    }

    @Test
    fun `report note gate is at 200 code points`() {
        assertTrue(isReportNoteWithinLimit("a".repeat(200)), "exactly 200 is within the limit")
        assertFalse(isReportNoteWithinLimit("a".repeat(201)), "201 exceeds the limit")
        assertTrue(isReportNoteWithinLimit(""), "an empty note is within the limit")
    }
}
