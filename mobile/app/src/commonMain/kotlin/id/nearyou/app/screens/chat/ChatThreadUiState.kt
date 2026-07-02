package id.nearyou.app.screens.chat

import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.screens.post.codePointLength

/** The chat message length cap — 2000 code points (`chat-conversations` content guard, docs/02 §319).
 *  The client gate is a UX nicety; the server NFKC-normalizes + length-guards authoritatively. */
const val MAX_CHAT_CONTENT_CODE_POINTS: Int = 2000

/**
 * The Compose-free internal merge model for one chat message — the common shape REST history, optimistic
 * sends, and realtime inbound all normalize to before the id-dedupe merge. [senderId] is used ONLY for
 * the own-vs-other decision; it is dropped at the [ChatMessageRow] projection so it never renders.
 * [content] is null when [isRedacted].
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val content: String?,
    val createdAtIso: String,
    val isRedacted: Boolean,
    // chat-embedded-posts: the shared-post context (null for a plain message). [embeddedPostId] is
    // null after the source post is hard-deleted (snapshot still present → "deleted" card state).
    val embeddedPostId: String? = null,
    val embeddedPostSnapshot: EmbeddedPostSnapshot? = null,
    val embeddedPostEditId: String? = null,
)

/** Maps a REST/realtime DTO to the merge model — content is forced null when redacted (defensive; the
 *  backend already nulls it). A non-null `redacted_at` marks the message redacted. The embed fields are
 *  carried through (the redaction-precedence suppression happens at the row projection, not here). */
fun ChatMessageDto.toChatMessage(): ChatMessage =
    ChatMessage(
        id = id,
        senderId = senderId,
        content = if (redactedAt != null) null else content,
        createdAtIso = createdAt,
        isRedacted = redactedAt != null,
        embeddedPostId = embeddedPostId,
        embeddedPostSnapshot = embeddedPostSnapshot,
        embeddedPostEditId = embeddedPostEditId,
    )

/**
 * The display-only projection of a chat message — the PII-stripped model a bubble renders. Carries NO
 * sender UUID (the own-vs-other decision is resolved into [isOwn] at projection time). [content] is null
 * for a redacted message → the screen renders the neutral `chat_message_redacted` placeholder, never an
 * empty/`null` bubble. [id] is the message's own UUID (a stable `LazyColumn` key, not sender PII).
 */
data class ChatMessageRow(
    val id: String,
    val content: String?,
    val isOwn: Boolean,
    val isRedacted: Boolean,
    val createdAtIso: String,
    // chat-embedded-posts: the context-card data, present ONLY on a non-redacted embed message. A
    // redacted message carries NULL embed fields here (the redaction-precedence rule keys on
    // redacted_at, not on a null content — design D9) so the screen renders the placeholder, not the
    // card. [embeddedPostId] null + [embeddedPostSnapshot] present = the source post was hard-deleted
    // ("post telah dihapus" state, not tappable).
    val embeddedPostId: String? = null,
    val embeddedPostSnapshot: EmbeddedPostSnapshot? = null,
    val embeddedPostEditId: String? = null,
) {
    /** True when this row should render the shared-post context card (a non-redacted embed message). */
    val hasEmbeddedPost: Boolean get() = !isRedacted && embeddedPostSnapshot != null

    /**
     * The report-affordance gate (`mobile-chat-message-report`): a message is reportable only when it is
     * the OTHER party's ([isOwn] false) AND not already redacted ([isRedacted] false). Derived purely
     * from the PII-stripped projection fields — `senderId` never reaches this row, so the gate is the
     * row-level realization of the conceptual "other party's message" / post `!isAuthor` gate.
     */
    val isReportable: Boolean get() = !isOwn && !isRedacted
}

/**
 * Pure id-deduplicated merge of the three message sources into one ordered list (design D4 / task 7.2).
 * The union of [rest] + [optimistic] + [realtime] is collapsed by message [ChatMessage.id]:
 *  - a new id appends;
 *  - the same id from multiple sources collapses to ONE row (the optimistic echo / realtime echo of a
 *    REST or optimistic row);
 *  - redaction is terminal — if ANY source for an id is redacted, the merged row is redacted with
 *    `content == null` (a later redaction inbound flips an existing row IN PLACE, no second row);
 *  - ordering is by `(createdAtIso, id)` ascending (oldest→newest); the [ChatMessage.id] secondary key
 *    is the deterministic tiebreak when two messages share an identical `createdAt` (the only case it
 *    matters).
 *
 * The temp-client-id → server-id reconcile (case where an optimistic row's id is replaced by the 201's
 * server id) happens in the ViewModel BEFORE this merge; here, once optimistic + realtime both carry the
 * server id, the dedupe collapses them. No PII beyond the merge model leaks (the result is projected by
 * [chatMessageRows], which drops `senderId`).
 */
fun mergeChatMessages(
    rest: List<ChatMessage>,
    optimistic: List<ChatMessage>,
    realtime: List<ChatMessage>,
): List<ChatMessage> {
    val byId = LinkedHashMap<String, ChatMessage>()
    for (message in rest + optimistic + realtime) {
        val existing = byId[message.id]
        byId[message.id] = if (existing == null) message else mergeOne(existing, message)
    }
    return byId.values.sortedWith(compareBy({ it.createdAtIso }, { it.id }))
}

/** Collapses two same-id versions: redaction wins (terminal); otherwise keep a non-null content; the
 *  earliest `createdAt` is kept (they agree in practice — `minOf` is just deterministic). */
private fun mergeOne(
    a: ChatMessage,
    b: ChatMessage,
): ChatMessage {
    val redacted = a.isRedacted || b.isRedacted
    return a.copy(
        content = if (redacted) null else (b.content ?: a.content),
        isRedacted = redacted,
        createdAtIso = minOf(a.createdAtIso, b.createdAtIso),
        senderId = a.senderId,
        // Preserve the embed across a REST↔realtime collapse (both sources carry the same embed for
        // the same id); prefer whichever source populated it.
        embeddedPostId = a.embeddedPostId ?: b.embeddedPostId,
        embeddedPostSnapshot = a.embeddedPostSnapshot ?: b.embeddedPostSnapshot,
        embeddedPostEditId = a.embeddedPostEditId ?: b.embeddedPostEditId,
    )
}

/** Projects merged messages to display rows: own-vs-other by `senderId == viewerId`; redacted rows carry
 *  null content (the screen substitutes the redacted placeholder). No `senderId` survives the projection. */
fun chatMessageRows(
    merged: List<ChatMessage>,
    viewerId: String,
): List<ChatMessageRow> =
    merged.map { message ->
        // Redaction precedence (design D9): a redacted message renders the neutral placeholder and
        // NEVER the context card, so the embed fields are dropped from the row when redacted.
        val redacted = message.isRedacted
        ChatMessageRow(
            id = message.id,
            content = if (redacted) null else message.content,
            isOwn = message.senderId == viewerId,
            isRedacted = redacted,
            createdAtIso = message.createdAtIso,
            embeddedPostId = if (redacted) null else message.embeddedPostId,
            embeddedPostSnapshot = if (redacted) null else message.embeddedPostSnapshot,
            embeddedPostEditId = if (redacted) null else message.embeddedPostEditId,
        )
    }

/**
 * Pure, Compose-free projection of the chat-thread screen state. Driven by the history [outcome] +
 * [isInitialLoad] + the already-merged [rows]:
 *  - initial load (or not-yet-loaded) ⇒ [Loading];
 *  - `SessionExpired` ⇒ [SessionRedirect] (neutral, no retry);
 *  - `NetworkError`/`Error` with NO rows yet ⇒ [Error]; with rows already loaded ⇒ [Content] (a transient
 *    history error never blanks an already-rendered thread — realtime/optimistic rows stay visible);
 *  - `Loaded` ⇒ [Content] (an empty thread renders an empty list + the input bar, still usable to send).
 */
sealed interface ChatThreadUiState {
    data object Loading : ChatThreadUiState

    data class Content(val rows: List<ChatMessageRow>) : ChatThreadUiState

    data object Error : ChatThreadUiState

    data object SessionRedirect : ChatThreadUiState
}

fun chatThreadUiState(
    outcome: ChatThreadOutcome?,
    isInitialLoad: Boolean,
    rows: List<ChatMessageRow>,
): ChatThreadUiState {
    if (isInitialLoad) return ChatThreadUiState.Loading
    return when (outcome) {
        null -> ChatThreadUiState.Loading
        ChatThreadOutcome.SessionExpired -> ChatThreadUiState.SessionRedirect
        ChatThreadOutcome.NetworkError, ChatThreadOutcome.Error ->
            if (rows.isEmpty()) ChatThreadUiState.Error else ChatThreadUiState.Content(rows)
        is ChatThreadOutcome.Loaded -> ChatThreadUiState.Content(rows)
    }
}

/**
 * The input-bar state, projected from the last [SendOutcome] + the in-flight flag (task 7.3):
 *  - in-flight ⇒ [Sending] (send disabled);
 *  - `Blocked` ⇒ [Blocked] (the canonical block banner);
 *  - `TooLong` ⇒ [TooLong] (inline over-limit hint — defensive; the client gate already blocks it);
 *  - `NetworkError`/`Error` ⇒ [NetworkRetry] (retry the send);
 *  - `SessionExpired` ⇒ [Idle] (the `SessionInvalidator` re-route owns the transition; no send chrome);
 *  - otherwise ⇒ [Idle].
 */
sealed interface SendBarState {
    data object Idle : SendBarState

    data object Sending : SendBarState

    data object Blocked : SendBarState

    data object TooLong : SendBarState

    data object NetworkRetry : SendBarState
}

fun sendBarState(
    outcome: SendOutcome?,
    inFlight: Boolean,
): SendBarState {
    if (inFlight) return SendBarState.Sending
    return when (outcome) {
        null, is SendOutcome.Sent, SendOutcome.SessionExpired -> SendBarState.Idle
        SendOutcome.Blocked -> SendBarState.Blocked
        SendOutcome.TooLong -> SendBarState.TooLong
        SendOutcome.NetworkError, SendOutcome.Error -> SendBarState.NetworkRetry
    }
}

/**
 * The edited-since-shared banner gate (`mobile-chat-embedded-posts`): the context card shows the
 * "diedit sejak dibagikan" banner when the live post's latest-edit signal ([liveLatestEditId])
 * differs from the message's [anchorEditId] (the version-at-share-time anchor). Both-null (the post
 * was unedited at share time AND is still unedited) → no banner. A null [liveLatestEditId] is treated
 * as "live edit state UNKNOWN" → no banner (never a false positive from missing data).
 *
 * DEFERRED (live source): the chat thread does not yet fetch a per-card live-edit signal — the card
 * renders from the immutable snapshot (design D1) and the thread fetches no live post state. Until
 * that source is wired the runtime caller passes [liveLatestEditId] == [anchorEditId] (no banner).
 * This pure gate IS the spec'd comparison (and is exercised directly by the banner test); only the
 * live-edit fetch is deferred to a follow-up, captured as an explicit spec requirement.
 */
fun editedSinceShared(
    anchorEditId: String?,
    liveLatestEditId: String?,
): Boolean {
    if (liveLatestEditId == null) return false
    return anchorEditId != liveLatestEditId
}

/**
 * Client-side send guard (task 7.3): the trimmed content must have ≥1 non-blank code point AND ≤
 * [MAX_CHAT_CONTENT_CODE_POINTS] code points. An empty/whitespace-only or over-limit input is rejected
 * BEFORE any request (the send control is disabled). Counts RAW Unicode code points (no NFKC); the
 * server normalizes + guards authoritatively.
 */
fun canSubmitChat(content: String): Boolean {
    val trimmed = content.trim()
    return trimmed.isNotEmpty() && content.codePointLength() <= MAX_CHAT_CONTENT_CODE_POINTS
}

/**
 * The one-shot report-result message for the chat surface (`mobile-chat-message-report`). The shared
 * [ReportOutcome] is mapped per-surface (see the [ReportOutcome] doc): the chat thread uses the
 * **post-detail posture** — [ReportOutcome.Submitted] AND [ReportOutcome.Duplicate] both map to [SUCCESS]
 * (the duplicate case is indistinguishable from a first report — anti-enumeration / anti-retaliation in a
 * 1:1 context, `docs/03`:234); [ReportOutcome.RateLimited] → [RATE_LIMITED] (no success claim);
 * [ReportOutcome.NetworkError] → [FAILED] (retryable). The screen maps each to a `:shared:resources`
 * string and shows it as a one-shot snackbar.
 */
enum class ChatReportMessage {
    SUCCESS,
    RATE_LIMITED,
    FAILED,
}

/** Pure, exhaustive (no wildcard) [ReportOutcome] → [ChatReportMessage] mapping (the post-detail posture). */
fun chatReportMessage(outcome: ReportOutcome): ChatReportMessage =
    when (outcome) {
        ReportOutcome.Submitted, ReportOutcome.Duplicate -> ChatReportMessage.SUCCESS
        is ReportOutcome.RateLimited -> ChatReportMessage.RATE_LIMITED
        ReportOutcome.NetworkError -> ChatReportMessage.FAILED
    }
