package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.timeline.GlobalPostDto
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.ui.timeline.InlineLikeController
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Global feed's first-page load state. Resolved via
 * `viewModel { … }` inside the Global tab's screen — which (design D1/D2) composes **directly** under
 * the `HomeRoute` `NavEntry`, so the VM binds to the `HomeRoute` ViewModel store and **survives both
 * tab switches and the composer round-trip**, cleared only when `HomeRoute` is popped. That is what
 * makes returning to the Global tab reuse the already-loaded feed instead of re-running
 * `loadFirstPage()` — mirroring the shipped `NearbyTimelineViewModel`.
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A
 * load failure maps to the EXISTING retryable [GlobalTimelineOutcome.NetworkError] (no new outcome
 * member).
 *
 * The single screen state is exposed as ONE [uiState] `StateFlow` (docs/11 §2.2) — the pure
 * `globalTimelineUiState(outcome, isInitialLoad)` projection folded into the ViewModel via `stateIn`, so
 * it is owned here, not recomputed in the composable. The initial-load flag is INTERNAL (`initialLoad`,
 * private), reflected through [uiState] (`Loading` until the first outcome arrives), NOT a separate public
 * flow; [isRefreshing] (true during a [reload] while a prior outcome is retained) drives only the
 * `PullToRefreshBox` indicator and is never folded into [uiState] (mobile-design-system § "Canonical list
 * loading and refresh pattern", design D3). On [reload] the existing outcome is kept and only [isRefreshing]
 * flips, so [uiState] keeps projecting `Content` and there is never a skeleton + pull-to-refresh spinner at
 * once. The raw [outcome] stays exposed as the domain-state seam the controllers + white-box tests read.
 * Mirrors `NearbyTimelineViewModel`.
 */
class GlobalTimelineViewModel(
    private val flow: GlobalTimelineFlow,
    likeFlow: LikeFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<GlobalTimelineOutcome?>(null)
    val outcome: StateFlow<GlobalTimelineOutcome?> = _outcome.asStateFlow()

    private val initialLoad = MutableStateFlow(true)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The single screen state (docs/11 §2.2): the pure [globalTimelineUiState] projection folded into the
     * ViewModel via [stateIn] — computed once per outcome/initial-load change, NOT re-derived in the
     * composable. `Loading` until the first outcome arrives; the refresh indicator is the separate
     * [isRefreshing] flag (design D3), never folded in here.
     */
    val uiState: StateFlow<GlobalTimelineUiState> =
        combine(_outcome, initialLoad) { outcome, isInitialLoad ->
            globalTimelineUiState(outcome, isInitialLoad)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalTimelineUiState.Loading)

    // mobile-inline-post-actions: the Global surface's instance of the SAME shared inline-like
    // controller class Nearby uses (no per-feed duplicate of the lifecycle), over the same LikeFlow
    // Koin singleton. Behavior identical: optimistic flip in the retained Loaded outcome; PostGone
    // self-heals via reload(); NetworkError reverts silently (declared v1).
    private val likeController =
        InlineLikeController(
            likeFlow = likeFlow,
            scope = viewModelScope,
            idOf = { it.id },
            setLiked = { post, liked -> post.copy(likedByViewer = liked) },
            readPosts = { (_outcome.value as? GlobalTimelineOutcome.Loaded)?.posts },
            writePosts = { posts ->
                val current = _outcome.value
                if (current is GlobalTimelineOutcome.Loaded) _outcome.value = current.copy(posts = posts)
            },
            onPostGone = ::reload,
        )

    /** Non-null while the Free like-cap dialog should be shown (one-shot state, docs/11 § 2.2). */
    val likeCapRetryAfterSeconds: StateFlow<Long?> = likeController.capRetryAfterSeconds

    // mobile-nearby-timeline-infinite-scroll (extended to Global): the Global instance of the ONE shared
    // load-more lifecycle (ui/timeline/LoadMoreController). Appends pages into the retained Loaded
    // outcome. Unlike Nearby, Global is CURSOR-ONLY — there is no spatial anchor to reuse (no spatial
    // filter), so fetchPage passes only the cursor. Eligibility-gated so load-more never runs during the
    // initial load or a refresh.
    private val loadMoreController =
        LoadMoreController<GlobalPostDto>(
            scope = viewModelScope,
            currentCursor = { (_outcome.value as? GlobalTimelineOutcome.Loaded)?.nextCursor },
            canLoadMore = { !initialLoad.value && !_isRefreshing.value },
            fetchPage = { cursor ->
                when (val outcome = flow.loadMore(cursor)) {
                    is GlobalTimelineOutcome.Loaded -> LoadMorePage.Success(outcome.posts, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                val current = _outcome.value
                if (current is GlobalTimelineOutcome.Loaded) {
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

    /** Resolves the tapped card's author UUID from the RAW [GlobalTimelineOutcome] (the DTO carries
     *  `author_user_id`, never rendered/serialized into the PII-free card model). Returns null when the
     *  post is no longer in the loaded set. The host uses it to push `ProfileRoute(authorUserId)`
     *  (`mobile-profile` — the card identity tap), keeping the screen navigation-free. */
    fun authorUserIdForPost(postId: String): String? =
        (_outcome.value as? GlobalTimelineOutcome.Loaded)?.posts?.firstOrNull { it.id == postId }?.authorUserId

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (2026-06-10 audit, 06 medium): stacked PTR + retry taps
        // raced concurrent fetches — latest-writer-wins on outcome and a flickering
        // isRefreshing. One reload at a time; the next gesture re-fires after.
        if (_isRefreshing.value || initialLoad.value) return
        // Refresh resets paging: load() swaps in a fresh first page (dropping the appended tail) and the
        // footer state is cleared here (mobile-design-system § load-more pattern, design D3).
        loadMoreController.reset()
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // The fetch threw → existing retryable error state (network copy + retry). No new
                // GlobalTimelineOutcome member is introduced.
                _outcome.value = GlobalTimelineOutcome.NetworkError
            } finally {
                initialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
