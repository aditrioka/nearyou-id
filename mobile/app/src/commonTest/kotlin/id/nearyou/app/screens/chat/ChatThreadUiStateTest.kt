package id.nearyou.app.screens.chat

import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.data.report.ReportOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun msg(
    id: String,
    sender: String = "other",
    content: String? = "c",
    createdAt: String = "2026-06-01T10:00:00Z",
    redacted: Boolean = false,
) = ChatMessage(id = id, senderId = sender, content = content, createdAtIso = createdAt, isRedacted = redacted)

/**
 * Pure projection + merge coverage (tasks 7.2 / 7.3 / 12.1). Deterministic; no wall-clock / platform.
 */
class ChatThreadUiStateTest {
    private val viewer = "viewer-1"

    // ---- message merge branches (a)–(e) ----

    @Test
    fun `a new id appends`() {
        val merged = mergeChatMessages(rest = listOf(msg("m1")), optimistic = emptyList(), realtime = listOf(msg("m2")))
        assertEquals(listOf("m1", "m2"), merged.map { it.id })
    }

    @Test
    fun `rest plus optimistic plus realtime sharing one id collapses to one row`() {
        val merged =
            mergeChatMessages(
                rest = listOf(msg("m1")),
                optimistic = listOf(msg("m1")),
                realtime = listOf(msg("m1")),
            )
        assertEquals(1, merged.size)
        assertEquals("m1", merged.single().id)
    }

    @Test
    fun `temp client-id reconciled to a server id then a realtime echo of that server id stays one row`() {
        // The VM reconciles temp→server before merge; here both optimistic + realtime carry the server id.
        val merged =
            mergeChatMessages(
                rest = emptyList(),
                optimistic = listOf(msg("server-9", sender = viewer)),
                realtime = listOf(msg("server-9", sender = viewer)),
            )
        assertEquals(1, merged.size)
    }

    @Test
    fun `a later redaction inbound flips content to null in place with no second row`() {
        val merged =
            mergeChatMessages(
                rest = listOf(msg("m1", content = "secret")),
                optimistic = emptyList(),
                realtime = listOf(msg("m1", content = null, redacted = true)),
            )
        assertEquals(1, merged.size)
        val row = merged.single()
        assertTrue(row.isRedacted)
        assertNull(row.content)
    }

    @Test
    fun `two messages sharing an identical createdAt order deterministically by id`() {
        val merged =
            mergeChatMessages(
                rest = listOf(msg("b", createdAt = "2026-06-01T10:00:00Z"), msg("a", createdAt = "2026-06-01T10:00:00Z")),
                optimistic = emptyList(),
                realtime = emptyList(),
            )
        assertEquals(listOf("a", "b"), merged.map { it.id }, "tiebreak on id ascending")
    }

    // ---- projection: own/other + redacted + NO PII ----

    @Test
    fun `projection resolves own-vs-other by sender id and drops sender from the row`() {
        val rows =
            chatMessageRows(
                listOf(msg("m1", sender = viewer), msg("m2", sender = "other")),
                viewerId = viewer,
            )
        assertTrue(rows[0].isOwn)
        assertFalse(rows[1].isOwn)
        // ChatMessageRow exposes NO senderId / coordinate — PII cannot leak (structural). Content + a
        // boolean + the message's own id are all that render.
        assertEquals(listOf("m1", "m2"), rows.map { it.id })
    }

    @Test
    fun `a redacted message projects to a null-content row for the placeholder`() {
        val rows = chatMessageRows(listOf(msg("m1", content = "x", redacted = true)), viewerId = viewer)
        assertTrue(rows.single().isRedacted)
        assertNull(rows.single().content)
    }

    // ---- mobile-chat-message-report: affordance-visibility projection + outcome mapping ----

    @Test
    fun `a received non-redacted message is reportable`() {
        val rows = chatMessageRows(listOf(msg("m1", sender = "other")), viewerId = viewer)
        assertTrue(rows.single().isReportable, "the other party's non-redacted message is reportable")
    }

    @Test
    fun `the viewer's own message is not reportable`() {
        val rows = chatMessageRows(listOf(msg("m1", sender = viewer)), viewerId = viewer)
        assertFalse(rows.single().isReportable, "you cannot report your own message")
    }

    @Test
    fun `an optimistic own send is not reportable`() {
        // An optimistic send carries the viewer's own id (senderId == viewer) → isOwn → not reportable,
        // so an un-persisted temp id can never reach the report target_id.
        val rows = chatMessageRows(listOf(msg("temp-0", sender = viewer)), viewerId = viewer)
        assertFalse(rows.single().isReportable)
    }

    @Test
    fun `a redacted received message is not reportable`() {
        val rows = chatMessageRows(listOf(msg("m1", sender = "other", redacted = true)), viewerId = viewer)
        assertFalse(rows.single().isReportable, "an already-moderated message is not reportable")
    }

    @Test
    fun `chatReportMessage folds Submitted and Duplicate into SUCCESS`() {
        assertEquals(ChatReportMessage.SUCCESS, chatReportMessage(ReportOutcome.Submitted))
        assertEquals(ChatReportMessage.SUCCESS, chatReportMessage(ReportOutcome.Duplicate))
    }

    @Test
    fun `chatReportMessage maps RateLimited and NetworkError distinctly`() {
        assertEquals(ChatReportMessage.RATE_LIMITED, chatReportMessage(ReportOutcome.RateLimited(retryAfterSeconds = 60)))
        assertEquals(ChatReportMessage.FAILED, chatReportMessage(ReportOutcome.NetworkError))
    }

    // ---- thread state projection ----

    @Test
    fun `initial load is Loading regardless of outcome`() {
        assertEquals(
            ChatThreadUiState.Loading,
            chatThreadUiState(ChatThreadOutcome.Loaded(emptyList(), null), isInitialLoad = true, rows = emptyList()),
        )
    }

    @Test
    fun `loaded projects to content even when empty`() {
        val state = chatThreadUiState(ChatThreadOutcome.Loaded(emptyList(), null), isInitialLoad = false, rows = emptyList())
        assertTrue(state is ChatThreadUiState.Content)
    }

    @Test
    fun `a history error with no rows is Error but with rows keeps content mounted`() {
        assertEquals(ChatThreadUiState.Error, chatThreadUiState(ChatThreadOutcome.NetworkError, isInitialLoad = false, rows = emptyList()))
        val rows = chatMessageRows(listOf(msg("m1")), viewer)
        assertTrue(chatThreadUiState(ChatThreadOutcome.NetworkError, isInitialLoad = false, rows = rows) is ChatThreadUiState.Content)
    }

    @Test
    fun `session expired maps to redirect`() {
        assertEquals(
            ChatThreadUiState.SessionRedirect,
            chatThreadUiState(ChatThreadOutcome.SessionExpired, isInitialLoad = false, rows = emptyList()),
        )
    }

    // ---- send-bar projection (7.3) ----

    @Test
    fun `send-bar maps each outcome and in-flight`() {
        assertEquals(SendBarState.Sending, sendBarState(outcome = null, inFlight = true))
        assertEquals(SendBarState.Idle, sendBarState(outcome = null, inFlight = false))
        assertEquals(SendBarState.Blocked, sendBarState(SendOutcome.Blocked, inFlight = false))
        assertEquals(SendBarState.TooLong, sendBarState(SendOutcome.TooLong, inFlight = false))
        assertEquals(SendBarState.NetworkRetry, sendBarState(SendOutcome.NetworkError, inFlight = false))
        assertEquals(SendBarState.NetworkRetry, sendBarState(SendOutcome.Error, inFlight = false))
        // Sent + SessionExpired both project to Idle (no send chrome).
        assertEquals(SendBarState.Idle, sendBarState(SendOutcome.SessionExpired, inFlight = false))
    }

    // ---- client send guard ----

    @Test
    fun `empty or whitespace-only content is rejected before any request`() {
        assertFalse(canSubmitChat(""))
        assertFalse(canSubmitChat("   "))
        assertFalse(canSubmitChat("\n\t "))
    }

    @Test
    fun `content at the cap is allowed and over the cap is rejected`() {
        assertTrue(canSubmitChat("a".repeat(MAX_CHAT_CONTENT_CODE_POINTS)))
        assertFalse(canSubmitChat("a".repeat(MAX_CHAT_CONTENT_CODE_POINTS + 1)))
    }
}
