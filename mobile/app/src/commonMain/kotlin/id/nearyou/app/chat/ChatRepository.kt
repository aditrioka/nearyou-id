package id.nearyou.app.chat

/**
 * Orchestrates the chat-thread operations (design D4/D9): delegates to [messagesApiClient] (history +
 * send) and [conversationsApiClient] (create-or-return), mapping each low-level `*ApiResult` to EXACTLY
 * one sealed outcome member, keyed on the HTTP **status**, with no generic "failed" wildcard. A
 * stateless Koin singleton (every method takes its ids), so `single<ChatFlow> { get<ChatRepository>() }`
 * is safe.
 *
 * `401` is delegated to the shipped `Auth` plugin (this repository MUST NOT reimplement token refresh /
 * re-route) — it only maps a terminal 401 to the neutral `SessionExpired` outcome. The [diagnosticLog]
 * sink is coordinate-free by construction — only status/code primitives are passed (never a body,
 * token, message content, or UUID).
 */
class ChatRepository(
    private val messagesApiClient: ChatMessagesApiClient,
    private val conversationsApiClient: ConversationsApiClient,
    private val diagnosticLog: (status: Int) -> Unit = {},
) : ChatFlow {
    override suspend fun loadHistory(
        conversationId: String,
        cursor: String?,
    ): ChatThreadOutcome =
        when (val result = messagesApiClient.getMessages(conversationId, cursor)) {
            is ChatMessagesApiResult.Success ->
                ChatThreadOutcome.Loaded(messages = result.body.messages, nextCursor = result.body.nextCursor)
            is ChatMessagesApiResult.NetworkError -> ChatThreadOutcome.NetworkError
            is ChatMessagesApiResult.HttpError ->
                when {
                    result.status == 401 -> ChatThreadOutcome.SessionExpired
                    // 400 (cursor/UUID) / 403 (not a participant) / 404 (unknown conv) — retryable, logged.
                    result.status == 400 || result.status == 403 || result.status == 404 -> {
                        diagnosticLog(result.status)
                        ChatThreadOutcome.Error
                    }
                    result.status in 500..599 -> ChatThreadOutcome.NetworkError
                    else -> ChatThreadOutcome.NetworkError
                }
        }

    override suspend fun send(
        conversationId: String,
        content: String,
    ): SendOutcome =
        when (val result = messagesApiClient.sendMessage(conversationId, content)) {
            is SendMessageApiResult.Created -> SendOutcome.Sent(result.message)
            is SendMessageApiResult.NetworkError -> SendOutcome.NetworkError
            is SendMessageApiResult.HttpError ->
                when {
                    result.status == 401 -> SendOutcome.SessionExpired
                    result.status == 403 -> SendOutcome.Blocked
                    result.status == 400 -> SendOutcome.TooLong
                    // 404 unknown conversation / 429 rate-limit / other non-2xx — retryable, logged.
                    result.status in 500..599 -> SendOutcome.NetworkError
                    else -> {
                        diagnosticLog(result.status)
                        SendOutcome.Error
                    }
                }
        }

    override suspend fun createOrReturn(recipientUserId: String): CreateConversationOutcome =
        when (val result = conversationsApiClient.createOrReturnConversation(recipientUserId)) {
            is CreateConversationApiResult.Created -> CreateConversationOutcome.Ready(result.body.conversation.id)
            is CreateConversationApiResult.Returned -> CreateConversationOutcome.Ready(result.body.conversation.id)
            CreateConversationApiResult.Blocked -> CreateConversationOutcome.Blocked
            CreateConversationApiResult.SelfConversation -> CreateConversationOutcome.SelfConversation
            CreateConversationApiResult.RecipientNotFound -> CreateConversationOutcome.RecipientNotFound
            is CreateConversationApiResult.NetworkError -> CreateConversationOutcome.NetworkError
            is CreateConversationApiResult.HttpError ->
                when {
                    result.status == 401 -> CreateConversationOutcome.SessionExpired
                    result.status in 500..599 -> CreateConversationOutcome.NetworkError
                    else -> {
                        diagnosticLog(result.status)
                        CreateConversationOutcome.Error
                    }
                }
        }
}
