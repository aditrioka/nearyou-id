package id.nearyou.app.data.report

/** The server's report-note bound (`reports` spec: `reason_note` ≤ 200 Unicode code points). */
const val REPORT_NOTE_MAX_CODE_POINTS: Int = 200

/**
 * Counts the **Unicode code points** in [note] (NOT UTF-16 `length`), so a surrogate-pair emoji counts
 * as one — matching the server's code-point bound (mirrors the reply composer's 280-code-point guard).
 * Pure + commonMain so the report-note gate is unit-testable without composing UI (a regression to
 * `.length` would change this count for surrogate input and fail the test).
 *
 * Relocated from `id.nearyou.app.screens.profile.ProfileUiState` into the shared `data/report/` seam so
 * the shared `ReportDialog` (in `ui/components/`) bounds the note without depending on a feature package.
 */
fun reportNoteCodePointCount(note: String): Int {
    var count = 0
    var i = 0
    while (i < note.length) {
        val c = note[i]
        i += if (c.isHighSurrogate() && i + 1 < note.length && note[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** Whether the report note is within the server's ≤200-code-point bound (the submit-enabled gate). */
fun isReportNoteWithinLimit(note: String): Boolean = reportNoteCodePointCount(note) <= REPORT_NOTE_MAX_CODE_POINTS
