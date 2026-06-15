package id.nearyou.app.admin.chatredaction

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + read-back helpers for the `admin-chat-message-redaction` integration
 * tests: a conversation with two active participants (+ optional soft-left
 * participant), N ordered messages (for the ±2 context window + boundary), an
 * embedded-post-only message (content NULL), and a redaction-state reader.
 *
 * `users` rows are seeded here; the offending-message author + the other
 * participant are plain users. Admin rows come from `AdminAuthTestSupport`. The
 * notification + audit readers are reused from `UserModerationTestSupport`
 * (`notificationsForUser`, `auditRowsForTarget`). Teardown reuses
 * `ReportQueueTestSupport.cleanup` (sweeps chat_messages / conversation_participants
 * / conversations / users by id; notifications cascade on user delete). DB-backed;
 * consuming specs are tagged `database`.
 */
object ChatRedactionTestSupport {
    fun seedUser(
        dataSource: DataSource,
        username: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "cr_$short")
                ps.setString(3, "Chat Redaction Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "c${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a conversation created by [creatorId]. */
    fun seedConversation(
        dataSource: DataSource,
        creatorId: UUID,
    ): UUID {
        val convId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO conversations (id, created_by) VALUES (?, ?)").use { ps ->
                ps.setObject(1, convId)
                ps.setObject(2, creatorId)
                ps.executeUpdate()
            }
        }
        return convId
    }

    /** Add a participant in [slot] (1 or 2); [left] marks it soft-left (left_at set). */
    fun addParticipant(
        dataSource: DataSource,
        conversationId: UUID,
        userId: UUID,
        slot: Int,
        left: Boolean = false,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO conversation_participants (conversation_id, user_id, slot, left_at) " +
                    "VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, conversationId)
                ps.setObject(2, userId)
                ps.setInt(3, slot)
                if (left) ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))) else ps.setNull(4, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Seed a message in [conversationId] from [senderId]. [createdAt] controls
     * ordering for the ±2 window. When [embeddedOnly] is true, `content` is NULL
     * and `embedded_post_snapshot` is a non-null JSONB (schema-permitted by the
     * V15 empty-message CHECK).
     */
    @Suppress("LongParameterList")
    fun seedMessage(
        dataSource: DataSource,
        conversationId: UUID,
        senderId: UUID,
        content: String? = "msg-body",
        createdAt: Instant = Instant.parse("2026-06-11T08:30:00Z"),
        embeddedOnly: Boolean = false,
    ): UUID {
        val msgId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO chat_messages (id, conversation_id, sender_id, content, embedded_post_snapshot, created_at) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
            ).use { ps ->
                ps.setObject(1, msgId)
                ps.setObject(2, conversationId)
                ps.setObject(3, senderId)
                if (embeddedOnly) ps.setNull(4, Types.VARCHAR) else ps.setString(4, content)
                if (embeddedOnly) ps.setString(5, """{"post_id":"seed","content":"snap"}""") else ps.setNull(5, Types.OTHER)
                ps.setTimestamp(6, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
        return msgId
    }

    data class RedactionState(
        val redactedAt: Instant?,
        val redactedBy: UUID?,
        val redactionReason: String?,
    )

    fun loadRedaction(
        dataSource: DataSource,
        messageId: UUID,
    ): RedactionState =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT redacted_at, redacted_by, redaction_reason FROM chat_messages WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    RedactionState(
                        redactedAt = rs.getTimestamp("redacted_at")?.toInstant(),
                        redactedBy = rs.getObject("redacted_by", UUID::class.java),
                        redactionReason = rs.getString("redaction_reason"),
                    )
                }
            }
        }

    /** Pre-seed [count] destructive `admin_chat_redaction` audit rows for [adminId]
     *  in the trailing hour, so a redaction sees the cap (mirrors the report-queue
     *  rate-limit test seeding). Each row needs a distinct text target_id. */
    fun seedRedactionAuditRows(
        dataSource: DataSource,
        adminId: UUID,
        count: Int,
    ) {
        dataSource.connection.use { conn ->
            repeat(count) {
                conn.prepareStatement(
                    "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id) " +
                        "VALUES (?, 'admin_chat_redaction', 'chat_message', ?)",
                ).use { ps ->
                    ps.setObject(1, adminId)
                    ps.setString(2, UUID.randomUUID().toString())
                    ps.executeUpdate()
                }
            }
        }
    }
}
