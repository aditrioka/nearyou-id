package id.nearyou.app.chat

/**
 * A configurable [ChatFlow] for ViewModel tests. [historyOutcomes] is a queue consumed one-per
 * [loadHistory] call (the last entry repeats once drained), so a test can script the initial load + a
 * resync. [sendOutcome] / [createOutcome] drive [send] / [createOrReturn]. Invocation counts let the
 * test assert resync-on-resubscribe + send-via-REST.
 */
class FakeChatFlow(
    historyOutcomes: List<ChatThreadOutcome> = listOf(ChatThreadOutcome.Loaded(emptyList(), null)),
    var sendOutcome: SendOutcome =
        SendOutcome.Sent(
            ChatMessageDto(
                id = "server-1",
                conversationId = "c1",
                senderId = "viewer-1",
                content = "hi",
                createdAt = "2026-06-01T12:00:00Z",
            ),
        ),
    var createOutcome: CreateConversationOutcome = CreateConversationOutcome.Ready("c1"),
) : ChatFlow {
    private val queue = ArrayDeque(historyOutcomes)

    var loadHistoryCount: Int = 0
        private set
    var sendCount: Int = 0
        private set
    val sentContents: MutableList<String?> = mutableListOf()

    // chat-embedded-posts: the send args captured so a test can assert an embed send carried the
    // expected conversation id + embedded_post_id (and no content).
    val sentConversationIds: MutableList<String> = mutableListOf()
    val sentEmbeddedPostIds: MutableList<String?> = mutableListOf()

    override suspend fun loadHistory(
        conversationId: String,
        cursor: String?,
    ): ChatThreadOutcome {
        loadHistoryCount++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun send(
        conversationId: String,
        content: String?,
        embeddedPostId: String?,
    ): SendOutcome {
        sendCount++
        sentConversationIds.add(conversationId)
        sentContents.add(content)
        sentEmbeddedPostIds.add(embeddedPostId)
        return sendOutcome
    }

    override suspend fun createOrReturn(recipientUserId: String): CreateConversationOutcome = createOutcome
}
