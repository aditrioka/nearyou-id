package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
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
 * Unit coverage of [GlobalTimelineViewModel] — the `HomeRoute`-scoped holder for the Global feed's
 * load state. Pins: the first page loads exactly once on construction, [GlobalTimelineViewModel.reload]
 * re-fetches (pull-to-refresh + error retry), and a load failure maps to the EXISTING retryable
 * [GlobalTimelineOutcome.NetworkError] (no new outcome member). Mirrors `NearbyTimelineViewModelTest`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main
 * so the init/reload coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalTimelineViewModelTest {
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
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "X")), null, null))
        val viewModel = GlobalTimelineViewModel(fake)
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is GlobalTimelineOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.inFlight.value, "inFlight resets after the load completes")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        val viewModel = GlobalTimelineViewModel(fake)
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeGlobalTimelineFlow(failWith = IllegalStateException("fetch threw"))
        val viewModel = GlobalTimelineViewModel(fake)
        assertEquals(
            GlobalTimelineOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
        )
    }
}
