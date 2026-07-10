package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersOutcome
import id.nearyou.app.data.block.UnblockOutcome
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
 * `BlockedUsersRoute`-NavEntry-scoped ViewModel owning the block-list load state + the unblock action.
 * Resolved via `viewModel { … }` inside `BlockedUsersScreen` (the established mobile state-holder
 * Pattern Registry entry, docs/11 § 2.2). The first page loads once in [init]; [reload] re-fetches
 * (pull-to-refresh + error-retry).
 *
 * The single screen state is exposed as ONE [uiState] `StateFlow` (docs/11 §2.2) — the pure
 * `blockedUsersUiState(outcome, isInitialLoad)` projection folded into the ViewModel via `stateIn`, so it
 * is owned here, not recomputed in the composable. The initial-load flag is INTERNAL ([initialLoad],
 * private), reflected through [uiState] (`Loading` until the first outcome arrives), NOT a separate public
 * flow; [isRefreshing] / [unblockError] / [unblocking] stay SEPARATE flows. The raw [outcome] stays
 * exposed as the domain-state seam: [unblock] reads/writes it (non-optimistic row removal) and routes a
 * terminal `401` through it, and the white-box tests assert on it.
 *
 * **Unblock is NON-optimistic** (`mobile-settings` § "Block-list management"): the row stays put until
 * the `DELETE` succeeds, then it is removed. A `RetryableError` keeps the row and raises a one-shot
 * [unblockError] (the screen shows a non-trapping snackbar) — the list NEVER optimistically drops a row
 * whose unblock did not succeed. A `401` on either call surfaces [BlockedUsersOutcome.TokenInvalid] so
 * the screen routes to sign-in.
 *
 * Cursor load-more (infinite scroll) appends pages into the same retained `Loaded` outcome via the shared
 * [LoadMoreController] (`mobile-settings` § "block-list data seam": `nextCursor` threaded on scroll-end),
 * gated so it never runs during the initial load or a refresh.
 */
class BlockedUsersViewModel(
    private val flow: BlockedUsersFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<BlockedUsersOutcome?>(null)
    val outcome: StateFlow<BlockedUsersOutcome?> = _outcome.asStateFlow()

    private val initialLoad = MutableStateFlow(true)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The single screen state (docs/11 §2.2): the pure [blockedUsersUiState] projection folded into the
     * ViewModel via [stateIn] — computed once per outcome/initial-load change, NOT re-derived in the
     * composable. `Loading` until the first outcome arrives; the refresh indicator is the separate
     * [isRefreshing] flag, never folded in here.
     */
    val uiState: StateFlow<BlockedUsersUiState> =
        combine(_outcome, initialLoad) { outcome, isInitialLoad ->
            blockedUsersUiState(outcome, isInitialLoad)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUsersUiState.Loading)

    // The block-list instance of the ONE shared cursor load-more lifecycle (ui/timeline/LoadMoreController,
    // mobile-settings § "block-list data seam": `nextCursor` threaded for load-more). Appends pages into
    // the retained Loaded outcome — so unblock's non-optimistic row removal operates over the GROWN list.
    // Eligibility-gated so load-more never runs during the initial load or a refresh; a load-more page
    // that is not Loaded (401 / retryable) maps to Failure → the non-destructive retry footer.
    private val loadMoreController =
        LoadMoreController<BlockedUser>(
            scope = viewModelScope,
            currentCursor = { (_outcome.value as? BlockedUsersOutcome.Loaded)?.nextCursor },
            canLoadMore = { !initialLoad.value && !_isRefreshing.value },
            fetchPage = { cursor ->
                when (val outcome = flow.fetchBlocks(cursor)) {
                    is BlockedUsersOutcome.Loaded -> LoadMorePage.Success(outcome.blocks, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                val current = _outcome.value
                if (current is BlockedUsersOutcome.Loaded) {
                    _outcome.value = current.copy(blocks = current.blocks + items, nextCursor = next)
                }
            },
        )

    /** True while a load-more page is in flight — drives only the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = loadMoreController.isLoadingMore

    /** True after a failed load-more — drives the non-destructive retry footer (loaded list retained). */
    val loadMoreError: StateFlow<Boolean> = loadMoreController.loadMoreError

    /** A one-shot "unblock failed, row kept" signal — the screen renders a snackbar then [consumeUnblockError]. */
    private val _unblockError = MutableStateFlow(false)
    val unblockError: StateFlow<Boolean> = _unblockError.asStateFlow()

    /** userIds with an unblock `DELETE` in flight — the row's affordance disables + a re-tap is a no-op. */
    private val _unblocking = MutableStateFlow<Set<String>>(emptySet())
    val unblocking: StateFlow<Set<String>> = _unblocking.asStateFlow()

    init {
        load(initial = true)
    }

    /** Scroll-end trigger from the screen — appends the next page (no-op during initial/refresh or at end). */
    fun onLoadMore() = loadMoreController.loadMore()

    /** Retry control on the load-more error footer — re-issues for the still-current cursor. */
    fun onRetryLoadMore() = loadMoreController.retry()

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        if (_isRefreshing.value || initialLoad.value) return
        // Refresh resets paging: load() swaps in a fresh first page (dropping the appended tail) and the
        // footer state is cleared here (mobile-design-system § load-more pattern).
        loadMoreController.reset()
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.fetchBlocks()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _outcome.value = BlockedUsersOutcome.RetryableError
            } finally {
                initialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Issues `DELETE /api/v1/blocks/{userId}`. On success the row is removed; on a retryable failure the
     * row stays and [unblockError] flips (non-trapping snackbar); on `401` the token-invalid outcome
     * routes to sign-in. A concurrent unblock of the same row is a no-op (the in-flight guard).
     */
    fun unblock(userId: String) {
        if (userId in _unblocking.value) return
        _unblocking.value = _unblocking.value + userId
        viewModelScope.launch {
            val result =
                try {
                    flow.unblock(userId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    UnblockOutcome.RetryableError
                }
            when (result) {
                UnblockOutcome.Success -> removeRow(userId)
                UnblockOutcome.RetryableError -> _unblockError.value = true // row stays
                UnblockOutcome.TokenInvalid -> _outcome.value = BlockedUsersOutcome.TokenInvalid
            }
            _unblocking.value = _unblocking.value - userId
        }
    }

    fun consumeUnblockError() {
        _unblockError.value = false
    }

    private fun removeRow(userId: String) {
        val current = _outcome.value as? BlockedUsersOutcome.Loaded ?: return
        _outcome.value = current.copy(blocks = current.blocks.filterNot { it.userId == userId })
    }
}
