package id.nearyou.app.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.SearchQueryGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `SearchRoute`-scoped ViewModel owning the search query + result state. Resolved via
 * `viewModel { … }` under the root `NavDisplay`'s entry decorator for `SearchRoute` (the pushed-route
 * precedent `PostDetailScreen` uses), so it survives recomposition + config change and is cleared when
 * `SearchRoute` is popped. Mirrors the timeline ViewModels' StateFlow shape.
 *
 * A query fires on a [DEBOUNCE_MILLIS] debounce after the last keystroke (via [onQueryChange]) AND
 * immediately on the keyboard submit action (via [onSubmit]) — but ONLY when the [SearchQueryGuard]
 * passes (below-2-char queries issue no request and keep the screen Idle). A "Lihat lebih banyak"
 * [loadMore] appends the next page to the retained results.
 */
class SearchViewModel(
    private val flow: SearchFlow,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _outcome = MutableStateFlow<SearchOutcome?>(null)
    val outcome: StateFlow<SearchOutcome?> = _outcome.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    /** The text field's change handler: caps the input at 100 code points, then either schedules a
     *  debounced fetch (eligible query) or returns the screen to Idle (below the guard's minimum). */
    fun onQueryChange(raw: String) {
        val capped = SearchQueryGuard.cap(raw)
        _query.value = capped
        debounceJob?.cancel()
        if (!SearchQueryGuard.isEligible(capped)) {
            // Below the threshold → Idle: cancel any in-flight fetch and clear prior results.
            searchJob?.cancel()
            _isLoading.value = false
            _isLoadingMore.value = false
            _outcome.value = null
            return
        }
        // Eligible: show Loading immediately (so typing a valid query doesn't flash the prior results),
        // then fire after the debounce window.
        _isLoading.value = true
        _outcome.value = null
        debounceJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                runSearch(capped)
            }
    }

    /** Keyboard submit (ime action): cancel the pending debounce and fetch the current query now. */
    fun onSubmit() {
        val current = _query.value
        if (!SearchQueryGuard.isEligible(current)) return
        debounceJob?.cancel()
        _isLoading.value = true
        _outcome.value = null
        viewModelScope.launch { runSearch(current) }
    }

    /** Error-retry: re-issue the current query's first page. */
    fun retry() = onSubmit()

    /** "Lihat lebih banyak": fetch the next page and APPEND it to the retained results. */
    fun loadMore() {
        val current = _outcome.value as? SearchOutcome.Results ?: return
        val nextOffset = current.nextOffset ?: return
        if (_isLoadingMore.value) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                when (val next = flow.search(SearchQueryGuard.normalize(_query.value), nextOffset)) {
                    is SearchOutcome.Results ->
                        _outcome.value =
                            if (next.hits.isEmpty()) {
                                // An empty page is terminal even if nextOffset != null (the documented
                                // FTS+OFFSET boundary) — keep the existing hits, hide the load-more.
                                current.copy(nextOffset = null)
                            } else {
                                SearchOutcome.Results(
                                    hits = current.hits + next.hits,
                                    nextOffset = next.nextOffset,
                                )
                            }
                    // A non-Results outcome on a load-more (e.g. a 429 on the next page) retains the
                    // existing results and hides the load-more rather than clobbering the user's list (v1).
                    else -> _outcome.value = current.copy(nextOffset = null)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _outcome.value = current.copy(nextOffset = null)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private suspend fun runSearch(rawQuery: String) {
        searchJob?.cancel()
        try {
            _outcome.value = flow.search(SearchQueryGuard.normalize(rawQuery), 0)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            _outcome.value = SearchOutcome.NetworkError
        } finally {
            _isLoading.value = false
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 500
    }
}
