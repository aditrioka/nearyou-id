package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeNearbyPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage of [NearbyTimelineViewModel] — the `HomeRoute`-scoped holder for the Nearby feed's
 * load state (the reload-on-return fix, `mobile-nav-swap-to-navigation3` Decision 5). Pins: the first
 * page loads exactly once on construction, [NearbyTimelineViewModel.reload] re-fetches (pull-to-refresh
 * + error retry), a load failure maps to the EXISTING retryable [NearbyTimelineOutcome.NetworkError]
 * (no new outcome member), and the split-loading contract (design D3): a reload toggles `isRefreshing`
 * (NOT `isInitialLoad`) and RETAINS the prior outcome so the screen keeps rendering `Content`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main
 * so the init/reload coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyTimelineViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsFirstPageExactlyOnce_andExposesTheOutcome() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "X")), null, null))
        val viewModel = NearbyTimelineViewModel(fake)
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is NearbyTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isInitialLoad.value, "isInitialLoad clears once the first outcome arrives")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = NearbyTimelineViewModel(fake)
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun reload_keepsPriorOutcome_andTogglesIsRefreshingNotIsInitialLoad() {
        // The first load completes (a Loaded outcome, isInitialLoad = false); the SECOND call (reload)
        // suspends, so we observe the in-flight refresh: isRefreshing = true, isInitialLoad stays false,
        // and the prior outcome is retained (not nulled) so the screen keeps rendering Content (design D3).
        val loaded = NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "RETAINED")), null, null)
        val fake = FakeNearbyTimelineFlow(loaded, suspendFromCall = 2)
        val viewModel = NearbyTimelineViewModel(fake)
        assertFalse(viewModel.isInitialLoad.value, "after the first load isInitialLoad is false")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")
        val priorOutcome = viewModel.outcome.value

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertFalse(viewModel.isInitialLoad.value, "a reload does NOT re-enter the initial-load skeleton")
        assertEquals(priorOutcome, viewModel.outcome.value, "the prior outcome is retained during the refresh")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeNearbyTimelineFlow(failWith = IllegalStateException("granted but no fix"))
        val viewModel = NearbyTimelineViewModel(fake)
        assertEquals(
            NearbyTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a coordinate/network failure maps to the existing retryable NetworkError (no new outcome member)",
        )
    }
}
