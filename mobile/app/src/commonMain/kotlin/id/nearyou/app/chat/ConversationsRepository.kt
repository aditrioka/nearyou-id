package id.nearyou.app.chat

/**
 * Orchestrates a conversation-list first-page fetch: call `GET /api/v1/conversations` → map the HTTP
 * **status** to exactly one [ConversationListOutcome] (mirrors `GlobalTimelineRepository`). No generic
 * "load failed" fallthrough; `401` is delegated to the shipped `Auth` plugin (this repository MUST NOT
 * reimplement token refresh / re-route). The [diagnosticLog] sink is coordinate-free by construction —
 * only a status string is passed (never a body, token, or UUID).
 */
class ConversationsRepository(
    private val apiClient: ConversationsApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : ConversationsFlow {
    override suspend fun loadFirstPage(): ConversationListOutcome =
        when (val result = apiClient.getConversations(cursor = null)) {
            is ConversationsApiResult.Success ->
                ConversationListOutcome.Loaded(
                    conversations = result.body.conversations,
                    nextCursor = result.body.nextCursor,
                )
            is ConversationsApiResult.NetworkError -> {
                diagnosticLog("conversations_network_error: ${result.cause::class.simpleName}")
                ConversationListOutcome.NetworkError
            }
            is ConversationsApiResult.HttpError ->
                when {
                    result.status == 401 -> ConversationListOutcome.SessionExpired
                    result.status == 400 -> {
                        diagnosticLog("conversations_invalid_request: status=400")
                        ConversationListOutcome.Error
                    }
                    result.status in 500..599 -> ConversationListOutcome.NetworkError
                    // Defined retryable fallback over the Int status — NOT a generic "load failed" copy.
                    else -> ConversationListOutcome.NetworkError
                }
        }
}
