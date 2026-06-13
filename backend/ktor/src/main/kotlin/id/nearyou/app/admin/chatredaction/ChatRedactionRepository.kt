package id.nearyou.app.admin.chatredaction

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Read + write repository for the admin chat-message redaction surface
 * (`admin-chat-message-redaction`). Backs `GET /admin/chat-messages/{id}` (the
 * ±2-context confirmation page) and `POST /admin/chat-messages/{id}/redact` (the
 * idempotent atomic redaction write).
 *
 * Mirrors [id.nearyou.app.admin.reportqueue.ReportResolutionRepository]'s
 * transaction shape verbatim (the established admin-write pattern — no new
 * pattern): one transaction, a `SELECT … FOR UPDATE` lock that serializes the
 * concurrent-double-redact race, a `RETURNING`-equivalent guarded UPDATE, and
 * the participant notification(s) + the immutable `admin_actions_log` row written
 * in the SAME transaction.
 *
 *  - All queries are parameterized JDBC `PreparedStatement`s — never string-
 *    interpolated.
 *  - Raw `FROM`/`UPDATE` on `chat_messages` / `conversation_participants` /
 *    `notifications` is permitted here: this is the admin module (package
 *    `id.nearyou.app.admin.*`), exempt from the `RawFromPostsRule` /
 *    `BlockExclusionJoinRule` Detekt rules (admins moderate everything; per
 *    `CLAUDE.md` § Critical invariants + `docs/04` § Admin Panel Data Access).
 *    No `visible_*` view or block-exclusion join applies.
 *  - The `chat_message_redacted` notification is written via a RAW in-tx
 *    `INSERT INTO notifications` (the shipped admin notification pattern, like
 *    `account_action_applied`) with `actor_user_id` NULL — NOT the social
 *    `NotificationEmitter`; so no block-suppression applies and both active
 *    participants are notified. In-app feed only (no FCM).
 *  - Redaction is a destructive action: gated by AND counted toward the shared
 *    20/hr cap (`DestructiveActionRateLimiter`); the redaction's own
 *    `admin_chat_redaction` audit row is in the limiter's destructive set.
 */
class ChatRedactionRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: DestructiveActionRateLimiter,
) {
    /**
     * Load the redaction confirmation page for [messageId]: the target message
     * plus up to 2 messages immediately before and 2 immediately after it in the
     * same conversation (the limited-disclosure ±2 moderation-context window,
     * design D5). Returns null when no `chat_messages` row matches (→ 404). The
     * window clamps naturally at conversation boundaries.
     */
    fun loadRedactionPage(messageId: UUID): ChatRedactionPageData? =
        dataSource.connection.use { conn ->
            val target = loadMessage(conn, messageId) ?: return null
            val earlier = loadNeighbors(conn, target, before = true).asReversed()
            val later = loadNeighbors(conn, target, before = false)
            val window =
                buildList {
                    addAll(earlier.map { it.toContext(isTarget = false) })
                    add(target.toContext(isTarget = true))
                    addAll(later.map { it.toContext(isTarget = false) })
                }
            ChatRedactionPageData(
                messageId = target.id,
                conversationId = target.conversationId,
                targetIsRedacted = target.redactedAt != null,
                context = window,
            )
        }

    /**
     * Apply the redaction in ONE transaction (design D3/D4): lock the row, no-op
     * if already redacted or not found, gate on the destructive cap, then set the
     * redaction flags + write a `chat_message_redacted` notification per active
     * participant + one `admin_chat_redaction` audit row — commit-or-rollback
     * together. Idempotent: the `WHERE redacted_at IS NULL` guard makes a
     * re-redaction a no-op that preserves the original flags.
     */
    fun redact(
        messageId: UUID,
        actingAdminId: UUID,
        reason: String,
        ip: String,
        userAgent: String?,
    ): RedactionOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val locked =
                    lockMessage(conn, messageId) ?: run {
                        conn.rollback()
                        return RedactionOutcome.NotFound
                    }
                if (locked.redactedAt != null) {
                    conn.rollback()
                    return RedactionOutcome.AlreadyRedacted
                }
                // Destructive-action cap (admin-destructive-action-rate-limit):
                // checked only for a genuinely-appliable redaction (after the
                // not-found / already-redacted no-ops), so a no-op never consumes
                // the cap. At/over the cap → reject, nothing written.
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return RedactionOutcome.RateLimited
                }

                conn.prepareStatement(
                    "UPDATE chat_messages SET redacted_at = NOW(), redacted_by = ?, redaction_reason = ? " +
                        "WHERE id = ? AND redacted_at IS NULL",
                ).use { ps ->
                    ps.setObject(1, actingAdminId)
                    ps.setString(2, reason)
                    ps.setObject(3, messageId)
                    ps.executeUpdate()
                }

                insertParticipantNotifications(conn, locked.conversationId, messageId)

                auditLogger.logChatRedaction(
                    conn = conn,
                    adminId = actingAdminId,
                    messageId = messageId,
                    reason = reason,
                    beforeState =
                        buildJsonObject {
                            put("content", locked.content?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("had_embed", JsonPrimitive(locked.hasEmbed))
                        },
                    afterState = buildJsonObject { put("redacted", JsonPrimitive(true)) },
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                RedactionOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /** Load a single message (own-connection, read path). */
    private fun loadMessage(
        conn: Connection,
        messageId: UUID,
    ): MessageRow? =
        conn.prepareStatement(
            "SELECT id, conversation_id, sender_id, content, embedded_post_snapshot, " +
                "created_at, redacted_at FROM chat_messages WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, messageId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toMessageRow() else null }
        }

    /** Up to 2 neighbors strictly [before] / after the target, ordered outward
     *  from the target by `(created_at, id)` so the closest 2 are selected. */
    private fun loadNeighbors(
        conn: Connection,
        target: MessageRow,
        before: Boolean,
    ): List<MessageRow> {
        val cmp = if (before) "<" else ">"
        val dir = if (before) "DESC" else "ASC"
        val sql =
            "SELECT id, conversation_id, sender_id, content, embedded_post_snapshot, created_at, redacted_at " +
                "FROM chat_messages " +
                "WHERE conversation_id = ? AND (created_at, id) $cmp (?, ?) " +
                "ORDER BY created_at $dir, id $dir LIMIT 2"
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, target.conversationId)
            ps.setObject(2, java.sql.Timestamp.from(target.createdAt))
            ps.setObject(3, target.id)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toMessageRow()) }
            }
        }
    }

    /** `SELECT … FOR UPDATE` the target row, locking it so concurrent redactors
     *  serialize (the loser sees `redacted_at IS NOT NULL` → AlreadyRedacted). */
    private fun lockMessage(
        conn: Connection,
        messageId: UUID,
    ): MessageRow? =
        conn.prepareStatement(
            "SELECT id, conversation_id, sender_id, content, embedded_post_snapshot, " +
                "created_at, redacted_at FROM chat_messages WHERE id = ? FOR UPDATE",
        ).use { ps ->
            ps.setObject(1, messageId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toMessageRow() else null }
        }

    /**
     * Write a `chat_message_redacted` notification for EACH active participant
     * (`left_at IS NULL`) of the conversation, via a raw in-tx INSERT (the shipped
     * admin notification pattern). `actor_user_id` NULL (system-originated → no
     * block-suppression); `target_type = 'message'`, `target_id` = the message id
     * (the canonical addressing pair); `body_data = {conversation_id}` only (no
     * content/reason/message_id — the catalog "don't duplicate target_id" rule).
     */
    private fun insertParticipantNotifications(
        conn: Connection,
        conversationId: UUID,
        messageId: UUID,
    ) {
        val bodyData = buildJsonObject { put("conversation_id", JsonPrimitive(conversationId.toString())) }
        conn.prepareStatement(
            "INSERT INTO notifications (user_id, type, target_type, target_id, body_data) " +
                "SELECT cp.user_id, 'chat_message_redacted', 'message', ?, ?::jsonb " +
                "FROM conversation_participants cp " +
                "WHERE cp.conversation_id = ? AND cp.left_at IS NULL",
        ).use { ps ->
            ps.setObject(1, messageId)
            ps.setString(2, json.encodeToString(bodyData))
            ps.setObject(3, conversationId)
            ps.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toMessageRow(): MessageRow =
        MessageRow(
            id = getObject("id", UUID::class.java),
            conversationId = getObject("conversation_id", UUID::class.java),
            senderId = getObject("sender_id", UUID::class.java),
            content = getString("content"),
            hasEmbed = getString("embedded_post_snapshot") != null,
            createdAt = getTimestamp("created_at").toInstant(),
            redactedAt = getTimestamp("redacted_at")?.toInstant(),
        )

    private fun MessageRow.toContext(isTarget: Boolean): ChatRedactionContextMessage =
        ChatRedactionContextMessage(
            id = id,
            senderId = senderId,
            // Mask content for an already-redacted message (never surface the
            // original text in the admin context window either — D5).
            content = if (redactedAt != null) null else content,
            isRedacted = redactedAt != null,
            hasEmbed = hasEmbed,
            createdAt = createdAt,
            isTarget = isTarget,
        )

    /** A loaded `chat_messages` row's redaction-relevant columns. */
    private data class MessageRow(
        val id: UUID,
        val conversationId: UUID,
        val senderId: UUID,
        val content: String?,
        val hasEmbed: Boolean,
        val createdAt: Instant,
        val redactedAt: Instant?,
    )

    private companion object {
        val json = Json { encodeDefaults = false }
    }
}

/** A message rendered in the ±2 context window of the redaction page. */
data class ChatRedactionContextMessage(
    val id: UUID,
    val senderId: UUID,
    val content: String?,
    val isRedacted: Boolean,
    val hasEmbed: Boolean,
    val createdAt: Instant,
    val isTarget: Boolean,
)

/** The redaction-page payload: the target + its ±2 chronological context window. */
data class ChatRedactionPageData(
    val messageId: UUID,
    val conversationId: UUID,
    val targetIsRedacted: Boolean,
    val context: List<ChatRedactionContextMessage>,
)

/** Typed result of [ChatRedactionRepository.redact]. */
sealed interface RedactionOutcome {
    /** The message was redacted + notifications + one audit row written. */
    data object Applied : RedactionOutcome

    /** The message was already redacted — benign no-op, no second write. */
    data object AlreadyRedacted : RedactionOutcome

    /** No message resolves to the id. */
    data object NotFound : RedactionOutcome

    /** Acting admin is at/over the destructive-action cap; nothing written. */
    data object RateLimited : RedactionOutcome
}
