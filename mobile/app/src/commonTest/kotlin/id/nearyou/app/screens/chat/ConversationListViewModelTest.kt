package id.nearyou.app.screens.chat

import id.nearyou.app.chat.ConversationDto
import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.chat.FakeConversationsFlow
import id.nearyou.app.chat.PartnerProfileDto
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
import kotlin.test.assertTrue

/**
 * Unit coverage of [ConversationListViewModel] — the `ConversationListRoute`-scoped holder for the
 * conversation-list first-page load state. Pins: the first page loads exactly once on construction,
 * [ConversationListViewModel.reload] re-fetches (pull-to-refresh + error retry), a thrown load failure
 * maps to the retryable [ConversationListOutcome.NetworkError] (and `uiState` projects to `Error`), and
 * the single-`uiState` contract (docs/11 §2.2): `uiState` delegates to the pure
 * `conversationListUiState(...)` projection and retains the resolved state across a fresh collector
 * (the configuration-change proxy). Mirrors `GlobalTimelineViewModelTest`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main
 * so the init/reload coroutines run eagerly and synchronously against the (non-suspending) fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun item(id: String) =
        ConversationListItemDto(
            conversation = ConversationDto(id = id, createdAt = "2026-06-01T10:00:00Z", lastMessageAt = "2026-06-02T11:00:00Z"),
            partner = PartnerProfileDto(id = "partner-uuid-$id", username = "u$id", displayName = "D$id", isPremium = false),
        )

    private fun loadedFlow(vararg ids: String) =
        FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(ids.map { item(it) }, nextCursor = null))

    @Test
    fun `first page loads once on construction and exposes the outcome`() {
        val flow = loadedFlow("c1")
        val vm = ConversationListViewModel(flow)
        assertEquals(1, flow.loadInvocationCount, "the first page loads exactly once on construction")
        assertTrue(vm.outcome.value is ConversationListOutcome.Loaded, "the loaded outcome is exposed")
        assertEquals(false, vm.isRefreshing.value, "isRefreshing is false after the initial load completes")
    }

    @Test
    fun `reload re-fetches page one`() {
        val flow = loadedFlow("c1")
        val vm = ConversationListViewModel(flow)
        vm.reload()
        assertEquals(2, flow.loadInvocationCount, "a reload re-issues loadFirstPage()")
    }

    @Test
    fun `a thrown load failure maps to NetworkError and uiState projects to Error`() {
        val throwing =
            object : ConversationsFlow {
                override suspend fun loadFirstPage(): ConversationListOutcome = throw RuntimeException("boom")
            }
        val vm = ConversationListViewModel(throwing)
        vm.activateUiState()
        assertEquals(ConversationListOutcome.NetworkError, vm.outcome.value, "a thrown fetch maps to the retryable NetworkError")
        assertTrue(vm.uiState.value is ConversationListUiState.Error, "and uiState projects to Error")
    }

    @Test
    fun `uiState delegates to the pure projection`() {
        val flow = loadedFlow("c1")
        val vm = ConversationListViewModel(flow)
        vm.activateUiState()
        // The single uiState equals the pure projection for the held outcome (reused, not reimplemented);
        // after the first outcome arrives the initial-load flag is false.
        assertEquals(
            conversationListUiState(vm.outcome.value, isInitialLoad = false),
            vm.uiState.value,
            "uiState delegates to conversationListUiState(outcome, isInitialLoad)",
        )
        assertTrue(vm.uiState.value is ConversationListUiState.Content, "a loaded non-empty outcome projects to Content")
    }

    @Test
    fun `uiState retains Content across a fresh collector`() {
        // The config-change proxy: the entry-scoped VM retains the resolved outcome, so a FRESH uiState
        // collector (the recomposed screen) still sees Content — not a reset to Loading.
        val flow = loadedFlow("c1")
        val vm = ConversationListViewModel(flow)
        vm.activateUiState()
        assertTrue(vm.uiState.value is ConversationListUiState.Content, "loaded → Content")
        vm.activateUiState() // a second, fresh collector (the config-change case)
        assertTrue(
            vm.uiState.value is ConversationListUiState.Content,
            "a fresh collector still sees the retained Content (not reset to Loading)",
        )
    }

    // Activates the WhileSubscribed(5000) uiState share (on the Unconfined Main) so uiState.value reflects
    // the projected state in these synchronous tests; the collector is abandoned at test end (no runTest).
    private fun ConversationListViewModel.activateUiState() {
        CoroutineScope(Dispatchers.Main).launch { uiState.collect {} }
    }
}
