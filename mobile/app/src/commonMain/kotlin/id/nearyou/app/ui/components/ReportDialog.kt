package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.isReportNoteWithinLimit
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.profile_report_note_placeholder
import id.nearyou.resources.generated.resources.profile_report_submit
import id.nearyou.resources.generated.resources.report_reason_adult_content
import id.nearyou.resources.generated.resources.report_reason_harassment
import id.nearyou.resources.generated.resources.report_reason_hate_speech_sara
import id.nearyou.resources.generated.resources.report_reason_misinformation
import id.nearyou.resources.generated.resources.report_reason_other
import id.nearyou.resources.generated.resources.report_reason_spam
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Test tag on the report dialog's optional-note field (the placeholder disappears once text is entered,
 *  so the note-gate test targets this stable tag instead of the placeholder). */
const val REPORT_DIALOG_NOTE_TAG: String = "reportDialogNote"

/**
 * The single shared report dialog (an M3 `AlertDialog`) used to report any reportable content
 * (mobile-content-report, design D2). Extracted verbatim from the profile `ReportReasonDialog` and made
 * target-agnostic: the [title] differs per surface (user / post / reply), but the reason picker (the six
 * user-facing [ReportReasonCategory] entries — no `self_harm`/`csam_suspected`) + the optional ≤200
 * **code-point** note are identical across surfaces. All strings come from `:shared:resources` (no
 * hardcoded UI strings). [onSubmit] hands back the selected category + the note (blank → null); the
 * caller submits via the shared `ReportSubmitter`.
 *
 * The note bound counts Unicode **code points** (not UTF-16 `length`) via [isReportNoteWithinLimit], so a
 * 200-emoji note (400 UTF-16 units) is accepted — mirroring the reply composer's 280-code-point guard.
 */
@Composable
fun ReportDialog(
    title: StringResource,
    onSubmit: (ReportReasonCategory, String?) -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    var selected by remember { mutableStateOf(ReportReasonCategory.SPAM) }
    var note by remember { mutableStateOf("") }
    // The submit is gated by the pure, unit-tested ≤200-code-point check (matches the server bound).
    val noteWithinLimit = isReportNoteWithinLimit(note)
    AlertDialog(
        modifier = Modifier.testTag(testTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ReportReasonCategory.entries.forEach { category ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == category,
                                    role = Role.RadioButton,
                                    onClick = { selected = category },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The whole row is the selectable target (above); the RadioButton is decorative
                        // (onClick = null) so the label text is tappable too (a11y + the test taps the label).
                        RadioButton(selected = selected == category, onClick = null)
                        Text(stringResource(category.label()))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(Res.string.profile_report_note_placeholder)) },
                    isError = !noteWithinLimit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().testTag(REPORT_DIALOG_NOTE_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(selected, note.ifBlank { null }) },
                // The submit is disabled past 200 Unicode code points (matching the server bound).
                enabled = noteWithinLimit,
            ) {
                Text(stringResource(Res.string.profile_report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cta_cancel)) }
        },
    )
}

/** Maps a [ReportReasonCategory] to its `:shared:resources` label (the six user-facing categories). */
private fun ReportReasonCategory.label(): StringResource =
    when (this) {
        ReportReasonCategory.SPAM -> Res.string.report_reason_spam
        ReportReasonCategory.HATE_SPEECH_SARA -> Res.string.report_reason_hate_speech_sara
        ReportReasonCategory.HARASSMENT -> Res.string.report_reason_harassment
        ReportReasonCategory.ADULT_CONTENT -> Res.string.report_reason_adult_content
        ReportReasonCategory.MISINFORMATION -> Res.string.report_reason_misinformation
        ReportReasonCategory.OTHER -> Res.string.report_reason_other
    }
