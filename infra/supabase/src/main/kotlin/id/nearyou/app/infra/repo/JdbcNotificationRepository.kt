package id.nearyou.app.infra.repo

import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.NotificationType
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [NotificationRepository]. SQL lives here so the
 * interface in `:core:data` stays DB-agnostic.
 *
 * Write-side methods accept a [Connection]; read-side methods open their own
 * connection from [dataSource]. No literals in this file touch `posts`,
 * `post_replies`, `users`, or `chat_messages` — the only cross-table read is
 * [isBlockedBetween]'s `SELECT 1 FROM user_blocks` existence check, which is
 * outside the `RawFromPostsRule` protected set.
 */
class JdbcNotificationRepository(
    private val dataSource: DataSource,
) : NotificationRepository {
    override fun insert(
        conn: Connection,
        recipientId: UUID,
        type: NotificationType,
        actorUserId: UUID?,
        targetType: String?,
        targetId: UUID?,
        bodyDataJson: String,
    ): UUID {
        val jsonb =
            PGobject().apply {
                this.type = "jsonb"
                this.value = bodyDataJson
            }
        conn.prepareStatement(
            """
            INSERT INTO notifications (user_id, type, actor_user_id, target_type, target_id, body_data)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, recipientId)
            ps.setString(2, type.wire)
            if (actorUserId != null) ps.setObject(3, actorUserId) else ps.setNull(3, java.sql.Types.OTHER)
            if (targetType != null) ps.setString(4, targetType) else ps.setNull(4, java.sql.Types.VARCHAR)
            if (targetId != null) ps.setObject(5, targetId) else ps.setNull(5, java.sql.Types.OTHER)
            ps.setObject(6, jsonb)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.getObject("id", UUID::class.java)
            }
        }
    }

    override fun isBlockedBetween(
        conn: Connection,
        userA: UUID,
        userB: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM user_blocks
             WHERE (blocker_id = ? AND blocked_id = ?)
                OR (blocker_id = ? AND blocked_id = ?)
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userA)
            ps.setObject(2, userB)
            ps.setObject(3, userB)
            ps.setObject(4, userA)
            ps.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    override fun listByUser(
        userId: UUID,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        unreadOnly: Boolean,
        limit: Int,
    ): List<NotificationRow> {
        val sql =
            buildString {
                append(
                    """
                    SELECT id, user_id, type, actor_user_id, target_type, target_id,
                           body_data::text AS body_data_json, created_at, read_at
                      FROM notifications
                     WHERE user_id = ?
                    """.trimIndent(),
                )
                if (unreadOnly) append("\n   AND read_at IS NULL")
                if (cursorCreatedAt != null && cursorId != null) {
                    append("\n   AND (created_at, id) < (?, ?)")
                }
                append("\n ORDER BY created_at DESC, id DESC")
                append("\n LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, userId)
                if (cursorCreatedAt != null && cursorId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorId)
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<NotificationRow>()
                    while (rs.next()) {
                        val row = rs.toRowOrNull()
                        if (row != null) out += row
                    }
                    return out
                }
            }
        }
    }

    override fun countUnread(userId: UUID): Long {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun markRead(
        userId: UUID,
        notificationId: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE notifications
                   SET read_at = NOW()
                 WHERE id = ? AND user_id = ? AND read_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, notificationId)
                ps.setObject(2, userId)
                return ps.executeUpdate()
            }
        }
    }

    override fun markAllRead(userId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE notifications SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL",
            ).use { ps ->
                ps.setObject(1, userId)
                return ps.executeUpdate()
            }
        }
    }

    override fun existsForUser(
        userId: UUID,
        notificationId: UUID,
    ): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM notifications WHERE id = ? AND user_id = ? LIMIT 1",
            ).use { ps ->
                ps.setObject(1, notificationId)
                ps.setObject(2, userId)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    override fun findById(notificationId: UUID): NotificationRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, type, actor_user_id, target_type, target_id,
                       body_data::text AS body_data_json, created_at, read_at
                  FROM notifications
                 WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, notificationId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toRowOrNull()
                }
            }
        }
    }

    private fun ResultSet.toRowOrNull(): NotificationRow? {
        val rawType = getString("type")
        val type = NotificationType.fromWireOrNull(rawType)
        if (type == null) {
            val id = getObject("id", UUID::class.java)
            log.warn(
                "event=notification_row_skipped reason=unknown_type id={} type={}",
                id,
                rawType,
            )
            return null
        }
        return NotificationRow(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            type = type,
            actorUserId = getObject("actor_user_id", UUID::class.java),
            targetType = getString("target_type"),
            targetId = getObject("target_id", UUID::class.java),
            bodyDataJson = getString("body_data_json") ?: "{}",
            createdAt = getTimestamp("created_at").toInstant(),
            readAt = getTimestamp("read_at")?.toInstant(),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcNotificationRepository::class.java)
    }
}
