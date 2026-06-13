package id.nearyou.app.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.SearchQueryGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 *
 * **One in-flight fetch at a time.** [searchJob] holds the current debounce+fetch coroutine; every new
 * keystroke / submit cancels it BEFORE launching the replacement, so a slow stale fetch (e.g. a fired
 * debounce for "ab") can never land after — and overwrite — a newer query's result ("abc"). The result
 * is committed only after [ensureActive] confirms the job was not superseded, so a cancelled fetch never
 * mutates `_outcome` / `_isLoading`; the latest job (or the Idle branch) owns the final loading state.
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

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    /** The text field's change handler: caps the input at 100 code points, then either schedules a
     *  debounced fetch (eligible query) or returns the screen to Idle (below the guard's minimum). */
    fun onQueryChange(raw: String) {
        val capped = SearchQueryGuard.cap(raw)
        _query.value = capped
        if (!SearchQueryGuard.isEligible(capped)) {
            // Below the threshold → Idle: cancel any in-flight fetch / load-more and clear results.
            searchJob?.cancel()
            loadMoreJob?.cancel()
            _isLoading.value = false
            _isLoadingMore.value = false
            _outcome.value = null
            return
        }
        startSearch(capped, debounce = true)
    }

    /** Keyboard submit (ime action): cancel the pending debounce and fetch the current query now. */
    fun onSubmit() {
        val current = _query.value
        if (!SearchQueryGuard.isEligible(current)) return
        startSearch(current, debounce = false)
    }

    /** Error-retry + the rate-limit "Coba lagi": re-issue the current query's first page now. */
    fun retry() = onSubmit()

    /**
     * Cancel the prior debounce+fetch (and any load-more), then launch a single new one. The cancel
     * runs BEFORE the new job is assigned, so it can never cancel itself; the new job commits its
     * outcome only after [ensureActive], so a superseded (cancelled) fetch never writes stale results.
     */
    private fun startSearch(
        query: String,
        debounce: Boolean,
    ) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        _isLoading.value = true
        _isLoadingMore.value = false
        _outcome.value = null
        searchJob =
            viewModelScope.launch {
                if (debounce) delay(DEBOUNCE_MILLIS)
                val result =
                    try {
                        flow.search(SearchQueryGuard.normalize(query), 0)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        SearchOutcome.NetworkError
                    }
                // Commit only if this job is still the active search (a newer query did not supersede it).
                ensureActive()
                _outcome.value = result
                _isLoading.value = false
            }
    }

    /** "Lihat lebih banyak": fetch the next page and APPEND it to the retained results. */
    fun loadMore() {
        val current = _outcome.value as? SearchOutcome.Results ?: return
        val nextOffset = current.nextOffset ?: return
        if (_isLoadingMore.value) return
        _isLoadingMore.value = true
        loadMoreJob =
            viewModelScope.launch {
                val next =
                    try {
                        flow.search(SearchQueryGuard.normalize(_query.value), nextOffset)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                ensureActive()
                // Only commit if the retained outcome is STILL `current` (the query didn't change under
                // us between the request and the response); otherwise a newer Results would be clobbered.
                if (_outcome.value === current) {
                    _outcome.value =
                        when {
                            next is SearchOutcome.Results && next.hits.isNotEmpty() ->
                                SearchOutcome.Results(current.hits + next.hits, next.nextOffset)
                            // An empty page is terminal even if nextOffset != null (the documented
                            // FTS+OFFSET boundary); a non-Results outcome (e.g. a 429 on the next page)
                            // retains the existing hits and hides the load-more rather than clobbering.
                            else -> current.copy(nextOffset = null)
                        }
                }
                _isLoadingMore.value = false
            }
    }

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 500
    }
}
