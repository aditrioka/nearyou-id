package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersOutcome
import id.nearyou.app.data.block.UnblockOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `BlockedUsersRoute`-NavEntry-scoped ViewModel owning the block-list load state + the unblock action.
 * Resolved via `viewModel { … }` inside `BlockedUsersScreen` (the established mobile state-holder
 * Pattern Registry entry, docs/11 § 2.2). The first page loads once in [init]; [reload] re-fetches
 * (pull-to-refresh + error-retry).
 *
 * **Unblock is NON-optimistic** (`mobile-settings` § "Block-list management"): the row stays put until
 * the `DELETE` succeeds, then it is removed. A `RetryableError` keeps the row and raises a one-shot
 * [unblockError] (the screen shows a non-trapping snackbar) — the list NEVER optimistically drops a row
 * whose unblock did not succeed. A `401` on either call surfaces [BlockedUsersOutcome.TokenInvalid] so
 * the screen routes to sign-in.
 */
class BlockedUsersViewModel(
    private val flow: BlockedUsersFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<BlockedUsersOutcome?>(null)
    val outcome: StateFlow<BlockedUsersOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** A one-shot "unblock failed, row kept" signal — the screen renders a snackbar then [consumeUnblockError]. */
    private val _unblockError = MutableStateFlow(false)
    val unblockError: StateFlow<Boolean> = _unblockError.asStateFlow()

    /** userIds with an unblock `DELETE` in flight — the row's affordance disables + a re-tap is a no-op. */
    private val _unblocking = MutableStateFlow<Set<String>>(emptySet())
    val unblocking: StateFlow<Set<String>> = _unblocking.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        if (_isRefreshing.value || _isInitialLoad.value) return
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
                _isInitialLoad.value = false
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
