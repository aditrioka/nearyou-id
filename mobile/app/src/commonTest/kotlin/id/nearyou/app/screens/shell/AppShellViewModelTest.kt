package id.nearyou.app.screens.shell

import id.nearyou.app.notifications.FakeNotificationsFlow
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
import kotlin.test.assertTrue

/**
 * Unit coverage of [AppShellViewModel] — the shell-`NavEntry`-scoped holder for the Notifikasi unread-count
 * badge (2026-06-10 audit, finding 05-#9: the fetch moved off the composable into a retained VM). Pins: the
 * count is fetched exactly once on construction (the one-shot, design D6), [AppShellViewModel.refreshUnreadCount]
 * re-fetches (the leaving-Notifikasi refresh), a positive count shows the badge while zero / absent hides it.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main so the
 * init/refresh coroutines run eagerly and synchronously against the (non-suspending) fake. [activateUiState]
 * activates the `WhileSubscribed(5000)` share so `uiState.value` reflects the projection (mirrors the feed
 * VM tests). Mirrors `NotificationsViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_fetchesUnreadCountExactlyOnce_andProjectsTheCount() {
        val fake = FakeNotificationsFlow(unreadCountValue = 4)
        val viewModel = AppShellViewModel(fake)
        viewModel.activateUiState()

        assertEquals(1, fake.unreadCountInvocationCount, "the unread count is fetched exactly once on construction (one-shot — D6)")
        assertEquals(4L, viewModel.uiState.value.unreadCount, "the fetched count is projected into uiState")
        assertTrue(viewModel.uiState.value.showBadge, "a positive count shows the badge")
    }

    @Test
    fun refreshUnreadCount_reFetches() {
        val fake = FakeNotificationsFlow(unreadCountValue = 2)
        val viewModel = AppShellViewModel(fake)
        assertEquals(1, fake.unreadCountInvocationCount, "construction fetches once")

        // The leaving-Notifikasi refresh re-fetches the count once (not a poll loop — a single re-fetch).
        viewModel.refreshUnreadCount()
        assertEquals(2, fake.unreadCountInvocationCount, "refreshUnreadCount re-fetches (leaving the Notifikasi section)")
    }

    @Test
    fun zeroCount_hidesTheBadge() {
        val fake = FakeNotificationsFlow(unreadCountValue = 0)
        val viewModel = AppShellViewModel(fake)
        viewModel.activateUiState()

        assertEquals(0L, viewModel.uiState.value.unreadCount, "a zero count projects to 0")
        assertFalse(viewModel.uiState.value.showBadge, "the badge is hidden when count == 0")
    }

    @Test
    fun absentCount_resetsToZero_noBadge() {
        // A failed/absent count (the fake returns null) maps to 0 — the badge simply shows nothing, never an error.
        val fake = FakeNotificationsFlow(unreadCountValue = null)
        val viewModel = AppShellViewModel(fake)
        viewModel.activateUiState()

        assertEquals(0L, viewModel.uiState.value.unreadCount, "a null/absent count resets to 0")
        assertFalse(viewModel.uiState.value.showBadge, "no badge for an absent count")
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects the
    // projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun AppShellViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}
