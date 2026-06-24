package id.nearyou.app.screens.consent

import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.consent.FakeConsentFlow
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [ConsentViewModel] (audit 05-#5 migration): the single-`stateIn` uiState delegates to
 * the pure [consentUiState] and surfaces the three toggles; the toggle selections survive a fresh `uiState`
 * read (the config-change proxy); a submit-`200` writes the durable snapshot once then raises the `done`
 * one-shot; the in-flight guard issues exactly one PATCH; and a retryable error writes no snapshot while
 * surfacing the non-trapping skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    // Default seed: analytics OFF, crash ON, ads OFF (the V2 column default).
    private fun vm(
        consentFlow: FakeConsentFlow = FakeConsentFlow(),
        store: InMemoryConsentSnapshotStore = InMemoryConsentSnapshotStore(),
    ): ConsentViewModel = ConsentViewModel(consentFlow, store)

    @Test
    fun uiState_delegatesToConsentUiState_andSurfacesTheDefaultToggles() =
        runTest(dispatcher) {
            val viewModel = vm()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(ConsentCtaLabel.CONTINUE, viewModel.uiState.value.ctaLabel)
            assertTrue(viewModel.uiState.value.ctaEnabled)
            assertFalse(viewModel.uiState.value.showSkip)
            assertFalse(viewModel.uiState.value.analytics)
            assertTrue(viewModel.uiState.value.crash)
            assertFalse(viewModel.uiState.value.ads)
        }

    @Test
    fun toggleSelections_surviveAFreshUiStateRead() =
        runTest(dispatcher) {
            // Config-change proxy: the entry-scoped VM retains the flipped toggles; a fresh collector
            // (modeling a recreated composition) still observes analytics ON / crash OFF, not the defaults.
            val viewModel = vm()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onAnalyticsChange(true)
            viewModel.onCrashChange(false)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.analytics)
            assertFalse(viewModel.uiState.value.crash)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.analytics, "analytics ON survives a fresh read")
            assertFalse(viewModel.uiState.value.crash, "crash OFF survives a fresh read")
            assertFalse(viewModel.uiState.value.ads)
        }

    @Test
    fun success_writesSnapshotOnce_thenRaisesDone() =
        runTest(dispatcher) {
            val flow = FakeConsentFlow(outcome = ConsentOutcome.Success)
            val store = InMemoryConsentSnapshotStore()
            val viewModel = vm(consentFlow = flow, store = store)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onAnalyticsChange(true) // submit the flipped triple
            viewModel.onContinueClick()
            advanceUntilIdle()
            assertEquals(1, flow.submitInvocationCount)
            assertEquals(
                ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false),
                store.read(),
                "the submitted triple is persisted to the durable snapshot",
            )
            assertTrue(viewModel.uiState.value.done, "Success raises the done one-shot")
            viewModel.onDoneHandled()
            assertFalse(viewModel.uiState.value.done, "the one-shot clears")
        }

    @Test
    fun doubleTap_issuesExactlyOnePatch() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val flow = FakeConsentFlow(outcome = ConsentOutcome.Success, gate = gate)
            val viewModel = vm(consentFlow = flow)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onContinueClick() // suspends at the gate — inFlight = true
            advanceUntilIdle()
            assertEquals(ConsentCtaLabel.LOADING, viewModel.uiState.value.ctaLabel)
            assertFalse(viewModel.uiState.value.ctaEnabled)
            viewModel.onContinueClick() // guarded — no second PATCH
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, flow.submitInvocationCount)
        }

    @Test
    fun retryableError_writesNoSnapshot_andSurfacesTheSkip() =
        runTest(dispatcher) {
            val flow = FakeConsentFlow(outcome = ConsentOutcome.RetryableError)
            val store = InMemoryConsentSnapshotStore()
            val viewModel = vm(consentFlow = flow, store = store)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onContinueClick()
            advanceUntilIdle()
            assertNull(store.read(), "a retryable error writes no snapshot")
            assertFalse(viewModel.uiState.value.done, "a retryable error does not route Home")
            assertTrue(viewModel.uiState.value.showSkip, "the non-trapping skip appears after a failed submit")
            assertEquals(ConsentBanner.RETRYABLE, viewModel.uiState.value.banner)
        }
}
