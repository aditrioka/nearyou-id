package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModelStore
import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ConversationDto
import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeConversationsFlow
import id.nearyou.app.chat.PartnerProfileDto
import id.nearyou.app.chat.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ConversationPickerViewModel` tests (task 8.1): a recipient pick sends the embed (carrying
 * `embedded_post_id = postId` to the picked conversation) and yields a [ChatShareResult.Navigate]; a
 * `403` on the send yields [ChatShareResult.Blocked] with NO navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPickerViewModelTest {
    private val postId = "99999999-9999-9999-9999-999999999999"

    private fun row() =
        ConversationListItemDto(
            conversation = ConversationDto(id = "conv-1", createdAt = "2026-06-01T09:00:00Z", lastMessageAt = null),
            partner = PartnerProfileDto(id = "ignored", username = "raka", displayName = "Raka", isPremium = false),
        )

    // Constructs the VM AND activates its `stateIn(WhileSubscribed)` uiState by collecting it in the
    // background — without an active collector, uiState.value stays Loading (the audit #409 pattern).
    private fun TestScope.vm(
        conversationsFlow: FakeConversationsFlow,
        chatFlow: FakeChatFlow,
    ): ConversationPickerViewModel {
        val viewModel = ConversationPickerViewModel(postId = postId, conversationsFlow = conversationsFlow, chatFlow = chatFlow)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    // Cancels the VM's viewModelScope (the WhileSubscribed share) within the test body while Main is
    // still the test dispatcher, so the share is dead before runTest's end-of-test drain (else its
    // stop is scheduled onto a reset Main → DispatchException — the ChatThreadViewModelTest pattern).
    private fun ConversationPickerViewModel.tearDown() {
        ViewModelStore().apply {
            put("vm", this@tearDown)
            clear()
        }
    }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun listsConversationsOnLoad() =
        test {
            val convs = FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(listOf(row()), null))
            val viewModel = vm(convs, FakeChatFlow())
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(state is ConversationListUiState.Content)
            assertEquals(listOf("conv-1"), state.rows.map { it.conversationId })
            viewModel.tearDown()
        }

    @Test
    fun pickSendsEmbedAndNavigates() =
        test {
            val convs = FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(listOf(row()), null))
            val chat =
                FakeChatFlow(
                    sendOutcome =
                        SendOutcome.Sent(
                            ChatMessageDto(id = "m1", conversationId = "conv-1", senderId = "viewer", createdAt = "2026-06-01T12:00:00Z"),
                        ),
                )
            val viewModel = vm(convs, chat)
            advanceUntilIdle()
            val picked = (viewModel.uiState.value as ConversationListUiState.Content).rows.single()

            viewModel.shareTo(picked)
            advanceUntilIdle()

            // The send carried the embed to the picked conversation — content null, embedded_post_id = postId.
            assertEquals(listOf("conv-1"), chat.sentConversationIds)
            assertEquals(listOf<String?>(null), chat.sentContents)
            assertEquals(listOf<String?>(postId), chat.sentEmbeddedPostIds)
            // Result is Navigate to that conversation, carrying the PII-free partner display fields.
            val result = viewModel.shareResult.value
            assertTrue(result is ChatShareResult.Navigate)
            assertEquals("conv-1", result.conversationId)
            assertEquals("Raka", result.partnerDisplayName)
            viewModel.tearDown()
        }

    @Test
    fun blockedSendMapsToBlockedResultNoNavigation() =
        test {
            val convs = FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(listOf(row()), null))
            val chat = FakeChatFlow(sendOutcome = SendOutcome.Blocked)
            val viewModel = vm(convs, chat)
            advanceUntilIdle()
            val picked = (viewModel.uiState.value as ConversationListUiState.Content).rows.single()

            viewModel.shareTo(picked)
            advanceUntilIdle()

            assertEquals(ChatShareResult.Blocked, viewModel.shareResult.value)
            viewModel.tearDown()
        }

    @Test
    fun failedSendMapsToFailedResult() =
        test {
            val convs = FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(listOf(row()), null))
            val chat = FakeChatFlow(sendOutcome = SendOutcome.NetworkError)
            val viewModel = vm(convs, chat)
            advanceUntilIdle()
            val picked = (viewModel.uiState.value as ConversationListUiState.Content).rows.single()

            viewModel.shareTo(picked)
            advanceUntilIdle()

            assertEquals(ChatShareResult.Failed, viewModel.shareResult.value)
            viewModel.clearShareResult()
            assertNull(viewModel.shareResult.value)
            viewModel.tearDown()
        }
}
