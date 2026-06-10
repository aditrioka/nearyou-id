package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.MarkAllReadResult
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        assertFalse(viewModel.isInitialLoad.value, "isInitialLoad resets after the first load completes")
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
        assertEquals(
            NotificationsOutcome.NetworkError,
            viewModel.outcome.value,
            "a fetch failure maps to the existing retryable NetworkError (no new outcome member)",
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
}
