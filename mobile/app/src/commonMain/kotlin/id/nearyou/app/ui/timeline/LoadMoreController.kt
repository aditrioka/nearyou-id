package id.nearyou.app.ui.timeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The uniform result of a single load-more page fetch — each surface maps its own `Loaded` outcome to
 * this shape so the shared [LoadMoreController] is agnostic of the feed's outcome type (the rate-limit
 * `upsell` on the timeline feeds is mapped out: load-more pages do not re-evaluate it, design D6).
 */
sealed interface LoadMorePage<out T> {
    /** HTTP 200 page. [nextCursor] = `null` ⇒ this was the last page (end-reached). */
    data class Success<out T>(val items: List<T>, val nextCursor: String?) : LoadMorePage<T>

    /** Any non-success (network / 5xx / 4xx / terminal-401) — retryable; the loaded list is kept. */
    data object Failure : LoadMorePage<Nothing>
}

/**
 * The ONE shared cursor load-more (infinite-scroll) lifecycle, mirroring [InlineLikeController]: every
 * paginated surface (`NearbyTimelineViewModel`, `FollowingTimelineViewModel`, `GlobalTimelineViewModel`,
 * `NotificationsViewModel`, `PostDetailViewModel`-replies) holds its OWN instance, generic over the
 * list item type [T], so the append / cursor-advance / in-flight-guard / end-reached / error-footer
 * logic exists exactly once (`mobile-design-system` § "Canonical list load-more (infinite-scroll)
 * pattern" + § "Shared generic load-more controller"). Compose-free and commonTest-unit-testable.
 *
 * The controller does NOT own the list — the host ViewModel does, on its retained `Loaded` outcome.
 * The controller drives appends via [appendItems], reads the live cursor via [currentCursor], and gates
 * eligibility via [canLoadMore] (the host's "not initial-loading and not refreshing" predicate, per the
 * pattern's trigger preconditions). It owns only the load-more-specific UI state ([isLoadingMore],
 * [loadMoreError]) + the per-instance in-flight guard.
 *
 * Lifecycle per [loadMore]:
 *  1. Skip when [canLoadMore] is false (initial load or refresh in flight), when [currentCursor] is
 *     `null` (end-reached), or when a load-more is already in flight (the guard) — no duplicate fetch.
 *  2. Fetch the next page for the current cursor via [fetchPage].
 *  3. `Success` → [appendItems] (the host appends below the retained list + advances its cursor; a
 *     `null` nextCursor leaves the cursor null ⇒ end-reached, no further fetch). `Failure` (or a thrown
 *     non-cancellation) → set [loadMoreError] true (the non-destructive retry footer; the loaded list
 *     is untouched). [retry] re-issues for the still-current cursor.
 *
 * `CancellationException` propagates (never mapped); the in-flight slot is released in `finally`.
 */
class LoadMoreController<T>(
    private val scope: CoroutineScope,
    private val currentCursor: () -> String?,
    private val canLoadMore: () -> Boolean,
    private val fetchPage: suspend (cursor: String) -> LoadMorePage<T>,
    private val appendItems: (items: List<T>, nextCursor: String?) -> Unit,
) {
    private var inFlight = false

    private val _isLoadingMore = MutableStateFlow(false)

    /** True while a load-more page is in flight — drives ONLY the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _loadMoreError = MutableStateFlow(false)

    /** True after a failed load-more — drives the non-destructive retry footer (loaded list retained). */
    val loadMoreError: StateFlow<Boolean> = _loadMoreError.asStateFlow()

    /** Derived end-reached: the host's retained cursor is `null` (last page consumed). */
    val endReached: Boolean get() = currentCursor() == null

    /** Scroll-end trigger. No-op when ineligible (refresh/initial in flight), end-reached, or in flight. */
    fun loadMore() {
        if (!canLoadMore()) return
        val cursor = currentCursor() ?: return
        if (inFlight) return
        inFlight = true
        _isLoadingMore.value = true
        _loadMoreError.value = false
        scope.launch {
            try {
                when (val page = fetchPage(cursor)) {
                    is LoadMorePage.Success -> appendItems(page.items, page.nextCursor)
                    LoadMorePage.Failure -> _loadMoreError.value = true
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _loadMoreError.value = true
            } finally {
                inFlight = false
                _isLoadingMore.value = false
            }
        }
    }

    /** The retry-footer control: re-issue load-more for the still-current cursor. */
    fun retry() = loadMore()

    /** Called by the host on a pull-to-refresh / first-page reload — clears the footer state (the host
     *  simultaneously resets its outcome to the fresh first page, which resets the cursor). */
    fun reset() {
        inFlight = false
        _isLoadingMore.value = false
        _loadMoreError.value = false
    }
}
