package id.nearyou.app.screens.appeal

import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.appeal.AppealStatusOutcome
import id.nearyou.app.appeal.AppealSubmitOutcome
import id.nearyou.app.appeal.FakeAppealFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit coverage of [AppealViewModel] — the on-entry own-status load, the submit→status mapping, the no-token
 * re-route, and the editor cap. The `WhileSubscribed` uiState is activated via a background collector (the
 * `FollowListViewModelTest` precedent).
 */
class AppealViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(
        flow: FakeAppealFlow,
        token: String? = "tok-1",
    ): AppealViewModel {
        val session = AppealSession().apply { token?.let { set(it) } }
        val viewModel = AppealViewModel(flow, session)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `no appeal token re-routes to sign-in without any status read`() =
        runTest {
            val flow = FakeAppealFlow()
            val viewModel = vm(flow, token = null)
            advanceUntilIdle()
            assertEquals(AppealStatus.SessionRedirect, viewModel.uiState.value.status)
            assertEquals(null, flow.lastToken, "no status read is issued without a token")
        }

    @Test
    fun `on-entry maps none to Form, pending to Pending, decided to Decided`() =
        runTest {
            val none = vm(FakeAppealFlow(statusOutcome = AppealStatusOutcome.None))
            advanceUntilIdle()
            assertIs<AppealStatus.Form>(none.uiState.value.status)

            val pending = vm(FakeAppealFlow(statusOutcome = AppealStatusOutcome.Pending("suspension", "2026-06-23")))
            advanceUntilIdle()
            assertEquals(AppealStatus.Pending("suspension"), pending.uiState.value.status)

            val decided =
                vm(
                    FakeAppealFlow(
                        statusOutcome =
                            AppealStatusOutcome.Decided(
                                approved = false,
                                actionType = "permanent_ban",
                                decisionReason = "Tidak cukup alasan.",
                                reviewedAt = null,
                            ),
                    ),
                )
            advanceUntilIdle()
            assertEquals(AppealStatus.Decided(approved = false, decisionReason = "Tidak cukup alasan."), decided.uiState.value.status)
        }

    @Test
    fun `submit success forwards text + token and transitions to Pending`() =
        runTest {
            val flow = FakeAppealFlow(submitOutcome = AppealSubmitOutcome.Submitted("a1"))
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onTextChange("mohon ditinjau")
            assertTrue(viewModel.uiState.value.submitEnabled, "a non-blank editor enables submit")
            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals(AppealStatus.Pending(null), viewModel.uiState.value.status)
            assertEquals("mohon ditinjau", flow.lastSubmittedText)
            assertEquals("tok-1", flow.lastToken)
        }

    @Test
    fun `submit already-pending also transitions to Pending`() =
        runTest {
            val viewModel = vm(FakeAppealFlow(submitOutcome = AppealSubmitOutcome.AlreadyPending))
            advanceUntilIdle()
            viewModel.onTextChange("lagi")
            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals(AppealStatus.Pending(null), viewModel.uiState.value.status)
        }

    @Test
    fun `submit rate-limited surfaces the inline Form error`() =
        runTest {
            val viewModel = vm(FakeAppealFlow(submitOutcome = AppealSubmitOutcome.RateLimited(120)))
            advanceUntilIdle()
            viewModel.onTextChange("x")
            viewModel.onSubmit()
            advanceUntilIdle()
            val status = assertIs<AppealStatus.Form>(viewModel.uiState.value.status)
            assertIs<AppealFormError.RateLimited>(status.error)
        }

    @Test
    fun `submit not-eligible and invalid-text surface their inline Form errors`() =
        runTest {
            val notEligible = vm(FakeAppealFlow(submitOutcome = AppealSubmitOutcome.NotEligible))
            advanceUntilIdle()
            notEligible.onTextChange("x")
            notEligible.onSubmit()
            advanceUntilIdle()
            assertEquals(AppealStatus.Form(AppealFormError.NotEligible), notEligible.uiState.value.status)

            val invalid = vm(FakeAppealFlow(submitOutcome = AppealSubmitOutcome.InvalidText))
            advanceUntilIdle()
            invalid.onTextChange("x")
            invalid.onSubmit()
            advanceUntilIdle()
            assertEquals(AppealStatus.Form(AppealFormError.InvalidText), invalid.uiState.value.status)
        }

    @Test
    fun `submit session-expired re-routes to sign-in`() =
        runTest {
            val viewModel = vm(FakeAppealFlow(submitOutcome = AppealSubmitOutcome.SessionExpired))
            advanceUntilIdle()
            viewModel.onTextChange("x")
            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals(AppealStatus.SessionRedirect, viewModel.uiState.value.status)
        }

    @Test
    fun `editor caps at the limit and counts characters, blank cannot submit`() =
        runTest {
            val flow = FakeAppealFlow()
            val viewModel = vm(flow)
            advanceUntilIdle()
            viewModel.onTextChange("   ")
            assertFalseSubmit(viewModel)

            viewModel.onTextChange("a".repeat(APPEAL_TEXT_LIMIT + 50))
            advanceUntilIdle()
            assertEquals(APPEAL_TEXT_LIMIT, viewModel.uiState.value.charCount, "text is capped at the limit")
            assertEquals(APPEAL_TEXT_LIMIT, viewModel.uiState.value.appealText.length)
        }

    @Test
    fun `on-entry status read failure shows the retryable LoadError`() =
        runTest {
            val viewModel = vm(FakeAppealFlow(statusOutcome = AppealStatusOutcome.TransportError))
            advanceUntilIdle()
            assertEquals(AppealStatus.LoadError, viewModel.uiState.value.status)
        }
}

private fun assertFalseSubmit(viewModel: AppealViewModel) {
    assertEquals(false, viewModel.uiState.value.submitEnabled, "a blank editor cannot submit")
}
