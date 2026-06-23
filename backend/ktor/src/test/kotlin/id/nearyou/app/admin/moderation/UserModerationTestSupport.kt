package id.nearyou.app.admin.moderation

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the `admin-user-moderation` integration
 * tests (tasks.md Section 8). Seeds `public.users` rows with controlled
 * `is_banned` / `suspended_until` / `deleted_at`, and reads back the
 * target-scoped `admin_actions_log` + `notifications` rows the suspend / unban
 * transactions write.
 *
 * Admin rows themselves are seeded via `AdminAuthTestSupport.seedAdmin(...)`
 * (the audit `admin_id` FK requires a real `admin_users` row); this helper
 * owns only the user-side fixtures + the target-scoped readers. DB-backed;
 * the consuming specs are tagged `database`.
 */
object UserModerationTestSupport {
    fun seedUser(
        dataSource: DataSource,
        isBanned: Boolean = false,
        suspendedUntil: Instant? = null,
        deletedAt: Instant? = null,
        username: String? = null,
        isShadowBanned: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    is_banned, suspended_until, is_shadow_banned, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "mod_$short")
                ps.setString(3, "Moderation Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "m${short.take(7)}")
                ps.setBoolean(6, isBanned)
                if (suspendedUntil != null) {
                    ps.setTimestamp(7, Timestamp.from(suspendedUntil))
                } else {
                    ps.setNull(7, Types.TIMESTAMP)
                }
                ps.setBoolean(8, isShadowBanned)
                if (deletedAt != null) {
                    ps.setTimestamp(9, Timestamp.from(deletedAt))
                } else {
                    ps.setNull(9, Types.TIMESTAMP)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    data class UserRow(
        val isBanned: Boolean,
        val suspendedUntil: Instant?,
        val deletedAt: Instant?,
        val isShadowBanned: Boolean = false,
    )

    fun loadUser(
        dataSource: DataSource,
        id: UUID,
    ): UserRow =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT is_banned, suspended_until, is_shadow_banned, deleted_at FROM users WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    UserRow(
                        isBanned = rs.getBoolean("is_banned"),
                        suspendedUntil = rs.getTimestamp("suspended_until")?.toInstant(),
                        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
                        isShadowBanned = rs.getBoolean("is_shadow_banned"),
                    )
                }
            }
        }

    /** True iff a `users` row with [id] still exists (used by the
     *  SQL-metacharacter-lookup safety assertion). */
    fun userExists(
        dataSource: DataSource,
        id: UUID,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    data class AuditRow(
        val adminId: UUID,
        val actionType: String,
        val targetType: String?,
        val targetId: String?,
        val reason: String?,
        val beforeStateJson: String?,
        val afterStateJson: String?,
        val ip: String?,
        val userAgent: String?,
    )

    fun auditRowsForTarget(
        dataSource: DataSource,
        targetId: UUID,
        actionType: String? = null,
    ): List<AuditRow> =
        dataSource.connection.use { conn ->
            val sql =
                buildString {
                    append("SELECT admin_id, action_type, target_type, target_id, reason, ")
                    // host(ip) yields the bare address (Postgres inet::text would append
                    // the /32 mask) so the test compares against the literal client IP.
                    append("before_state::text AS bs, after_state::text AS afs, host(ip) AS ip, user_agent ")
                    append("FROM admin_actions_log WHERE target_id = ?")
                    if (actionType != null) append(" AND action_type = ?")
                    append(" ORDER BY created_at DESC")
                }
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, targetId.toString())
                if (actionType != null) ps.setString(2, actionType)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AuditRow(
                                    adminId = rs.getObject("admin_id", UUID::class.java),
                                    actionType = rs.getString("action_type"),
                                    targetType = rs.getString("target_type"),
                                    targetId = rs.getString("target_id"),
                                    reason = rs.getString("reason"),
                                    beforeStateJson = rs.getString("bs"),
                                    afterStateJson = rs.getString("afs"),
                                    ip = rs.getString("ip"),
                                    userAgent = rs.getString("user_agent"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    data class NotificationRow(
        val type: String,
        val bodyDataJson: String?,
        val actorUserId: UUID?,
    )

    fun notificationsForUser(
        dataSource: DataSource,
        userId: UUID,
    ): List<NotificationRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT type, body_data::text AS bd, actor_user_id FROM notifications WHERE user_id = ? ORDER BY created_at DESC",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                NotificationRow(
                                    type = rs.getString("type"),
                                    bodyDataJson = rs.getString("bd"),
                                    actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** Delete the target-scoped audit rows (no FK to `users`, so they do NOT
     *  cascade) then the users (notifications cascade via FK). */
    fun cleanupUser(
        dataSource: DataSource,
        vararg userIds: UUID,
    ) {
        if (userIds.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM admin_actions_log WHERE target_id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("text", userIds.map { it.toString() }.toTypedArray()))
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", userIds.toList().toTypedArray()))
                ps.executeUpdate()
            }
        }
    }
}
