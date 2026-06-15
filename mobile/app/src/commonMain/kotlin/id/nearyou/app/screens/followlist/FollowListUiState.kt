package id.nearyou.app.screens.followlist

import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.followlist.FollowListUser

/**
 * One tab's rendered state — mutually exclusive (mirrors `NearbyTimelineUiState`). The PII-free
 * [FollowListUser] rows carry only display fields (the row `userId` is kept off this model — it lives only
 * as the row's navigation key in the VM):
 *  - [Loading] — initial load, no rows yet → the skeleton (the pull-to-refresh spinner is NOT shown).
 *  - [Content] — non-empty rows. [canLoadMore] (a non-null keyset cursor remains) gates the scroll-to-end
 *    load-more; [loadMoreFailed] flags a load-more `NetworkError` that occurred WITH prior rows → a
 *    non-blocking, retryable footer (the rows are RETAINED — never discarded to a full-screen error).
 *  - [Empty] — `Loaded` with zero rows → the per-tab empty copy.
 *  - [NotFound] — the constant `404 user_not_found` (the profile target is gone) → the not-found copy.
 *  - [Error] — an initial-load `NetworkError` with no rows → the full-screen network copy + a retry.
 */
sealed interface FollowListTabUiState {
    data object Loading : FollowListTabUiState

    data class Content(
        val users: List<FollowListUser>,
        val canLoadMore: Boolean,
        val loadMoreFailed: Boolean,
    ) : FollowListTabUiState

    data object Empty : FollowListTabUiState

    data object NotFound : FollowListTabUiState

    data object Error : FollowListTabUiState
}

/**
 * Pure, Compose-free projection of ONE tab's state — deterministic (no wall-clock / platform dependency),
 * carrying no PII beyond the display fields the [FollowListUser] rows already hold. Exhaustive over
 * [FollowListOutcome] (+ the not-yet-loaded `null`) — no generic fallthrough:
 *  - [isInitialLoad] (no rows yet) ⇒ [FollowListTabUiState.Loading].
 *  - any accumulated [rows] ⇒ [FollowListTabUiState.Content] (load-more gated on a non-null [nextCursor];
 *    [loadMoreFailed] surfaced as the footer flag) — a `NetworkError`/`NotFound` that arrives WHILE rows are
 *    present keeps the rows (the load-more-failure-retains-rows contract).
 *  - no rows + `Loaded` ⇒ [FollowListTabUiState.Empty]; + `NotFound` ⇒ [FollowListTabUiState.NotFound];
 *    + `NetworkError` ⇒ [FollowListTabUiState.Error]; + `null` ⇒ [FollowListTabUiState.Loading].
 */
fun followListTabUiState(
    isInitialLoad: Boolean,
    rows: List<FollowListUser>,
    nextCursor: String?,
    latestOutcome: FollowListOutcome?,
    loadMoreFailed: Boolean,
): FollowListTabUiState {
    if (isInitialLoad) return FollowListTabUiState.Loading
    if (rows.isNotEmpty()) {
        return FollowListTabUiState.Content(
            users = rows,
            canLoadMore = nextCursor != null,
            loadMoreFailed = loadMoreFailed,
        )
    }
    return when (latestOutcome) {
        is FollowListOutcome.Loaded -> FollowListTabUiState.Empty
        FollowListOutcome.NotFound -> FollowListTabUiState.NotFound
        FollowListOutcome.NetworkError -> FollowListTabUiState.Error
        null -> FollowListTabUiState.Loading
    }
}

/**
 * The whole screen's rendered state — the two tabs' [FollowListTabUiState]s plus the per-tab refresh flag
 * that feeds each tab's `PullToRefreshBox(isRefreshing = …)`. A refresh keeps the tab's prior projection
 * (it never flips back to [FollowListTabUiState.Loading]) — the separate refresh flag is exactly the
 * `isInitialLoad`-vs-`isRefreshing` separation the canonical list pattern requires.
 */
data class FollowListUiState(
    val followers: FollowListTabUiState,
    val following: FollowListTabUiState,
    val followersRefreshing: Boolean,
    val followingRefreshing: Boolean,
) {
    fun tab(tab: FollowListTab): FollowListTabUiState =
        when (tab) {
            FollowListTab.Followers -> followers
            FollowListTab.Following -> following
        }

    fun isRefreshing(tab: FollowListTab): Boolean =
        when (tab) {
            FollowListTab.Followers -> followersRefreshing
            FollowListTab.Following -> followingRefreshing
        }
}
