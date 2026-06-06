package id.nearyou.app.admin.reportqueue

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the `admin-report-queue` integration tests
 * (tasks.md Section 5). Seeds `reports`, optional `moderation_queue` rows, and
 * the four polymorphic target shapes (`users` / `posts` / `post_replies` /
 * `chat_messages`) so the keyset, filter, queue-context, and deep-link
 * resolution assertions are deterministic.
 *
 * Admin rows themselves are seeded via `AdminAuthTestSupport.seedAdmin(...)`;
 * this helper owns only the report-side fixtures + the count readers used by
 * the read-only / mutation negative guards. DB-backed; the consuming specs are
 * tagged `database`. `created_at` is bound as an absolute [Instant] via
 * `Timestamp.from` (matching the production repository binding) so the
 * date-boundary comparisons are tz-deterministic across CI runners.
 */
object ReportQueueTestSupport {
    fun seedUser(
        dataSource: DataSource,
        username: String? = null,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "rq_$short")
                ps.setString(3, "Report Queue Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${short.take(7)}")
                if (deletedAt != null) ps.setTimestamp(6, Timestamp.from(deletedAt)) else ps.setNull(6, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPost(
        dataSource: DataSource,
        authorId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "post-${id.toString().take(6)}")
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedReply(
        dataSource: DataSource,
        postId: UUID,
        authorId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (id, post_id, author_id, content) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, postId)
                ps.setObject(3, authorId)
                ps.setString(4, "reply-${id.toString().take(6)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a conversation owned by [senderId] + a valid message, returning the message id. */
    fun seedChatMessage(
        dataSource: DataSource,
        senderId: UUID,
    ): UUID {
        val convId = UUID.randomUUID()
        val msgId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO conversations (id, created_by) VALUES (?, ?)").use { ps ->
                ps.setObject(1, convId)
                ps.setObject(2, senderId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, convId)
                ps.setObject(2, senderId)
                ps.setInt(3, 1)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO chat_messages (id, conversation_id, sender_id, content) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, msgId)
                ps.setObject(2, convId)
                ps.setObject(3, senderId)
                ps.setString(4, "chat-${msgId.toString().take(6)}")
                ps.executeUpdate()
            }
        }
        return msgId
    }

    @Suppress("LongParameterList")
    fun seedReport(
        dataSource: DataSource,
        reporterId: UUID,
        targetType: String,
        targetId: UUID,
        createdAt: Instant,
        reasonCategory: String = "spam",
        reasonNote: String? = null,
        status: String = "pending",
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO reports (
                    id, reporter_id, target_type, target_id, reason_category, reason_note, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, reporterId)
                ps.setString(3, targetType)
                ps.setObject(4, targetId)
                ps.setString(5, reasonCategory)
                if (reasonNote != null) ps.setString(6, reasonNote) else ps.setNull(6, Types.VARCHAR)
                ps.setString(7, status)
                ps.setObject(8, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    @Suppress("LongParameterList")
    fun seedQueueRow(
        dataSource: DataSource,
        targetType: String,
        targetId: UUID,
        trigger: String = "auto_hide_3_reports",
        priority: Int = 5,
        status: String = "pending",
        createdAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO moderation_queue (id, target_type, target_id, trigger, priority, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, NOW()))
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, targetType)
                ps.setObject(3, targetId)
                ps.setString(4, trigger)
                ps.setInt(5, priority)
                ps.setString(6, status)
                if (createdAt != null) ps.setTimestamp(7, Timestamp.from(createdAt)) else ps.setNull(7, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Hard-delete a `users` row (simulates a hard-deleted target). Reports/queue
     *  rows referencing it by `target_id` carry no FK, so they survive. */
    fun hardDeleteUser(
        dataSource: DataSource,
        id: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }

    /** True iff the `reports` table still exists + is queryable (injection guard). */
    fun reportsTableExists(dataSource: DataSource): Boolean =
        dataSource.connection.use { conn ->
            runCatching {
                conn.prepareStatement("SELECT 1 FROM reports LIMIT 1").use { ps ->
                    ps.executeQuery().use { true }
                }
            }.getOrDefault(false)
        }

    fun totalReportCount(dataSource: DataSource): Long = scalarCount(dataSource, "SELECT COUNT(*) FROM reports")

    fun totalQueueCount(dataSource: DataSource): Long = scalarCount(dataSource, "SELECT COUNT(*) FROM moderation_queue")

    private fun scalarCount(
        dataSource: DataSource,
        sql: String,
    ): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /**
     * FK-safe teardown: per seeded id, sweep every table that could reference it
     * (mirrors `ReportEndpointsTest.cleanup`). Pass every seeded `users` id and
     * every seeded content-target id (post / reply / chat_message / user) —
     * reports + queue rows keyed on those targets are swept here, and reports
     * also cascade on reporter delete.
     */
    fun cleanup(
        dataSource: DataSource,
        ids: Collection<UUID>,
    ) {
        if (ids.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            ids.forEach { id ->
                runUpdate(conn, "DELETE FROM moderation_queue WHERE target_id = ?", id)
                runUpdate(conn, "DELETE FROM reports WHERE target_id = ? OR reporter_id = ?", id, id)
                runUpdate(conn, "DELETE FROM post_replies WHERE post_id = ? OR id = ? OR author_id = ?", id, id, id)
                runUpdate(conn, "DELETE FROM chat_messages WHERE id = ? OR sender_id = ?", id, id)
                runUpdate(conn, "DELETE FROM posts WHERE id = ? OR author_id = ?", id, id)
                runUpdate(conn, "DELETE FROM conversation_participants WHERE user_id = ?", id)
                runUpdate(conn, "DELETE FROM conversations WHERE created_by = ?", id)
                runUpdate(conn, "DELETE FROM users WHERE id = ?", id)
            }
        }
    }

    private fun runUpdate(
        conn: java.sql.Connection,
        sql: String,
        vararg args: UUID,
    ) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
    }
}
