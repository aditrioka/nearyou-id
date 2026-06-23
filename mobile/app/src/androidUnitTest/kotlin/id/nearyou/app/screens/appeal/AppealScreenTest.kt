package id.nearyou.app.screens.appeal

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.appeal.AppealFlow
import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.appeal.AppealStatusOutcome
import id.nearyou.app.appeal.AppealSubmitOutcome
import id.nearyou.app.appeal.FakeAppealFlow
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val INTRO = "Kamu bisa mengajukan banding atas tindakan moderasi pada akunmu. Tim kami akan meninjau dan memberi keputusan."
private const val SUBMIT = "Kirim banding"
private const val PENDING_TITLE = "Banding sedang ditinjau"
private const val REJECTED_TITLE = "Banding ditolak"
private const val REJECTED_REASON = "Alasan: Tidak cukup alasan."
private const val RATE_LIMITED_2 = "Terlalu banyak permintaan. Coba lagi dalam 2 menit."
private const val COUNTER_FULL = "1000/1000"

/**
 * Render + behavior coverage of `AppealScreen` via the Robolectric-backed CMP UI runner (task 5.5). Drives
 * each state through a `FakeAppealFlow` (the outcome→state projection is covered purely by
 * `AppealViewModelTest`): the editor form, submit→Pending, an on-entry already-pending status, a submit
 * rate-limit, a decided-rejected status + reason, and the 1000-char editor cap. The `KoinContext` +
 * multi-test startKoin cycle mirrors `SearchScreenTest`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class AppealScreenTest {
    private fun installKoin(
        status: AppealStatusOutcome = AppealStatusOutcome.None,
        submit: AppealSubmitOutcome = AppealSubmitOutcome.Submitted("a1"),
        token: String? = "tok-1",
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<AppealFlow> { FakeAppealFlow(statusOutcome = status, submitOutcome = submit) }
                    single { AppealSession().apply { token?.let { set(it) } } }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun ComposeUiTest.content() {
        setContent { KoinContext { NearYouTheme { AppealScreen(onBack = {}, onReSignIn = {}) } } }
    }

    @Test
    fun onEntryNoAppeal_showsTheEditorForm() {
        installKoin(status = AppealStatusOutcome.None)
        runComposeUiTest {
            content()
            onNodeWithText(INTRO).assertExists()
            onNodeWithTag(APPEAL_FIELD_TAG).assertExists()
            // A blank editor cannot submit.
            onNodeWithTag(APPEAL_SUBMIT_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun submit_transitionsToPending() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.Submitted("a1"))
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("mohon ditinjau")
            waitForIdle()
            onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithText(PENDING_TITLE).assertExists()
        }
    }

    @Test
    fun onEntryPending_showsThePendingStatus() {
        installKoin(status = AppealStatusOutcome.Pending(actionType = "suspension", createdAt = null))
        runComposeUiTest {
            content()
            onNodeWithText(PENDING_TITLE).assertExists()
        }
    }

    @Test
    fun submitRateLimited_showsTheTryLaterCopy() {
        installKoin(status = AppealStatusOutcome.None, submit = AppealSubmitOutcome.RateLimited(retryAfterSeconds = 120))
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("x")
            waitForIdle()
            onNodeWithTag(APPEAL_SUBMIT_TAG).performClick()
            waitForIdle()
            onNodeWithText(RATE_LIMITED_2).assertExists()
        }
    }

    @Test
    fun onEntryRejected_showsTheTitleAndReason() {
        installKoin(
            status =
                AppealStatusOutcome.Decided(
                    approved = false,
                    actionType = "suspension",
                    decisionReason = "Tidak cukup alasan.",
                    reviewedAt = null,
                ),
        )
        runComposeUiTest {
            content()
            onNodeWithText(REJECTED_TITLE).assertExists()
            onNodeWithText(REJECTED_REASON, substring = true).assertExists()
        }
    }

    @Test
    fun editor_capsAtTheThousandCharLimit() {
        installKoin(status = AppealStatusOutcome.None)
        runComposeUiTest {
            content()
            onNodeWithTag(APPEAL_FIELD_TAG).performTextInput("a".repeat(1050))
            waitForIdle()
            onNodeWithText(COUNTER_FULL).assertExists()
        }
    }
}
