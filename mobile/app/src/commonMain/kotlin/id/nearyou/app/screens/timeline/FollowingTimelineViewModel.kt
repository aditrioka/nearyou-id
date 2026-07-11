package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.timeline.FollowingPostDto
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.ui.timeline.InlineLikeController
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import id.nearyou.app.ui.timeline.TimelineBlockController
import id.nearyou.app.ui.timeline.TimelineBlockMessage
import id.nearyou.app.ui.timeline.TimelineBlockTarget
import id.nearyou.app.ui.timeline.TimelineReportController
import id.nearyou.app.ui.timeline.TimelineReportMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Following feed's first-page load state. Resolved via
 * `viewModel { … }` inside the Following tab's screen — which (design D1/D2) composes **directly** under
 * the `HomeRoute` `NavEntry`, so the VM binds to the `HomeRoute` ViewModel store and **survives feed
 * swipes/tab switches, bottom-nav section switches, AND the composer round-trip**, cleared only when
 * `HomeRoute` is popped. That is what makes returning to the Following tab reuse the already-loaded feed
 * instead of re-running `loadFirstPage()` — mirroring `NearbyTimelineViewModel` / `GlobalTimelineViewModel`.
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A load
 * failure maps to the EXISTING retryable [FollowingTimelineOutcome.NetworkError] (no new outcome member).
 *
 * The single screen state is exposed as ONE [uiState] `StateFlow` (docs/11 §2.2) — the pure
 * `followingTimelineUiState(outcome, isInitialLoad)` projection folded into the ViewModel via `stateIn`, so
 * it is owned here, not recomputed in the composable. The initial-load flag is INTERNAL (`initialLoad`,
 * private), reflected through [uiState] (`Loading` until the first outcome arrives), NOT a separate public
 * flow; [isRefreshing] (true during a [reload] while a prior outcome is retained) drives only the
 * `PullToRefreshBox` indicator and is never folded into [uiState] (the canonical loading/refresh pattern,
 * design D3). The raw [outcome] stays exposed as the domain-state seam the controllers + white-box tests
 * read. Mirrors `GlobalTimelineViewModel`.
 */
class FollowingTimelineViewModel(
    private val flow: FollowingTimelineFlow,
    likeFlow: LikeFlow,
    reportSubmitter: ReportSubmitter,
    private val selfUserIdProvider: SelfUserIdProvider,
    blockSubmitter: BlockSubmitter,
) : ViewModel() {
    private val _outcome = MutableStateFlow<FollowingTimelineOutcome?>(null)
    val outcome: StateFlow<FollowingTimelineOutcome?> = _outcome.asStateFlow()

    private val initialLoad = MutableStateFlow(true)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The single screen state (docs/11 §2.2): the pure [followingTimelineUiState] projection folded into
     * the ViewModel via [stateIn] — computed once per outcome/initial-load change, NOT re-derived in the
     * composable. `Loading` until the first outcome arrives; the refresh indicator is the separate
     * [isRefreshing] flag (design D3), never folded in here.
     */
    val uiState: StateFlow<FollowingTimelineUiState> =
        combine(_outcome, initialLoad) { outcome, isInitialLoad ->
            followingTimelineUiState(outcome, isInitialLoad)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowingTimelineUiState.Loading)

    // mobile-inline-post-actions: the Following surface's instance of the SAME shared inline-like
    // controller class Nearby/Global use (no per-feed duplicate of the lifecycle), over the same LikeFlow
    // Koin singleton. Behavior identical: optimistic flip in the retained Loaded outcome; PostGone
    // self-heals via reload(); NetworkError reverts silently (declared v1).
    private val likeController =
        InlineLikeController(
            likeFlow = likeFlow,
            scope = viewModelScope,
            idOf = { it.id },
            setLiked = { post, liked -> post.copy(likedByViewer = liked) },
            readPosts = { (_outcome.value as? FollowingTimelineOutcome.Loaded)?.posts },
            writePosts = { posts ->
                val current = _outcome.value
                if (current is FollowingTimelineOutcome.Loaded) _outcome.value = current.copy(posts = posts)
            },
            onPostGone = ::reload,
        )

    /** Non-null while the Free like-cap dialog should be shown (one-shot state, docs/11 § 2.2). */
    val likeCapRetryAfterSeconds: StateFlow<Long?> = likeController.capRetryAfterSeconds

    // mobile-nearby-timeline-infinite-scroll (extended to Following): the Following instance of the ONE
    // shared load-more lifecycle (ui/timeline/LoadMoreController). Appends pages into the retained Loaded
    // outcome. Following is CURSOR-ONLY — there is no spatial anchor to reuse (unlike Nearby). Eligibility-
    // gated so load-more never runs during the initial load or a refresh.
    private val loadMoreController =
        LoadMoreController<FollowingPostDto>(
            scope = viewModelScope,
            currentCursor = { (_outcome.value as? FollowingTimelineOutcome.Loaded)?.nextCursor },
            canLoadMore = { !initialLoad.value && !_isRefreshing.value },
            fetchPage = { cursor ->
                when (val outcome = flow.loadMore(cursor)) {
                    is FollowingTimelineOutcome.Loaded -> LoadMorePage.Success(outcome.posts, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                val current = _outcome.value
                if (current is FollowingTimelineOutcome.Loaded) {
                    _outcome.value = current.copy(posts = current.posts + items, nextCursor = next)
                }
            },
        )

    /** True while a load-more page is in flight — drives only the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = loadMoreController.isLoadingMore

    /** True after a failed load-more — drives the non-destructive retry footer (loaded list retained). */
    val loadMoreError: StateFlow<Boolean> = loadMoreController.loadMoreError

    // timeline-card-report-kebab: the per-surface instance of the ONE shared report lifecycle
    // (ui/timeline/TimelineReportController) over the shared ReportSubmitter seam (target_type=post).
    private val reportController = TimelineReportController(reportSubmitter, viewModelScope)

    /** Non-null while the report dialog should be shown (one-shot, docs/11 § 2.2). */
    val reportingPostId: StateFlow<String?> = reportController.reportingPostId

    /** The one-shot report-result message (anti-enumeration mapping in the shared controller). */
    val reportMessage: StateFlow<TimelineReportMessage?> = reportController.reportMessage

    // timeline-card-block-kebab: the per-surface instance of the ONE shared block lifecycle
    // (ui/timeline/TimelineBlockController) over the shared BlockSubmitter seam. A Blocked outcome
    // filters the blocked author's posts out of the retained Loaded outcome (design D4 — mutual
    // invisibility made immediate; cursor untouched, later pages are server-side excluded).
    private val blockController =
        TimelineBlockController(
            blockSubmitter = blockSubmitter,
            scope = viewModelScope,
            removeAuthorPosts = { authorUserId ->
                val current = _outcome.value
                if (current is FollowingTimelineOutcome.Loaded) {
                    _outcome.value = current.copy(posts = current.posts.filterNot { it.authorUserId == authorUserId })
                }
            },
        )

    /** Non-null while the block confirmation dialog should be shown (one-shot, docs/11 § 2.2). */
    val blockTarget: StateFlow<TimelineBlockTarget?> = blockController.blockTarget

    /** The one-shot block-result message (Blocked/RateLimited/NetworkError mapping in the controller). */
    val blockMessage: StateFlow<TimelineBlockMessage?> = blockController.blockMessage

    // The viewer's own user id, resolved once on entry — the report + block kebabs' authorship gate.
    // Null until resolved (or unresolvable) → NO kebab (fail-closed: never offer an action we can't
    // gate). A Following feed can't contain the viewer's own posts (no self-follow), but the uniform
    // gate keeps the three feeds one shape.
    private val _selfUserId = MutableStateFlow<String?>(null)
    val selfUserId: StateFlow<String?> = _selfUserId.asStateFlow()

    init {
        load(initial = true)
        viewModelScope.launch { _selfUserId.value = selfUserIdProvider.selfUserId() }
    }

    /** The card kebab's report action for [postId], or null when ineligible: the self id is unresolved
     *  (fail-closed), the post left the loaded set, or the viewer authored it. [selfUserId] is passed
     *  back by the screen (collected compose state) so resolving it recomposes the cards. The authorship
     *  comparison runs on the RAW DTO's `authorUserId` — the PII-free card model stays UUID-free. */
    fun reportActionFor(
        postId: String,
        selfUserId: String?,
    ): (() -> Unit)? {
        if (selfUserId == null) return null
        val authorUserId = authorUserIdForPost(postId) ?: return null
        if (authorUserId == selfUserId) return null
        return { reportController.onReportClicked(postId) }
    }

    /** The card kebab's block action for [postId], or null when ineligible — the same fail-closed gate
     *  as [reportActionFor] (unresolved self id / post left the loaded set / viewer-authored). The
     *  closure carries the RAW DTO's `authorUserId` + `authorUsername`; the card model stays UUID-free. */
    fun blockActionFor(
        postId: String,
        selfUserId: String?,
    ): (() -> Unit)? {
        if (selfUserId == null) return null
        val post =
            (_outcome.value as? FollowingTimelineOutcome.Loaded)?.posts?.firstOrNull { it.id == postId }
                ?: return null
        if (post.authorUserId == selfUserId) return null
        return { blockController.onBlockClicked(post.authorUserId, post.authorUsername) }
    }

    /** Dismiss the block dialog without submitting. */
    fun onBlockDialogDismissed() = blockController.onDialogDismissed()

    /** Confirm the block for the currently-targeted author (dialog closes immediately). */
    fun onBlockConfirmed() = blockController.onConfirmed()

    /** Clears the one-shot block message after the screen has shown it. */
    fun onBlockMessageShown() = blockController.onMessageShown()

    /** Dismiss the report dialog without submitting. */
    fun onReportDialogDismissed() = reportController.onDialogDismissed()

    /** Submit the report for the currently-targeted post (dialog closes immediately). */
    fun onReportSubmitted(
        category: ReportReasonCategory,
        note: String?,
    ) = reportController.onSubmitted(category, note)

    /** Clears the one-shot report message after the screen has shown it. */
    fun onReportMessageShown() = reportController.onMessageShown()

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

    /** Resolves the tapped card's author UUID from the RAW [FollowingTimelineOutcome] (the DTO carries
     *  `author_user_id`, never rendered/serialized into the PII-free card model). Returns null when the
     *  post is no longer in the loaded set. The host uses it to push `ProfileRoute(authorUserId)`
     *  (`mobile-profile` — the card identity tap), keeping the screen navigation-free. */
    fun authorUserIdForPost(postId: String): String? =
        (_outcome.value as? FollowingTimelineOutcome.Loaded)?.posts?.firstOrNull { it.id == postId }?.authorUserId

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (mirrors Global/Nearby): stacked PTR + retry taps would otherwise race
        // concurrent fetches. One reload at a time; the next gesture re-fires after.
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
                // FollowingTimelineOutcome member is introduced.
                _outcome.value = FollowingTimelineOutcome.NetworkError
            } finally {
                initialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
