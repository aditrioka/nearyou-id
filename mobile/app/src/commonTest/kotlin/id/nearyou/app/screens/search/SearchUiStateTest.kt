package id.nearyou.app.screens.search

import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.fakeSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage of the pure [searchUiState] projection: every outcome maps to its state deterministically,
 * the below-2 query short-circuits to Idle, the in-flight first-page flag yields Loading, and the
 * Results load-more flag follows `nextOffset`. No wall-clock / platform dependency.
 */
class SearchUiStateTest {
    private val hit = fakeSearchHit()

    @Test
    fun `below-2 query is Idle regardless of outcome`() {
        assertEquals(SearchUiState.Idle, searchUiState("a", null, isLoading = false, isLoadingMore = false))
        assertEquals(
            SearchUiState.Idle,
            // Even with a stale Results outcome, typing back below 2 returns to the prompt.
            searchUiState("a", SearchOutcome.Results(listOf(hit), 20), isLoading = false, isLoadingMore = false),
        )
        assertEquals(SearchUiState.Idle, searchUiState("", null, isLoading = false, isLoadingMore = false))
    }

    @Test
    fun `valid query in flight is Loading`() {
        assertEquals(SearchUiState.Loading, searchUiState("jakarta", null, isLoading = true, isLoadingMore = false))
        // A valid query with no outcome yet (pre-fetch window) also projects to Loading.
        assertEquals(SearchUiState.Loading, searchUiState("jakarta", null, isLoading = false, isLoadingMore = false))
    }

    @Test
    fun `Results non-empty with nextOffset shows load-more`() {
        val state =
            searchUiState("jakarta", SearchOutcome.Results(listOf(hit), 20), isLoading = false, isLoadingMore = false)
        assertTrue(state is SearchUiState.Results)
        assertEquals(1, state.hits.size)
        assertTrue(state.showLoadMore, "nextOffset != null → load-more shown")
        assertEquals(false, state.isLoadingMore)
    }

    @Test
    fun `Results non-empty with null nextOffset hides load-more`() {
        val state =
            searchUiState("jakarta", SearchOutcome.Results(listOf(hit), null), isLoading = false, isLoadingMore = true)
        assertTrue(state is SearchUiState.Results)
        assertEquals(false, state.showLoadMore, "nextOffset == null → load-more hidden")
        assertEquals(true, state.isLoadingMore)
    }

    @Test
    fun `Results empty is EmptyResults carrying the trimmed query`() {
        val state =
            searchUiState("  jakarta  ", SearchOutcome.Results(emptyList(), null), isLoading = false, isLoadingMore = false)
        assertEquals(SearchUiState.EmptyResults("jakarta"), state)
    }

    @Test
    fun `the projected hits carry no PII`() {
        val state =
            searchUiState("jakarta", SearchOutcome.Results(listOf(hit), null), isLoading = false, isLoadingMore = false)
        assertTrue(state is SearchUiState.Results)
        val projected = state.hits.first()
        // SearchHit structurally has no authorId / rank fields — assert the display fields survived.
        assertEquals("dewi.kuliner", projected.authorUsername)
        assertEquals("Dewi Lestari", projected.authorDisplayName)
        assertEquals("p1", projected.postId)
    }

    @Test
    fun `each non-Results outcome maps to its state`() {
        fun state(outcome: SearchOutcome) = searchUiState("jakarta", outcome, isLoading = false, isLoadingMore = false)
        assertEquals(SearchUiState.PremiumGate, state(SearchOutcome.PremiumGate))
        assertEquals(SearchUiState.RateLimited(1740L), state(SearchOutcome.RateLimited(1740L)))
        assertEquals(SearchUiState.Disabled, state(SearchOutcome.Disabled))
        assertEquals(SearchUiState.Error, state(SearchOutcome.Error))
        assertEquals(SearchUiState.Error, state(SearchOutcome.NetworkError))
        assertEquals(SearchUiState.SessionRedirect, state(SearchOutcome.SessionExpired))
    }
}
