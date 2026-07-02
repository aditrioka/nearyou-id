package id.nearyou.app.chat

/**
 * The chat-thread orchestration contract consumed by `ChatThreadScreen` / `ChatThreadViewModel`. The
 * production binding is [ChatRepository]; commonTest substitutes a `FakeChatFlow` so the ViewModel /
 * screen tests can drive specific outcomes without a backend.
 */
interface ChatFlow {
    /** Loads a page of message history (first page when [cursor] is null; older pages on scroll-up). */
    suspend fun loadHistory(
        conversationId: String,
        cursor: String? = null,
    ): ChatThreadOutcome

    /**
     * Sends a message; maps the POST status to a [SendOutcome] (403 → Blocked, 400 → TooLong/Error, …).
     * The text-bar path passes [content] only; the share-to-chat flow (`mobile-chat-embedded-posts`)
     * MAY additionally pass an [embeddedPostId] (content then optional) — the same send method, no
     * separate endpoint.
     */
    suspend fun send(
        conversationId: String,
        content: String? = null,
        embeddedPostId: String? = null,
    ): SendOutcome

    /** Create-or-return a 1:1 conversation with [recipientUserId] (the future profile "Kirim pesan" path). */
    suspend fun createOrReturn(recipientUserId: String): CreateConversationOutcome
}

/**
 * A message-history fetch maps to EXACTLY one member, keyed on HTTP status / transport type — no
 * generic fallthrough. Terminal `401` → [SessionExpired] (neutral redirect; the `Auth` plugin re-routes).
 */
sealed interface ChatThreadOutcome {
    /** HTTP 200. Raw parsed [messages] (incl. each `sender_id` UUID the projection uses ONLY for the
     *  own-vs-other decision, never rendered) + the snake_case [nextCursor] (older-page cursor). */
    data class Loaded(
        val messages: List<ChatMessageDto>,
        val nextCursor: String?,
    ) : ChatThreadOutcome

    /** HTTP 5xx / transport / unenumerated non-2xx — retryable. */
    data object NetworkError : ChatThreadOutcome

    /** HTTP 400 (malformed cursor/UUID) / 403 (not a participant) / 404 (unknown conversation) — retryable, logged. */
    data object Error : ChatThreadOutcome

    /** Terminal HTTP 401 — neutral redirect placeholder (NO retry). */
    data object SessionExpired : ChatThreadOutcome
}

/**
 * A send maps to EXACTLY one member. `403` → [Blocked] (the canonical block banner), `400` → [TooLong]
 * (the client gate already blocks empty/over-limit, so a 400 is the over-limit/empty edge), other
 * non-2xx → [Error], 5xx/transport → [NetworkError], terminal `401` → [SessionExpired]. `201` →
 * [Sent] with the inserted message (the optimistic row reconciles to its server id).
 */
sealed interface SendOutcome {
    data class Sent(val message: ChatMessageDto) : SendOutcome

    data object Blocked : SendOutcome

    data object TooLong : SendOutcome

    data object NetworkError : SendOutcome

    data object Error : SendOutcome

    data object SessionExpired : SendOutcome
}

/**
 * The create-or-return result. `201` (new) + `200` (existing) both collapse to [Ready] (carry the
 * conversation id to navigate to the thread). `403` → [Blocked], `400` → [SelfConversation], `404` →
 * [RecipientNotFound] (the distinct split per tasks 6.2/12.2), `401` → [SessionExpired], other non-2xx →
 * [Error], 5xx/transport → [NetworkError].
 */
sealed interface CreateConversationOutcome {
    data class Ready(val conversationId: String) : CreateConversationOutcome

    data object Blocked : CreateConversationOutcome

    data object SelfConversation : CreateConversationOutcome

    data object RecipientNotFound : CreateConversationOutcome

    data object NetworkError : CreateConversationOutcome

    data object Error : CreateConversationOutcome

    data object SessionExpired : CreateConversationOutcome
}
