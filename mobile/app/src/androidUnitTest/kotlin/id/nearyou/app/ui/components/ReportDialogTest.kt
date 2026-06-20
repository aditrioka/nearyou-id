package id.nearyou.app.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.report_title_post
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val REPORT_TAG = "reportDialogUnderTest"
private const val SUBMIT = "Kirim laporan" // profile_report_submit

/**
 * Render + behavior coverage of the shared [ReportDialog] (mobile-content-report). Covers: the six
 * user-facing categories shown (and NO `self_harm`/`csam_suspected`), the ≤200 **code-point** note gate
 * (incl. a 200-emoji boundary = 400 UTF-16 units accepted, 201 emoji rejected — mirrors the reply
 * composer's 280-code-point precedent), and that submit emits the selected category's wire reason value.
 * The component takes plain params (no Koin), so the tests drive it directly.
 *
 * Runs at the default Robolectric viewport (NO `w360dp-h800dp` qualifier) — mirrors `DailyCapUpsellDialogTest`.
 * An `AlertDialog` hosting an `OutlinedTextField` never reaches Compose idle under the `w360dp-h800dp`
 * qualifier (a Robolectric layout quirk), so the dialog component test uses the default device profile.
 *
 * `@Suppress("DEPRECATION")`: keeps the v1 `runComposeUiTest` API every sibling screen test uses.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ReportDialogTest {
    @Test
    fun showsExactlyTheSixUserFacingCategories_noInternalClassifications() =
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ReportDialog(title = Res.string.report_title_post, onSubmit = { _, _ -> }, onDismiss = {}, testTag = REPORT_TAG)
                }
            }
            onNodeWithText("Spam").assertExists()
            onNodeWithText("Ujaran kebencian (SARA)").assertExists()
            onNodeWithText("Pelecehan").assertExists()
            onNodeWithText("Konten dewasa").assertExists()
            onNodeWithText("Misinformasi").assertExists()
            onNodeWithText("Lainnya").assertExists()
            // The internal/automated classifications are never user-pickable.
            onAllNodesWithText("self_harm", substring = true).assertCountEquals(0)
            onAllNodesWithText("csam", substring = true).assertCountEquals(0)
        }

    @Test
    fun submitEmitsTheSelectedWireReasonCategory() =
        runComposeUiTest {
            var emittedCategory: ReportReasonCategory? = null
            var emittedNote: String? = "unset"
            setContent {
                NearYouTheme {
                    ReportDialog(
                        title = Res.string.report_title_post,
                        onSubmit = { category, note ->
                            emittedCategory = category
                            emittedNote = note
                        },
                        onDismiss = {},
                        testTag = REPORT_TAG,
                    )
                }
            }
            // Pick "Pelecehan" (→ harassment), leave the note blank, submit.
            onNodeWithText("Pelecehan").performClick()
            onNodeWithText(SUBMIT).performClick()
            assertEquals(ReportReasonCategory.HARASSMENT, emittedCategory)
            assertEquals("harassment", emittedCategory?.wireValue, "the emitted category carries the shipped wire value")
            assertNull(emittedNote, "a blank note is emitted as null")
        }

    @Test
    fun noteGate_disablesSubmit_pastTwoHundredCodePoints_emojiBoundary() =
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ReportDialog(title = Res.string.report_title_post, onSubmit = { _, _ -> }, onDismiss = {}, testTag = REPORT_TAG)
                }
            }
            // 200 surrogate-pair emoji = 200 code points (400 UTF-16 units) → within the limit (submit enabled).
            // A regression to `.length` would count 400 and wrongly disable this. Target the field by tag —
            // the placeholder disappears once text is entered.
            onNodeWithTag(REPORT_DIALOG_NOTE_TAG).performTextInput("😀".repeat(200))
            onNodeWithText(SUBMIT).assertIsEnabled()
            // One more emoji → 201 code points → over the limit (submit disabled).
            onNodeWithTag(REPORT_DIALOG_NOTE_TAG).performTextInput("😀")
            onNodeWithText(SUBMIT).assertIsNotEnabled()
        }

    @Test
    fun noteGate_disablesSubmit_at201Ascii() =
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ReportDialog(title = Res.string.report_title_post, onSubmit = { _, _ -> }, onDismiss = {}, testTag = REPORT_TAG)
                }
            }
            onNodeWithTag(REPORT_TAG).assertExists()
            onNodeWithText("Spam").assertExists() // sanity: dialog rendered
            onNodeWithTag(REPORT_DIALOG_NOTE_TAG).performTextInput("a".repeat(201))
            onNodeWithText(SUBMIT).assertIsNotEnabled()
        }
}
