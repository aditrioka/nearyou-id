package id.nearyou.app.screens.referral

import id.nearyou.app.referral.ReferralRepository
import id.nearyou.app.referral.ReferralState
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
import kotlin.test.assertTrue

/** A programmable [ReferralRepository]: returns [results] in order (last value repeats); optional [gate] suspends. */
private class FakeReferralRepository(
    private val results: List<ReferralState?>,
    private val gate: CompletableDeferred<Unit>? = null,
) : ReferralRepository {
    var loadCalls = 0
        private set

    override suspend fun loadState(): ReferralState? {
        gate?.await()
        val index = loadCalls.coerceAtMost(results.lastIndex)
        loadCalls++
        return results[index]
    }
}

private val sampleState = ReferralState(inviteCode = "a3f7k2mq", grantedReferrals = 3, milestone = 5, inviterRewardClaimed = false)

/**
 * Unit coverage of [ReferralViewModel]: the single-`stateIn` uiState transitions loading → loaded on a
 * successful fetch, loading → error on a failed fetch, and a [ReferralViewModel.retry] re-invokes the
 * repository. Mirrors `UsernameCustomizationViewModelTest`: a shared [UnconfinedTestDispatcher] is `Main`
 * and a `backgroundScope` collector activates the `WhileSubscribed` share so `uiState.value` reflects state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferralViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun successfulFetch_transitionsLoadingToLoaded() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel = ReferralViewModel(FakeReferralRepository(listOf(sampleState), gate = gate))
            val emissions = mutableListOf<ReferralUiState>()
            backgroundScope.launch { viewModel.uiState.collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(ReferralUiState.Loading, viewModel.uiState.value, "the fetch is suspended at the gate")

            gate.complete(Unit)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value
            assertTrue(loaded is ReferralUiState.Loaded)
            assertEquals("a3f7k2mq", loaded.inviteCode)
            assertEquals(3, loaded.grantedReferrals)
            assertEquals(5, loaded.milestone)
            assertEquals(false, loaded.inviterRewardClaimed)
            assertEquals(ReferralUiState.Loading, emissions.first(), "the first emission is Loading")
            assertTrue(emissions.last() is ReferralUiState.Loaded, "the last emission is Loaded")
        }

    @Test
    fun failedFetch_transitionsToError() =
        runTest(dispatcher) {
            val viewModel = ReferralViewModel(FakeReferralRepository(listOf(null)))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(ReferralUiState.Error, viewModel.uiState.value, "a failed fetch lands on Error (no crash)")
        }

    @Test
    fun retry_reInvokesTheFetch_andLoadsOnTheSecondResult() =
        runTest(dispatcher) {
            // First load fails (null → Error); retry re-fetches and succeeds.
            val repo = FakeReferralRepository(listOf(null, sampleState))
            val viewModel = ReferralViewModel(repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(ReferralUiState.Error, viewModel.uiState.value)

            viewModel.retry()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is ReferralUiState.Loaded, "retry re-fetches and loads")
            assertEquals(2, repo.loadCalls, "retry re-invokes the repository fetch")
        }
}
