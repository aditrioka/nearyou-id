package id.nearyou.app.screens.followlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.followlist.FollowListFlow
import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.followlist.FollowListUser
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.profile.ProfileMessage
import id.nearyou.app.screens.profile.settle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * **Inline follow toggle (`mobile-follow-list-inline-follow`).** [onToggleFollow] reuses the profile toggle
 * through [profileFlow] + the shared `settle` mapping: the user's `followedByViewer` is patched in place on
 * EVERY loaded row of that user in BOTH tabs (D4 — a mutual follow appears in both), optimistically, then to
 * the settled value; a 429 / constant 404 surface [FollowListUiState.message] (cleared by [onMessageShown]).
 * The write runs under [NonCancellable] (docs/11 § 2.2). [onRowOpened] + [onResumed] re-read the one user
 * whose profile the row opened (D6 — docs/11 § 2.3 silent `ON_RESUME` re-read).
 */
class FollowListViewModel(
    private val flow: FollowListFlow,
    private val profileFlow: ProfileFlow,
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
        /** Row userId → the optimistic `followedByViewer` of a toggle whose write is in flight. */
        val followInFlight: Map<String, Boolean> = emptyMap(),
        val message: ProfileMessage? = null,
    )

    private val state = MutableStateFlow(VmState())

    /** The row user whose profile this list opened — re-read once on the next [onResumed] (D6). */
    private var lastOpenedUserId: String? = null

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

    /** Inline follow / unfollow of [rowUserId] (non-self rows only): optimistic patch of every loaded row of
     *  that user in both tabs, then settle via the shared mapping. A second tap while in flight is a no-op. */
    fun onToggleFollow(rowUserId: String) {
        val current = state.value
        if (rowUserId in current.followInFlight) return
        val row = current.rowFor(rowUserId) ?: return
        if (row.isSelf) return
        val previouslyFollowed = row.followedByViewer
        val wantFollow = !previouslyFollowed
        // The in-flight claim + optimistic patch land synchronously, before the launch (docs/11 § 2.2).
        state.update { it.copy(followInFlight = it.followInFlight + (rowUserId to wantFollow)).withFollowed(rowUserId, wantFollow) }
        viewModelScope.launch {
            val outcome =
                withContext(NonCancellable) {
                    if (wantFollow) profileFlow.follow(rowUserId) else profileFlow.unfollow(rowUserId)
                }
            val settled = outcome.settle(previouslyFollowed = previouslyFollowed)
            state.update {
                it
                    .copy(followInFlight = it.followInFlight - rowUserId, message = settled.message ?: it.message)
                    .withFollowed(rowUserId, settled.followed)
            }
        }
    }

    /** The one-shot toggle message was shown (the snackbar) — clear it. */
    fun onMessageShown() {
        state.update { it.copy(message = null) }
    }

    /** A row was tapped (its profile is about to open) — remember it for the return re-read. */
    fun onRowOpened(rowUserId: String) {
        lastOpenedUserId = rowUserId
    }

    /** The screen resumed (`ON_RESUME`): if a row's profile was opened, silently re-read that one user — the
     *  profile may have changed its follow state or blocked it. `Loaded` patches its rows; the constant-404
     *  `NotFound` drops them from both tabs (what a refresh would return); `NetworkError` changes nothing. */
    fun onResumed() {
        val opened = lastOpenedUserId ?: return
        if (opened in state.value.followInFlight) return
        lastOpenedUserId = null
        viewModelScope.launch {
            when (val outcome = profileFlow.loadProfile(opened)) {
                is ProfileOutcome.Loaded ->
                    state.update { s ->
                        if (opened in s.followInFlight) s else s.withFollowed(opened, outcome.profile.followedByViewer)
                    }
                ProfileOutcome.NotFound -> state.update { it.withoutUser(opened) }
                ProfileOutcome.NetworkError -> Unit
            }
        }
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
            // Rows arriving while a toggle is in flight keep its optimistic value (no flicker back, D4).
            val patched = { t: TabState -> transform(t).let { it.copy(rows = it.rows.withFollowed(s.followInFlight)) } }
            when (tab) {
                FollowListTab.Followers -> s.copy(followers = patched(s.followers))
                FollowListTab.Following -> s.copy(following = patched(s.following))
            }
        }
    }

    private fun VmState.rowFor(rowUserId: String): FollowListUser? =
        followers.rows.firstOrNull { it.userId == rowUserId } ?: following.rows.firstOrNull { it.userId == rowUserId }

    /** Patch `followedByViewer` on every loaded row of [rowUserId] in both tabs. */
    private fun VmState.withFollowed(
        rowUserId: String,
        followed: Boolean,
    ): VmState {
        val patch = mapOf(rowUserId to followed)
        return copy(
            followers = followers.copy(rows = followers.rows.withFollowed(patch)),
            following = following.copy(rows = following.rows.withFollowed(patch)),
        )
    }

    private fun List<FollowListUser>.withFollowed(patch: Map<String, Boolean>): List<FollowListUser> =
        if (patch.isEmpty()) {
            this
        } else {
            map { row -> patch[row.userId]?.let { row.copy(followedByViewer = it) } ?: row }
        }

    /** Drop every row of [rowUserId] from both tabs (a tab left empty projects to its empty state). */
    private fun VmState.withoutUser(rowUserId: String): VmState =
        copy(
            followers = followers.copy(rows = followers.rows.filterNot { it.userId == rowUserId }),
            following = following.copy(rows = following.rows.filterNot { it.userId == rowUserId }),
        )

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
            followInFlight = followInFlight.keys,
            message = message,
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
