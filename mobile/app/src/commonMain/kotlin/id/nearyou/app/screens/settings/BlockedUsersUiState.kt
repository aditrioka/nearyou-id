package id.nearyou.app.screens.settings

import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersOutcome

/**
 * Pure, Compose-free projection of the blocked-users screen state, mirroring `notificationsUiState`.
 * Exhaustive over [BlockedUsersOutcome] — no generic fallthrough. The empty list is its own [Empty]
 * state (the directive "Belum ada pengguna yang diblokir" copy), distinct from [Content].
 */
sealed interface BlockedUsersUiState {
    /** First load (skeleton) — before the first outcome arrives. */
    data object Loading : BlockedUsersUiState

    /** `200` with no blocked users — the empty-state copy. */
    data object Empty : BlockedUsersUiState

    /** `5xx` / IO / `400` — retryable error (reuses `signin_error_network` + `cta_retry`). */
    data object Error : BlockedUsersUiState

    /** Terminal `401` — a neutral redirect placeholder while the sign-in re-route is in flight. */
    data object SessionRedirect : BlockedUsersUiState

    /** `200` with ≥1 blocked user. */
    data class Content(val rows: List<BlockedUser>) : BlockedUsersUiState
}

/**
 * Maps the load [outcome] (null = not yet loaded) + the [isInitialLoad] flag to the screen state.
 * While the first load is in flight the screen shows [Loading]; afterwards it reflects the outcome.
 */
fun blockedUsersUiState(
    outcome: BlockedUsersOutcome?,
    isInitialLoad: Boolean,
): BlockedUsersUiState {
    if (isInitialLoad) return BlockedUsersUiState.Loading
    return when (outcome) {
        null -> BlockedUsersUiState.Loading
        is BlockedUsersOutcome.Loaded ->
            if (outcome.blocks.isEmpty()) BlockedUsersUiState.Empty else BlockedUsersUiState.Content(outcome.blocks)
        BlockedUsersOutcome.TokenInvalid -> BlockedUsersUiState.SessionRedirect
        BlockedUsersOutcome.RetryableError -> BlockedUsersUiState.Error
    }
}
