package id.nearyou.app.screens.search

import id.nearyou.app.search.FakeSearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.fakeSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage of [SearchViewModel]: submit issues `search(query, 0)`; a below-2 query issues nothing
 * and stays Idle; the 500 ms debounce fires after the window; a load-more issues `search(query,
 * nextOffset)` and APPENDS; an empty load-more page is terminal; each outcome is surfaced.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] over an explicit
 * [TestCoroutineScheduler] is installed as Main so non-delayed launches run eagerly and the debounce's
 * `delay(500)` can be advanced deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun submit_issuesSearchAtOffsetZero() {
        val fake = FakeSearchFlow(SearchOutcome.Results(listOf(fakeSearchHit()), 20))
        val vm = SearchViewModel(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        assertTrue(fake.calls.any { it == FakeSearchFlow.Call("jakarta", 0) }, "submit issues search(query, 0): ${fake.calls}")
        assertTrue(vm.outcome.value is SearchOutcome.Results)
    }

    @Test
    fun belowTwoCharQuery_issuesNothing_andClearsOutcome() {
        val fake = FakeSearchFlow()
        val vm = SearchViewModel(fake)
        vm.onQueryChange("a")
        scheduler.advanceUntilIdle()

        assertEquals(0, fake.invocationCount, "a below-2 query issues no request")
        assertEquals(null, vm.outcome.value, "outcome stays null (Idle)")
        assertEquals("a", vm.query.value)
    }

    @Test
    fun debounce_firesAfterTheWindow_notBefore() {
        val fake = FakeSearchFlow(SearchOutcome.Results(emptyList(), null))
        val vm = SearchViewModel(fake)
        vm.onQueryChange("jakarta")

        // The launched debounce enters and suspends at delay(500) — no fetch yet.
        scheduler.runCurrent()
        assertEquals(0, fake.invocationCount, "no fetch before the debounce window elapses")

        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()
        assertEquals(1, fake.invocationCount, "the debounced fetch fires once after 500 ms")
        assertEquals(FakeSearchFlow.Call("jakarta", 0), fake.calls.last())
    }

    @Test
    fun rapidTyping_debounces_toASingleFetch() {
        val fake = FakeSearchFlow(SearchOutcome.Results(emptyList(), null))
        val vm = SearchViewModel(fake)
        vm.onQueryChange("ja")
        scheduler.advanceTimeBy(200)
        vm.onQueryChange("jak")
        scheduler.advanceTimeBy(200)
        vm.onQueryChange("jakarta")
        scheduler.advanceUntilIdle()

        assertEquals(1, fake.invocationCount, "intermediate keystrokes are debounced away: ${fake.calls}")
        assertEquals(FakeSearchFlow.Call("jakarta", 0), fake.calls.single())
    }

    @Test
    fun loadMore_issuesNextOffset_andAppends() {
        val fake =
            FakeSearchFlow(
                firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1")), nextOffset = 20),
                loadMoreOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p2")), nextOffset = null),
            )
        val vm = SearchViewModel(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()

        vm.loadMore()
        scheduler.advanceUntilIdle()

        assertTrue(fake.calls.any { it == FakeSearchFlow.Call("jakarta", 20) }, "load-more uses the retained offset: ${fake.calls}")
        val outcome = vm.outcome.value
        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(listOf("p1", "p2"), outcome.hits.map { it.postId }, "the next page is APPENDED, not replaced")
        assertEquals(null, outcome.nextOffset, "nextOffset advances to the new page's (null → terminal)")
    }

    @Test
    fun loadMore_emptyPage_isTerminal_keepsExistingHits() {
        val fake =
            FakeSearchFlow(
                firstOutcome = SearchOutcome.Results(listOf(fakeSearchHit(postId = "p1")), nextOffset = 20),
                loadMoreOutcome = SearchOutcome.Results(emptyList(), nextOffset = 40),
            )
        val vm = SearchViewModel(fake)
        vm.onQueryChange("jakarta")
        vm.onSubmit()
        scheduler.advanceUntilIdle()
        vm.loadMore()
        scheduler.advanceUntilIdle()

        val outcome = vm.outcome.value
        assertTrue(outcome is SearchOutcome.Results)
        assertEquals(listOf("p1"), outcome.hits.map { it.postId }, "an empty page keeps the existing hits")
        assertEquals(null, outcome.nextOffset, "an empty page is terminal even if nextOffset != null")
    }

    @Test
    fun eachOutcome_isSurfaced() {
        fun outcomeFor(outcome: SearchOutcome): SearchOutcome? {
            val vm = SearchViewModel(FakeSearchFlow(outcome))
            vm.onQueryChange("jakarta")
            vm.onSubmit()
            scheduler.advanceUntilIdle()
            return vm.outcome.value
        }
        assertEquals(SearchOutcome.PremiumGate, outcomeFor(SearchOutcome.PremiumGate))
        assertEquals(SearchOutcome.RateLimited(1740L), outcomeFor(SearchOutcome.RateLimited(1740L)))
        assertEquals(SearchOutcome.Disabled, outcomeFor(SearchOutcome.Disabled))
        assertEquals(SearchOutcome.SessionExpired, outcomeFor(SearchOutcome.SessionExpired))
    }
}
