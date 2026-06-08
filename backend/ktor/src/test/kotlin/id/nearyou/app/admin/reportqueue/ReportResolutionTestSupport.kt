package id.nearyou.app.admin.reportqueue

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + read-back helpers specific to the `admin-report-queue-resolution-
 * actions` integration tests (tasks.md Section 4) — the resolution write-back
 * state the base [ReportQueueTestSupport] readers don't cover: the post/reply
 * `is_auto_hidden` toggle, the author's `is_banned`/`suspended_until`/
 * `is_shadow_banned` moderation flags, and the `reports`/`moderation_queue`
 * resolution columns (`reviewed_by`/`resolution`/`resolved_by`/`resolved_at`).
 *
 * Reuses [ReportQueueTestSupport] for the base fixtures (users / posts /
 * replies / chat messages / reports / queue rows / cleanup) and
 * `id.nearyou.app.admin.moderation.UserModerationTestSupport` for the audit +
 * notification readers (the audit `target_id` is the report/queue id; the
 * notification `user_id` is the author). DB-backed; consuming specs are tagged
 * `database`.
 */
object ReportResolutionTestSupport {
    /**
     * Seed a `users` row with controlled moderation flags (a superset of
     * [ReportQueueTestSupport.seedUser], which has no `is_banned` /
     * `suspended_until` / `is_shadow_banned` knobs). Used to seed the offending
     * author with a specific prior state (e.g. already permanently banned for
     * the suspend-guard scenario, or `is_shadow_banned = FALSE` baseline).
     */
    @Suppress("LongParameterList")
    fun seedUser(
        dataSource: DataSource,
        isBanned: Boolean = false,
        suspendedUntil: Instant? = null,
        isShadowBanned: Boolean = false,
        deletedAt: Instant? = null,
        username: String? = null,
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
                ps.setString(2, username ?: "rr_$short")
                ps.setString(3, "Resolution Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "x${short.take(7)}")
                ps.setBoolean(6, isBanned)
                if (suspendedUntil != null) ps.setTimestamp(7, Timestamp.from(suspendedUntil)) else ps.setNull(7, Types.TIMESTAMP)
                ps.setBoolean(8, isShadowBanned)
                if (deletedAt != null) ps.setTimestamp(9, Timestamp.from(deletedAt)) else ps.setNull(9, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Set `is_auto_hidden` on a `post`/`reply` target (to seed the pre-state for
     *  the keep/hide visibility assertions). */
    fun setAutoHidden(
        dataSource: DataSource,
        targetType: String,
        id: UUID,
        hidden: Boolean,
    ) {
        val table = if (targetType == "reply") "post_replies" else "posts"
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE $table SET is_auto_hidden = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, hidden)
                ps.setObject(2, id)
                ps.executeUpdate()
            }
        }
    }

    /** Read `is_auto_hidden` for a `post`/`reply` target. */
    fun loadAutoHidden(
        dataSource: DataSource,
        targetType: String,
        id: UUID,
    ): Boolean {
        val table = if (targetType == "reply") "post_replies" else "posts"
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_auto_hidden FROM $table WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_auto_hidden")
                }
            }
        }
    }

    data class UserModState(
        val isBanned: Boolean,
        val suspendedUntil: Instant?,
        val isShadowBanned: Boolean,
    )

    fun loadUserModeration(
        dataSource: DataSource,
        id: UUID,
    ): UserModState =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned, suspended_until, is_shadow_banned FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    UserModState(
                        isBanned = rs.getBoolean("is_banned"),
                        suspendedUntil = rs.getTimestamp("suspended_until")?.toInstant(),
                        isShadowBanned = rs.getBoolean("is_shadow_banned"),
                    )
                }
            }
        }

    data class ReportState(
        val status: String,
        val reviewedBy: UUID?,
        val reviewedAt: Instant?,
    )

    fun loadReport(
        dataSource: DataSource,
        id: UUID,
    ): ReportState =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status, reviewed_by, reviewed_at FROM reports WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    ReportState(
                        status = rs.getString("status"),
                        reviewedBy = rs.getObject("reviewed_by", UUID::class.java),
                        reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant(),
                    )
                }
            }
        }

    data class QueueState(
        val status: String,
        val resolution: String?,
        val resolvedBy: UUID?,
        val resolvedAt: Instant?,
    )

    fun loadQueue(
        dataSource: DataSource,
        id: UUID,
    ): QueueState =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT status, resolution, resolved_by, resolved_at FROM moderation_queue WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    QueueState(
                        status = rs.getString("status"),
                        resolution = rs.getString("resolution"),
                        resolvedBy = rs.getObject("resolved_by", UUID::class.java),
                        resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
                    )
                }
            }
        }

    /** Count `reports` rows in a given status for a `(target_type, target_id)`
     *  (the no-cascade-to-siblings guard). */
    fun countReportsForTarget(
        dataSource: DataSource,
        targetType: String,
        targetId: UUID,
        status: String,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM reports WHERE target_type = ? AND target_id = ? AND status = ?",
            ).use { ps ->
                ps.setString(1, targetType)
                ps.setObject(2, targetId)
                ps.setString(3, status)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
