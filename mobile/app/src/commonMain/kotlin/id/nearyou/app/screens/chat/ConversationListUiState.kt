package id.nearyou.app.screens.chat

import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome

/**
 * The display-only projection of one conversation row — the PII-stripped model the list renders. It
 * carries ONLY what a row shows plus the [conversationId] needed for navigation (a conversation
 * identifier, NOT user PII — design D3). There is deliberately NO `partner.id` (the partner's user
 * UUID): the rendered state STRUCTURALLY cannot leak it. [partnerDisplayName] is the COALESCE-masked
 * value when the partner is shadow-banned/deleted (`"Akun Dihapus"`). [lastMessageAtIso] is null for a
 * brand-new conversation with no messages.
 */
data class ConversationRow(
    val conversationId: String,
    val partnerUsername: String,
    val partnerDisplayName: String,
    val partnerIsPremium: Boolean,
    val lastMessageAtIso: String?,
)

private fun ConversationListItemDto.toRow(): ConversationRow =
    ConversationRow(
        conversationId = conversation.id,
        partnerUsername = partner.username,
        partnerDisplayName = partner.displayName,
        partnerIsPremium = partner.isPremium,
        lastMessageAtIso = conversation.lastMessageAt,
    )

/**
 * Pure, Compose-free projection of the conversation-list UI state, mirroring `GlobalTimelineUiState`.
 * The five states are deterministic functions of the [ConversationListOutcome] + the [isInitialLoad]
 * flag (no wall-clock / platform dependency), and carry NO PII — [Content] rows are [ConversationRow]
 * (partner UUID already dropped).
 */
sealed interface ConversationListUiState {
    /** Initial load (no content yet) → skeleton + loading copy. NOT used during a refresh of loaded content. */
    data object Loading : ConversationListUiState

    /** `Loaded`, non-empty → the conversation row list. */
    data class Content(val rows: List<ConversationRow>) : ConversationListUiState

    /** `Loaded`, empty → the "no conversations yet" empty state. */
    data object Empty : ConversationListUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : ConversationListUiState

    /** `SessionExpired` (terminal 401) → a neutral redirect placeholder (NO retry). */
    data object SessionRedirect : ConversationListUiState
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [isInitialLoad] to the screen state. Exhaustive
 * over [ConversationListOutcome] — no generic fallthrough. The [isInitialLoad] flag distinguishes the
 * initial skeleton from a refresh: during a refresh of loaded content [isInitialLoad] is false and the
 * retained `Loaded` outcome projects to [Content]/[Empty] (the list stays mounted); the refresh
 * indicator is conveyed separately via the `PullToRefreshBox` `isRefreshing` value.
 */
fun conversationListUiState(
    outcome: ConversationListOutcome?,
    isInitialLoad: Boolean,
): ConversationListUiState {
    if (isInitialLoad) return ConversationListUiState.Loading
    return when (outcome) {
        null -> ConversationListUiState.Loading
        is ConversationListOutcome.Loaded -> {
            val rows = outcome.conversations.map { it.toRow() }
            if (rows.isEmpty()) ConversationListUiState.Empty else ConversationListUiState.Content(rows)
        }
        ConversationListOutcome.NetworkError, ConversationListOutcome.Error -> ConversationListUiState.Error
        ConversationListOutcome.SessionExpired -> ConversationListUiState.SessionRedirect
    }
}
