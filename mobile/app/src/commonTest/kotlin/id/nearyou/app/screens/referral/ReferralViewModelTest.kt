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

/** First call awaits [gate] then returns [first]; every later call returns [second] immediately. */
private class FirstCallGatedReferralRepository(
    private val first: ReferralState,
    private val second: ReferralState,
    private val gate: CompletableDeferred<Unit>,
) : ReferralRepository {
    private var calls = 0

    override suspend fun loadState(): ReferralState? {
        val n = ++calls
        return if (n == 1) {
            gate.await()
            first
        } else {
            second
        }
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
    fun retry_isSingleFlight_aCancelledLoadDoesNotOverwriteTheLaterOne() =
        runTest(dispatcher) {
            // The first load suspends at the gate; retry() cancels it and launches a second load that
            // resolves immediately. Completing the gate afterwards must NOT resurrect the first load's
            // result over the second's (the single-flight fetchJob cancellation).
            val gate = CompletableDeferred<Unit>()
            val first = ReferralState(inviteCode = "AAA", grantedReferrals = 1, milestone = 5, inviterRewardClaimed = false)
            val second = ReferralState(inviteCode = "BBB", grantedReferrals = 2, milestone = 5, inviterRewardClaimed = true)
            val repo = FirstCallGatedReferralRepository(first, second, gate)
            val viewModel = ReferralViewModel(repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(ReferralUiState.Loading, viewModel.uiState.value, "the first load is suspended at the gate")

            viewModel.retry() // cancels the gated first load; the second load resolves immediately
            advanceUntilIdle()
            assertEquals("BBB", (viewModel.uiState.value as ReferralUiState.Loaded).inviteCode)

            gate.complete(Unit) // would resume the cancelled first load if it were not single-flighted
            advanceUntilIdle()
            assertEquals(
                "BBB",
                (viewModel.uiState.value as ReferralUiState.Loaded).inviteCode,
                "the cancelled first load must not overwrite the second",
            )
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
