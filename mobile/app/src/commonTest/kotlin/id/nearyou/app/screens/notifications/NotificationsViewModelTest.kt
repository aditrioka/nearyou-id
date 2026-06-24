package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.MarkAllReadResult
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [NotificationsViewModel] — the shell-`NavEntry`-scoped holder for the notifications
 * feed's load state + the optimistic read mutations. Pins: the first page loads exactly once on
 * construction, [NotificationsViewModel.reload] re-fetches, a load failure maps to the existing retryable
 * [NotificationsOutcome.NetworkError], and the optimistic mark-read / mark-all-read keep on `204`/`404` /
 * revert on failure. Mirrors `GlobalTimelineViewModelTest`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main so
 * the init/reload/mutation coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun NotificationsViewModel.readAtOf(id: String): String? {
        val loaded = outcome.value as? NotificationsOutcome.Loaded ?: return null
        return loaded.items.first { it.id == id }.readAt
    }

    @Test
    fun init_loadsFirstPageExactlyOnce_andExposesTheOutcome() {
        val fake = FakeNotificationsFlow(NotificationsOutcome.Loaded(listOf(fakeNotification()), null))
        val viewModel = NotificationsViewModel(fake)
        assertEquals(1, fake.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(viewModel.outcome.value is NotificationsOutcome.Loaded, "the loaded outcome is exposed")
        assertFalse(viewModel.isRefreshing.value, "isRefreshing stays false for the initial load")
    }

    @Test
    fun reload_reFetchesPageOne() {
        val fake = FakeNotificationsFlow(NotificationsOutcome.Loaded(emptyList(), null))
        val viewModel = NotificationsViewModel(fake)
        assertEquals(1, fake.loadInvocationCount)
        viewModel.reload()
        assertEquals(2, fake.loadInvocationCount, "reload re-fetches page 1 (pull-to-refresh / error retry)")
    }

    @Test
    fun loadFailure_mapsToExistingNetworkError() {
        val fake = FakeNotificationsFlow(failWith = IllegalStateException("fetch threw"))
        val viewModel = NotificationsViewModel(fake)
        viewModel.activateUiState()
        assertEquals(
            NotificationsOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
        )
        assertTrue(viewModel.uiState.value is NotificationsUiState.Error, "and uiState projects to Error")
    }

    @Test
    fun uiState_delegatesToTheProjection() {
        val fake = FakeNotificationsFlow(NotificationsOutcome.Loaded(listOf(fakeNotification()), null))
        val viewModel = NotificationsViewModel(fake)
        viewModel.activateUiState()
        // The single uiState equals the pure projection for the held outcome (reused, not reimplemented);
        // after the first outcome arrives the initial-load flag is false.
        assertEquals(
            notificationsUiState(viewModel.outcome.value, isInitialLoad = false),
            viewModel.uiState.value,
            "uiState delegates to notificationsUiState(outcome, isInitialLoad)",
        )
        assertTrue(viewModel.uiState.value is NotificationsUiState.Content, "a loaded non-empty outcome projects to Content")
    }

    @Test
    fun uiState_retainsContentAcrossAFreshCollector() {
        // The config-change proxy: the entry-scoped VM retains the resolved outcome, so a FRESH uiState
        // collector (the recomposed screen) still sees Content — not a reset to Loading.
        val fake = FakeNotificationsFlow(NotificationsOutcome.Loaded(listOf(fakeNotification()), null))
        val viewModel = NotificationsViewModel(fake)
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is NotificationsUiState.Content, "loaded → Content")
        viewModel.activateUiState() // a second, fresh collector (the config-change case)
        assertTrue(
            viewModel.uiState.value is NotificationsUiState.Content,
            "a fresh collector still sees the retained Content (not reset to Loading)",
        )
    }

    @Test
    fun refresh_keepsContent_andTogglesOnlyIsRefreshing() {
        // suspendFromCall = 2 → the reload's loadFirstPage suspends, so we observe the in-flight refresh:
        // isRefreshing = true, uiState stays Content (NOT Loading) — the prior outcome is retained.
        val fake = FakeNotificationsFlow(NotificationsOutcome.Loaded(listOf(fakeNotification()), null), suspendFromCall = 2)
        val viewModel = NotificationsViewModel(fake)
        viewModel.activateUiState()
        assertTrue(viewModel.uiState.value is NotificationsUiState.Content, "after the first load uiState is Content")
        assertFalse(viewModel.isRefreshing.value, "not refreshing before reload")

        viewModel.reload()

        assertTrue(viewModel.isRefreshing.value, "a reload-in-flight sets isRefreshing")
        assertTrue(
            viewModel.uiState.value is NotificationsUiState.Content,
            "uiState stays Content during the refresh (does NOT revert to Loading)",
        )
    }

    @Test
    fun markRead_optimisticFlip_keptOn204() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
                markReadResult = MarkReadResult.Acknowledged,
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markRead("n1")
        assertEquals(listOf("n1"), fake.markReadIds, "a mark-read PATCH is issued for the tapped row")
        assertNotNull(viewModel.readAtOf("n1"), "204 keeps the optimistic read flip")
    }

    @Test
    fun markRead_keptOn404() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
                markReadResult = MarkReadResult.NotFound,
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markRead("n1")
        assertNotNull(viewModel.readAtOf("n1"), "404 is a no-op — the row stays read (idempotent-looking)")
    }

    @Test
    fun markRead_revertsOnExplicitFailure() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
                markReadResult = MarkReadResult.Failed,
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markRead("n1")
        assertNull(viewModel.readAtOf("n1"), "a transport failure reverts the optimistic flip to unread")
    }

    @Test
    fun markRead_revertsOnTransportThrow() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
                markReadThrows = IllegalStateException("io"),
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markRead("n1")
        assertNull(viewModel.readAtOf("n1"), "a thrown transport failure reverts the optimistic flip")
    }

    @Test
    fun markRead_alreadyReadRow_issuesNoPatch() {
        val fake =
            FakeNotificationsFlow(
                outcome =
                    NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = "2026-05-31T11:00:00Z")), null),
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markRead("n1")
        assertTrue(fake.markReadIds.isEmpty(), "an already-read row is a no-op (no PATCH issued)")
    }

    @Test
    fun markAllRead_flipsAllLoadedRows() {
        val fake =
            FakeNotificationsFlow(
                outcome =
                    NotificationsOutcome.Loaded(
                        listOf(fakeNotification(id = "n1", readAt = null), fakeNotification(id = "n2", readAt = null)),
                        null,
                    ),
                markAllReadResult = MarkAllReadResult.Success(2),
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markAllRead()
        assertEquals(1, fake.markAllReadInvocationCount)
        assertNotNull(viewModel.readAtOf("n1"))
        assertNotNull(viewModel.readAtOf("n2"))
    }

    @Test
    fun markAllRead_revertsOnFailure() {
        val fake =
            FakeNotificationsFlow(
                outcome =
                    NotificationsOutcome.Loaded(
                        listOf(fakeNotification(id = "n1", readAt = null), fakeNotification(id = "n2", readAt = null)),
                        null,
                    ),
                markAllReadResult = MarkAllReadResult.Failed,
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.markAllRead()
        assertNull(viewModel.readAtOf("n1"), "a failed read-all reverts every optimistic flip")
        assertNull(viewModel.readAtOf("n2"))
    }

    // ---- mobile-nearby-timeline-infinite-scroll (extended to notifications): cursor load-more ----

    private fun NotificationsViewModel.loadedIds(): List<String> = (outcome.value as NotificationsOutcome.Loaded).items.map { it.id }

    @Test
    fun onLoadMore_appendsBelowPage1_andAdvancesCursor() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1")), "c1"),
                loadMorePages = listOf(NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n2")), "c2")),
            )
        val viewModel = NotificationsViewModel(fake)

        viewModel.onLoadMore()

        val loaded = viewModel.outcome.value as NotificationsOutcome.Loaded
        assertEquals(listOf("n1", "n2"), loaded.items.map { it.id }, "page 2 appends below page 1")
        assertEquals("c2", loaded.nextCursor, "the cursor advances to the new page's cursor")
        assertEquals(listOf("c1"), fake.loadMoreCalls, "load-more fetches the retained cursor c1")
    }

    @Test
    fun markRead_flipsARowFromAnAppendedPage() {
        // n2 arrives only via load-more — marking it read must flip it in the GROWN list.
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), "c1"),
                loadMorePages =
                    listOf(NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n2", readAt = null)), null)),
                markReadResult = MarkReadResult.Acknowledged,
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.onLoadMore()
        assertEquals(listOf("n1", "n2"), viewModel.loadedIds(), "the appended row is present before mark-read")

        viewModel.markRead("n2")

        assertEquals(listOf("n2"), fake.markReadIds, "a mark-read PATCH is issued for the APPENDED row")
        assertNotNull(viewModel.readAtOf("n2"), "the appended row flips to read in the grown list")
        assertNull(viewModel.readAtOf("n1"), "the page-1 row is untouched")
    }

    @Test
    fun markAllRead_flipsAllLoadedRowsIncludingAppended() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), "c1"),
                loadMorePages =
                    listOf(NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n2", readAt = null)), null)),
                markAllReadResult = MarkAllReadResult.Success(2),
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.onLoadMore()

        viewModel.markAllRead()

        assertEquals(1, fake.markAllReadInvocationCount)
        assertNotNull(viewModel.readAtOf("n1"), "the page-1 row is flipped read")
        assertNotNull(viewModel.readAtOf("n2"), "the APPENDED row is flipped read too (mark-all spans the grown list)")
    }

    @Test
    fun onLoadMore_whenEndReached_isNoOp() {
        // First page already end-reached (null cursor) → load-more must not fire.
        val fake = FakeNotificationsFlow(outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1")), null))
        val viewModel = NotificationsViewModel(fake)

        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "no load-more request when the cursor is null (end-reached)")
    }

    @Test
    fun onLoadMore_failure_raisesErrorFooter_andKeepsLoadedItems() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1")), "c1"),
                loadMorePages = listOf(NotificationsOutcome.NetworkError),
            )
        val viewModel = NotificationsViewModel(fake)

        viewModel.onLoadMore()

        assertTrue(viewModel.loadMoreError.value, "a failed load-more raises the non-destructive error footer")
        assertEquals(listOf("n1"), viewModel.loadedIds(), "the loaded items are retained on load-more failure")
    }

    @Test
    fun reload_clearsTheLoadMoreErrorFooter() {
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1")), "c1"),
                loadMorePages = listOf(NotificationsOutcome.NetworkError),
            )
        val viewModel = NotificationsViewModel(fake)
        viewModel.onLoadMore()
        assertTrue(viewModel.loadMoreError.value)

        viewModel.reload()

        assertFalse(viewModel.loadMoreError.value, "a refresh resets paging — the load-more footer state clears")
    }

    @Test
    fun onLoadMore_isSuppressedWhileARefreshIsInFlight() {
        // suspendFromCall = 2 → the reload's loadFirstPage suspends, so the refresh stays in flight.
        val fake =
            FakeNotificationsFlow(
                outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1")), "c1"),
                suspendFromCall = 2,
                loadMorePages = listOf(NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n2")), "c2")),
            )
        val viewModel = NotificationsViewModel(fake)

        viewModel.reload()
        assertTrue(viewModel.isRefreshing.value, "the reload is in flight")
        viewModel.onLoadMore()

        assertTrue(fake.loadMoreCalls.isEmpty(), "load-more is suppressed while a refresh is in flight (canLoadMore gate)")
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects
    // the projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun NotificationsViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}
