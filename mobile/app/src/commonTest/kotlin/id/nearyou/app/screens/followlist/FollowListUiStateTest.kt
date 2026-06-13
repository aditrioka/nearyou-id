package id.nearyou.app.screens.followlist

import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListPage
import id.nearyou.app.followlist.FollowListUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FollowListUiStateTest {
    private val rows = listOf(FollowListUser("u1", "raka.jkt", "Raka", isPremium = false))

    @Test
    fun `initial load maps to Loading even when rows are already present`() {
        assertEquals(
            FollowListTabUiState.Loading,
            followListTabUiState(isInitialLoad = true, rows = emptyList(), nextCursor = null, latestOutcome = null, loadMoreFailed = false),
        )
        assertEquals(
            FollowListTabUiState.Loading,
            followListTabUiState(isInitialLoad = true, rows = rows, nextCursor = "c", latestOutcome = null, loadMoreFailed = false),
        )
    }

    @Test
    fun `loaded with rows is Content with cursor-gated load-more`() {
        val withCursor =
            assertIs<FollowListTabUiState.Content>(
                followListTabUiState(false, rows, "c", FollowListOutcome.Loaded(FollowListPage(rows, "c")), loadMoreFailed = false),
            )
        assertTrue(withCursor.canLoadMore)
        assertEquals(rows, withCursor.users)
        val noCursor =
            assertIs<FollowListTabUiState.Content>(
                followListTabUiState(false, rows, null, FollowListOutcome.Loaded(FollowListPage(rows, null)), loadMoreFailed = false),
            )
        assertFalse(noCursor.canLoadMore)
    }

    @Test
    fun `load-more failure retains the rows and raises the footer flag`() {
        val content =
            assertIs<FollowListTabUiState.Content>(
                followListTabUiState(false, rows, "c", FollowListOutcome.Loaded(FollowListPage(rows, "c")), loadMoreFailed = true),
            )
        assertEquals(rows, content.users)
        assertTrue(content.loadMoreFailed)
    }

    @Test
    fun `empty loaded maps to Empty`() {
        assertEquals(
            FollowListTabUiState.Empty,
            followListTabUiState(false, emptyList(), null, FollowListOutcome.Loaded(FollowListPage(emptyList(), null)), false),
        )
    }

    @Test
    fun `constant 404 maps to NotFound`() {
        assertEquals(FollowListTabUiState.NotFound, followListTabUiState(false, emptyList(), null, FollowListOutcome.NotFound, false))
    }

    @Test
    fun `network error with no rows maps to Error`() {
        assertEquals(FollowListTabUiState.Error, followListTabUiState(false, emptyList(), null, FollowListOutcome.NetworkError, false))
    }

    @Test
    fun `no rows and a null outcome falls back to Loading`() {
        assertEquals(FollowListTabUiState.Loading, followListTabUiState(false, emptyList(), null, null, false))
    }
}
