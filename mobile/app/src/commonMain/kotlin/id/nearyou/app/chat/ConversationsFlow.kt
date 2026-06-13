package id.nearyou.app.chat

/**
 * The conversation-list orchestration contract consumed by `ConversationListScreen`. The production
 * binding is [ConversationsRepository]; commonTest substitutes a `FakeConversationsFlow` so the screen
 * tests can drive a specific outcome without a backend — mirroring `GlobalTimelineFlow`.
 */
interface ConversationsFlow {
    /** Loads the first page of the conversation list. Pull-to-refresh + retry re-invoke this. */
    suspend fun loadFirstPage(): ConversationListOutcome
}

/**
 * Every list-conversations fetch maps to EXACTLY one member, keyed on the HTTP **status** /
 * transport-failure type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough
 * (mirrors `GlobalTimelineOutcome`). A terminal `401` (one that survived the shipped `Auth` refresh)
 * maps to [SessionExpired] — the `Auth` plugin + `SessionInvalidator` still own the actual re-route to
 * sign-in; this member only guarantees the brief pre-re-route render is a neutral redirect placeholder.
 */
sealed interface ConversationListOutcome {
    /**
     * HTTP 200. Carries the raw parsed [conversations] (incl. each `partner.id` UUID that the UI-state
     * projection STRIPS — PII never reaches the rendered state) + the snake_case [nextCursor] (retained,
     * not yet used for load-more).
     */
    data class Loaded(
        val conversations: List<ConversationListItemDto>,
        val nextCursor: String?,
    ) : ConversationListOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable (connectivity copy). */
    data object NetworkError : ConversationListOutcome

    /** HTTP 400 (`invalid_cursor` — not expected on the always-valid first page) — retryable, logged. */
    data object Error : ConversationListOutcome

    /** Terminal HTTP 401 (survived the `Auth` refresh) — neutral redirect placeholder (NO retry). */
    data object SessionExpired : ConversationListOutcome
}
