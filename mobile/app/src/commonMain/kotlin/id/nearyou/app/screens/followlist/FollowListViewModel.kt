package id.nearyou.app.screens.followlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.followlist.FollowListFlow
import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.followlist.FollowListUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The follow-list screen's state holder (docs/11 § 2.2): an androidx [ViewModel] in commonMain, obtained
 * via `viewModel { … }` scoped to the `FollowListRoute` Nav3 entry. Holds per-tab state for BOTH tabs
 * (followers + following) over the same profile [userId], exposes ONE [StateFlow]<[FollowListUiState]> via
 * `stateIn(WhileSubscribed)`, and talks ONLY to [FollowListFlow] (never the ApiClient — the
 * `UI → ViewModel → Repository → ApiClient` direction).
 *
 * The deep-linked [initialTab] fetches its first page immediately; the other tab fetches lazily the first
 * time it is revealed ([onTabRevealed]). Each tab paginates by keyset ([onLoadMore], guarded against
 * duplicate-in-flight / null-cursor / a prior failed page), supports pull-to-refresh without flipping to
 * the initial-load skeleton ([onRefresh] drives `isRefreshing`, not `isInitialLoad`), and retains its
 * loaded rows when a load-more fails ([onLoadMore] sets `loadMoreFailed`; the footer retries via
 * [onRetryLoadMore]). Row taps navigate at the screen (the row's `userId` → `ProfileRoute`); the VM does no
 * navigation.
 */
class FollowListViewModel(
    private val flow: FollowListFlow,
    private val userId: String,
    val initialTab: FollowListTab,
) : ViewModel() {
    private data class TabState(
        val rows: List<FollowListUser> = emptyList(),
        val nextCursor: String? = null,
        val isInitialLoad: Boolean = true,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
        val latestOutcome: FollowListOutcome? = null,
        val hasStarted: Boolean = false,
    )

    private data class VmState(
        val followers: TabState = TabState(),
        val following: TabState = TabState(),
    )

    private val state = MutableStateFlow(VmState())

    val uiState: StateFlow<FollowListUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    init {
        // The deep-linked initial tab fetches immediately; the other tab fetches lazily on first reveal.
        onTabRevealed(initialTab)
    }

    /** First-display lazy fetch: each tab loads its first page the first time it is shown (the initial tab
     *  via init; the other when the user swipes/taps to it). A no-op once that tab has started. */
    fun onTabRevealed(tab: FollowListTab) {
        if (tabState(tab).hasStarted) return
        updateTab(tab) { it.copy(hasStarted = true, isInitialLoad = true) }
        viewModelScope.launch {
            val outcome = flow.load(userId, tab, null)
            updateTab(tab) { it.withFirstPage(outcome) }
        }
    }

    /** Initial-load / not-found / error retry — reloads the tab's first page from scratch. */
    fun onRetry(tab: FollowListTab) {
        updateTab(tab) { it.copy(isInitialLoad = true, loadMoreFailed = false) }
        viewModelScope.launch {
            val outcome = flow.load(userId, tab, null)
            updateTab(tab) { it.withFirstPage(outcome) }
        }
    }

    /** Pull-to-refresh of an already-loaded tab: reloads the first page WITHOUT flipping to the skeleton
     *  (drives `isRefreshing`, not `isInitialLoad`). On success the rows + cursor are replaced; on failure
     *  the prior rows are retained (the refresh simply ends). */
    fun onRefresh(tab: FollowListTab) {
        val t = tabState(tab)
        // Guard `isInitialLoad` too (mirrors `NearbyTimelineViewModel.onRefresh`): a pull-to-refresh
        // fired from the still-loading skeleton would otherwise launch a second concurrent first-page
        // fetch whose `withRefresh` clears `isRefreshing` but NOT `isInitialLoad`, flashing the skeleton
        // over already-loaded rows until the original first-page load resolves.
        if (t.isRefreshing || t.isInitialLoad) return
        updateTab(tab) { it.copy(isRefreshing = true, loadMoreFailed = false) }
        viewModelScope.launch {
            val outcome = flow.load(userId, tab, null)
            updateTab(tab) { it.withRefresh(outcome) }
        }
    }

    /** Scroll-to-end load-more: appends the next keyset page. Guarded — no fetch when no cursor remains,
     *  when one is already in flight, when a prior load-more failed (retry via [onRetryLoadMore]), or while
     *  the initial page is still loading. */
    fun onLoadMore(tab: FollowListTab) {
        val t = tabState(tab)
        val cursor = t.nextCursor ?: return
        if (t.isLoadingMore || t.loadMoreFailed || t.isInitialLoad) return
        updateTab(tab) { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val outcome = flow.load(userId, tab, cursor)
            updateTab(tab) { it.withLoadMore(outcome) }
        }
    }

    /** Retry a failed load-more (the footer tap): clears the failed flag and re-issues the page fetch. */
    fun onRetryLoadMore(tab: FollowListTab) {
        if (!tabState(tab).loadMoreFailed) return
        updateTab(tab) { it.copy(loadMoreFailed = false) }
        onLoadMore(tab)
    }

    private fun tabState(tab: FollowListTab): TabState =
        when (tab) {
            FollowListTab.Followers -> state.value.followers
            FollowListTab.Following -> state.value.following
        }

    private inline fun updateTab(
        tab: FollowListTab,
        crossinline transform: (TabState) -> TabState,
    ) {
        state.update { s ->
            when (tab) {
                FollowListTab.Followers -> s.copy(followers = transform(s.followers))
                FollowListTab.Following -> s.copy(following = transform(s.following))
            }
        }
    }

    private fun TabState.withFirstPage(outcome: FollowListOutcome): TabState =
        when (outcome) {
            is FollowListOutcome.Loaded ->
                copy(
                    rows = outcome.page.users,
                    nextCursor = outcome.page.nextCursor,
                    isInitialLoad = false,
                    latestOutcome = outcome,
                    loadMoreFailed = false,
                )
            FollowListOutcome.NotFound, FollowListOutcome.NetworkError ->
                copy(
                    rows = emptyList(),
                    nextCursor = null,
                    isInitialLoad = false,
                    latestOutcome = outcome,
                    loadMoreFailed = false,
                )
        }

    private fun TabState.withRefresh(outcome: FollowListOutcome): TabState =
        when (outcome) {
            is FollowListOutcome.Loaded ->
                copy(
                    rows = outcome.page.users,
                    nextCursor = outcome.page.nextCursor,
                    isRefreshing = false,
                    latestOutcome = outcome,
                    loadMoreFailed = false,
                )
            // Refresh failed: when rows are already present, keep them (never flip a loaded tab to the
            // skeleton/error — just end the refresh). When the tab had no rows, update latestOutcome so the
            // Empty/Error/NotFound projection stays correct.
            FollowListOutcome.NotFound, FollowListOutcome.NetworkError ->
                if (rows.isNotEmpty()) copy(isRefreshing = false) else copy(isRefreshing = false, latestOutcome = outcome)
        }

    private fun TabState.withLoadMore(outcome: FollowListOutcome): TabState =
        when (outcome) {
            is FollowListOutcome.Loaded ->
                copy(
                    rows = rows + outcome.page.users,
                    nextCursor = outcome.page.nextCursor,
                    isLoadingMore = false,
                    loadMoreFailed = false,
                )
            // Load-more failed WITH prior rows → retain the rows, surface the retry footer (never discard
            // to a full-screen error).
            FollowListOutcome.NotFound, FollowListOutcome.NetworkError ->
                copy(isLoadingMore = false, loadMoreFailed = true)
        }

    private fun VmState.toUiState(): FollowListUiState =
        FollowListUiState(
            followers = followers.project(),
            following = following.project(),
            followersRefreshing = followers.isRefreshing,
            followingRefreshing = following.isRefreshing,
        )

    private fun TabState.project(): FollowListTabUiState =
        followListTabUiState(
            isInitialLoad = isInitialLoad,
            rows = rows,
            nextCursor = nextCursor,
            latestOutcome = latestOutcome,
            loadMoreFailed = loadMoreFailed,
        )
}
