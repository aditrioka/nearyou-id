package id.nearyou.app.screens.chat

import id.nearyou.app.chat.ConversationDto
import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.PartnerProfileDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun item(
    id: String,
    lastMessageAt: String? = "2026-06-02T11:00:00Z",
) = ConversationListItemDto(
    conversation = ConversationDto(id = id, createdAt = "2026-06-01T10:00:00Z", lastMessageAt = lastMessageAt),
    partner = PartnerProfileDto(id = "partner-uuid-$id", username = "u$id", displayName = "D$id", isPremium = false),
)

/** Pure projection coverage (tasks 7.1 / 12.1) — deterministic outcome→state; rows carry NO partner UUID. */
class ConversationListUiStateTest {
    @Test
    fun `initial load is Loading regardless of outcome`() {
        assertEquals(
            ConversationListUiState.Loading,
            conversationListUiState(ConversationListOutcome.Loaded(listOf(item("c1")), null), isInitialLoad = true),
        )
    }

    @Test
    fun `loaded non-empty projects to content and strips the partner UUID`() {
        val state = conversationListUiState(ConversationListOutcome.Loaded(listOf(item("c1")), "tok"), isInitialLoad = false)
        assertTrue(state is ConversationListUiState.Content)
        val row = state.rows.single()
        assertEquals("c1", row.conversationId)
        assertEquals("uc1", row.partnerUsername)
        assertEquals("Dc1", row.partnerDisplayName)
        // ConversationRow exposes NO partner UUID field — PII cannot leak (structural).
    }

    @Test
    fun `loaded empty projects to empty`() {
        assertEquals(
            ConversationListUiState.Empty,
            conversationListUiState(ConversationListOutcome.Loaded(emptyList(), null), isInitialLoad = false),
        )
    }

    @Test
    fun `network and generic errors project to Error`() {
        assertEquals(ConversationListUiState.Error, conversationListUiState(ConversationListOutcome.NetworkError, false))
        assertEquals(ConversationListUiState.Error, conversationListUiState(ConversationListOutcome.Error, false))
    }

    @Test
    fun `session expired projects to redirect`() {
        assertEquals(
            ConversationListUiState.SessionRedirect,
            conversationListUiState(ConversationListOutcome.SessionExpired, false),
        )
    }

    @Test
    fun `a null last_message_at is carried through for a brand-new conversation`() {
        val state =
            conversationListUiState(ConversationListOutcome.Loaded(listOf(item("c1", lastMessageAt = null)), null), false)
        assertTrue(state is ConversationListUiState.Content)
        assertEquals(null, state.rows.single().lastMessageAtIso)
    }
}
