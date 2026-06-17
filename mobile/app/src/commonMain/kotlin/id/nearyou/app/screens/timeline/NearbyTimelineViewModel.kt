package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.timeline.NearbyPostDto
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.ui.timeline.InlineLikeController
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Nearby feed's first-page load state. Resolved via
 * `viewModel { … }` inside the `HomeRoute` `NavEntry` (with `rememberViewModelStoreNavEntryDecorator`),
 * so it **survives the entry going off-screen** — e.g. while the post composer (`PostCreationRoute`)
 * is on top — and is cleared only when `HomeRoute` is popped. That is the fix for the
 * reload-on-return papercut: returning from the composer reuses the already-loaded feed instead of
 * re-running `loadFirstPage()` (which would re-acquire location + re-hit the network). This is the
 * canonical Navigation 3 state-retention pattern (design Decision 5).
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A
 * coordinate-acquisition failure maps to the EXISTING retryable [NearbyTimelineOutcome.NetworkError]
 * (no new outcome member) — identical to the prior in-composable behavior, just hoisted off the
 * composition so it is not lost when the entry is disposed.
 *
 * Loading is split into two distinct flags (mobile-design-system § "Canonical list loading and refresh
 * pattern", design D3), replacing the prior single `inFlight`:
 * - [isInitialLoad] — true only until the FIRST outcome arrives (no content yet). Drives the screen's
 *   skeleton state via `nearbyTimelineUiState(outcome, isInitialLoad)`.
 * - [isRefreshing] — true during a [reload] while a prior outcome is RETAINED (so the list stays
 *   mounted and the screen keeps rendering `Content`). Drives only the `PullToRefreshBox` indicator.
 *
 * On [reload] the existing outcome is kept (never nulled) and [isRefreshing] is set true; the outcome
 * is swapped + [isRefreshing] cleared on completion. So there is never an initial-load skeleton AND a
 * pull-to-refresh spinner at once.
 */
class NearbyTimelineViewModel(
    private val flow: NearbyTimelineFlow,
    likeFlow: LikeFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<NearbyTimelineOutcome?>(null)
    val outcome: StateFlow<NearbyTimelineOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // mobile-inline-post-actions: the per-surface instance of the ONE shared inline-like lifecycle
    // (ui/timeline/InlineLikeController) over the extracted LikeFlow seam — the same
    // PostDetailRepository singleton post-detail uses. The optimistic flip mutates the tapped post
    // inside the retained Loaded outcome; PostGone self-heals via reload().
    private val likeController =
        InlineLikeController(
            likeFlow = likeFlow,
            scope = viewModelScope,
            idOf = { it.id },
            setLiked = { post, liked -> post.copy(likedByViewer = liked) },
            readPosts = { (_outcome.value as? NearbyTimelineOutcome.Loaded)?.posts },
            writePosts = { posts ->
                val current = _outcome.value
                if (current is NearbyTimelineOutcome.Loaded) _outcome.value = current.copy(posts = posts)
            },
            onPostGone = ::reload,
        )

    /** Non-null while the Free like-cap dialog should be shown (one-shot state, docs/11 § 2.2). */
    val likeCapRetryAfterSeconds: StateFlow<Long?> = likeController.capRetryAfterSeconds

    // mobile-nearby-timeline-infinite-scroll: the Nearby instance of the ONE shared load-more lifecycle
    // (ui/timeline/LoadMoreController). Appends pages into the retained Loaded outcome, reusing the
    // page-1 anchor for every follow-up request (design D4 — chronological cursor ⇒ ordering is
    // anchor-independent; anchor is VM-held request state, never rendered/logged). Eligibility-gated so
    // load-more never runs during the initial load or a refresh.
    private val loadMoreController =
        LoadMoreController<NearbyPostDto>(
            scope = viewModelScope,
            currentCursor = { (_outcome.value as? NearbyTimelineOutcome.Loaded)?.nextCursor },
            canLoadMore = { !_isInitialLoad.value && !_isRefreshing.value },
            fetchPage = { cursor ->
                val anchor = (_outcome.value as? NearbyTimelineOutcome.Loaded)?.anchor
                if (anchor == null) {
                    LoadMorePage.Failure
                } else {
                    when (val outcome = flow.loadMore(cursor, anchor)) {
                        is NearbyTimelineOutcome.Loaded -> LoadMorePage.Success(outcome.posts, outcome.nextCursor)
                        else -> LoadMorePage.Failure
                    }
                }
            },
            appendItems = { items, next ->
                val current = _outcome.value
                if (current is NearbyTimelineOutcome.Loaded) {
                    _outcome.value = current.copy(posts = current.posts + items, nextCursor = next)
                }
            },
        )

    /** True while a load-more page is in flight — drives only the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = loadMoreController.isLoadingMore

    /** True after a failed load-more — drives the non-destructive retry footer (loaded list retained). */
    val loadMoreError: StateFlow<Boolean> = loadMoreController.loadMoreError

    init {
        load(initial = true)
    }

    /** Scroll-end trigger from the screen — appends the next page (no-op during initial/refresh or at end). */
    fun onLoadMore() = loadMoreController.loadMore()

    /** Retry control on the load-more error footer — re-issues for the still-current cursor. */
    fun onRetryLoadMore() = loadMoreController.retry()

    /** Inline like tap: [currentlyLiked] is the tapped card's CURRENT state (both directions valid). */
    fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ) = likeController.toggle(postId, currentlyLiked)

    /** Clears the one-shot cap-dialog state (the dialog's dismiss + v1 Premium-CTA wiring). */
    fun onLikeCapDialogDismissed() = likeController.onCapDialogDismissed()

    /** Resolves the tapped card's author UUID from the RAW [NearbyTimelineOutcome] (the DTO carries
     *  `author_user_id`, never rendered/serialized into the PII-free card model). Returns null when the
     *  post is no longer in the loaded set. The host uses it to push `ProfileRoute(authorUserId)`
     *  (`mobile-profile` — the card identity tap), keeping the screen navigation-free. */
    fun authorUserIdForPost(postId: String): String? =
        (_outcome.value as? NearbyTimelineOutcome.Loaded)?.posts?.firstOrNull { it.id == postId }?.authorUserId

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (2026-06-10 audit, 06 medium): stacked PTR + retry taps
        // raced concurrent fetches — latest-writer-wins on outcome and a flickering
        // isRefreshing. One reload at a time; the next gesture re-fires after.
        if (_isRefreshing.value || _isInitialLoad.value) return
        // Refresh resets paging: load() swaps in a fresh first page (dropping the appended tail) and the
        // footer state is cleared here (mobile-design-system § load-more pattern, design D3).
        loadMoreController.reset()
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            // A refresh keeps the prior outcome + flips only isRefreshing (the list stays mounted, the
            // screen keeps rendering Content). The initial load keeps isInitialLoad = true (skeleton).
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // Granted-but-no-fix: the provider could not acquire a coordinate → existing retryable
                // error state (network copy + retry). No new NearbyTimelineOutcome member is introduced.
                _outcome.value = NearbyTimelineOutcome.NetworkError
            } finally {
                // After the first outcome arrives the screen leaves the skeleton for good; subsequent
                // reloads toggle isRefreshing only.
                _isInitialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
