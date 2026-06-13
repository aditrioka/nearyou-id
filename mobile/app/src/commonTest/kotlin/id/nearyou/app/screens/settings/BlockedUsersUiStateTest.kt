package id.nearyou.app.screens.settings

import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-projection coverage of [blockedUsersUiState] — every outcome maps to exactly one state. */
class BlockedUsersUiStateTest {
    private val sampleUser = BlockedUser(userId = "u-1", username = "raka.jkt", displayName = "Raka", isPremium = false)

    @Test
    fun `initial load shows Loading regardless of outcome`() {
        assertEquals(BlockedUsersUiState.Loading, blockedUsersUiState(outcome = null, isInitialLoad = true))
    }

    @Test
    fun `loaded with rows maps to Content`() {
        val state = blockedUsersUiState(BlockedUsersOutcome.Loaded(listOf(sampleUser), nextCursor = null), isInitialLoad = false)
        assertTrue(state is BlockedUsersUiState.Content)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun `loaded with no rows maps to Empty`() {
        assertEquals(
            BlockedUsersUiState.Empty,
            blockedUsersUiState(BlockedUsersOutcome.Loaded(emptyList(), nextCursor = null), isInitialLoad = false),
        )
    }

    @Test
    fun `retryable error maps to Error`() {
        assertEquals(BlockedUsersUiState.Error, blockedUsersUiState(BlockedUsersOutcome.RetryableError, isInitialLoad = false))
    }

    @Test
    fun `token invalid maps to SessionRedirect`() {
        assertEquals(
            BlockedUsersUiState.SessionRedirect,
            blockedUsersUiState(BlockedUsersOutcome.TokenInvalid, isInitialLoad = false),
        )
    }

    @Test
    fun `null outcome after initial load maps to Loading`() {
        assertEquals(BlockedUsersUiState.Loading, blockedUsersUiState(outcome = null, isInitialLoad = false))
    }
}
