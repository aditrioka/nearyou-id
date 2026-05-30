package id.nearyou.app.admin.actionslog

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helper for `admin_actions_log` rows in the audit-log-viewer
 * integration tests (`admin-actions-log-viewer` tasks.md Sections 6 + 7).
 *
 * Admin rows themselves are seeded via `AdminAuthTestSupport.seedAdmin(...)`;
 * this helper inserts audit rows referencing a seeded admin with fully
 * controlled `created_at` + filter columns so the keyset-pagination and
 * filtering assertions are deterministic.
 *
 * `created_at` is bound as an absolute [Instant] (constructed in the tests
 * with explicit UTC instants) via `Timestamp.from`, matching the production
 * `AdminActionsLogRepository` binding — seed + query share the JVM, so the
 * boundary comparisons are deterministic regardless of host timezone
 * (proposal-review test-N4).
 */
object ActionsLogTestSupport {
    @Suppress("LongParameterList")
    fun seedActionLog(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        createdAt: Instant,
        targetType: String? = null,
        targetId: String? = null,
        reason: String? = null,
        beforeStateJson: String? = null,
        afterStateJson: String? = null,
        ip: String? = null,
        userAgent: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_actions_log (
                    id, admin_id, action_type, target_type, target_id, reason,
                    before_state, after_state, ip, user_agent, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, adminId)
                ps.setString(3, actionType)
                ps.setString(4, targetType)
                ps.setString(5, targetId)
                ps.setString(6, reason)
                ps.setString(7, beforeStateJson)
                ps.setString(8, afterStateJson)
                ps.setString(9, ip)
                ps.setString(10, userAgent)
                ps.setObject(11, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Delete all audit rows for an admin (sessions + admin row are cleaned by AdminAuthTestSupport.cleanupAdmin). */
    fun deleteActionLogs(
        dataSource: DataSource,
        adminId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM admin_actions_log WHERE admin_id = ?").use { ps ->
                ps.setObject(1, adminId)
                ps.executeUpdate()
            }
        }
    }
}
