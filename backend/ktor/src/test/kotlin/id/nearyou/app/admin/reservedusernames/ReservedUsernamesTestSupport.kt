package id.nearyou.app.admin.reservedusernames

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + read helpers for the Reserved Usernames Editor integration tests
 * (`admin-reserved-usernames-editor` tasks.md Section 5).
 *
 * `reserved_usernames` ships a permanent `seed_system` baseline from V3, so
 * tests NEVER delete seed rows — they create `admin_added` rows (tracked by the
 * caller and removed in `afterEach` via [deleteAdminAdded]) and seed
 * `admin_actions_log` rows for the rate-limit substrate (removed via
 * `AdminAuthTestSupport.cleanupAdmin`). All inserts are parameterized.
 */
object ReservedUsernamesTestSupport {
    /** A known `seed_system` username present from the V3 seed (for seed-protection tests). */
    const val SEED_USERNAME = "admin"

    /** Insert an `admin_added` (default) reserved row; optionally at a fixed instant. */
    fun seed(
        dataSource: DataSource,
        username: String,
        reason: String = "test reason",
        source: String = "admin_added",
        createdAt: Instant? = null,
    ) {
        dataSource.connection.use { conn ->
            if (createdAt == null) {
                conn.prepareStatement(
                    "INSERT INTO reserved_usernames (username, reason, source) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, username)
                    ps.setString(2, reason)
                    ps.setString(3, source)
                    ps.executeUpdate()
                }
            } else {
                conn.prepareStatement(
                    "INSERT INTO reserved_usernames (username, reason, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, username)
                    ps.setString(2, reason)
                    ps.setString(3, source)
                    ps.setObject(4, Timestamp.from(createdAt))
                    ps.setObject(5, Timestamp.from(createdAt))
                    ps.executeUpdate()
                }
            }
        }
    }

    /** Remove an `admin_added` row (NEVER a seed_system row). Safe in afterEach. */
    fun deleteAdminAdded(
        dataSource: DataSource,
        username: String,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM reserved_usernames WHERE username = ? AND source = 'admin_added'").use { ps ->
                ps.setString(1, username)
                ps.executeUpdate()
            }
        }
    }

    fun exists(
        dataSource: DataSource,
        username: String,
    ): Boolean = reasonOf(dataSource, username) != null

    fun reasonOf(
        dataSource: DataSource,
        username: String,
    ): String? = column(dataSource, username, "reason")

    fun sourceOf(
        dataSource: DataSource,
        username: String,
    ): String? = column(dataSource, username, "source")

    fun updatedAtOf(
        dataSource: DataSource,
        username: String,
    ): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT updated_at FROM reserved_usernames WHERE username = ?").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("updated_at")?.toInstant() else null }
            }
        }

    private fun column(
        dataSource: DataSource,
        username: String,
        col: String,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT $col FROM reserved_usernames WHERE username = ?").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(col) else null }
            }
        }

    /** Full audit rows for an admin (newest-first) — includes `target_id` + the
     *  before/after JSONB as text, which `AdminAuthTestSupport.latestAuditRows`
     *  does not surface (needed for the target_id + state-shape assertions). */
    fun auditRows(
        dataSource: DataSource,
        adminId: UUID,
    ): List<ReservedAuditRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT action_type, target_type, target_id, before_state::text AS bs, after_state::text AS afs " +
                    "FROM admin_actions_log WHERE admin_id = ? ORDER BY created_at DESC",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ReservedAuditRow(
                                    actionType = rs.getString("action_type"),
                                    targetType = rs.getString("target_type"),
                                    targetId = rs.getString("target_id"),
                                    beforeState = rs.getString("bs"),
                                    afterState = rs.getString("afs"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    data class ReservedAuditRow(
        val actionType: String,
        val targetType: String?,
        val targetId: String?,
        val beforeState: String?,
        val afterState: String?,
    )

    /**
     * Seed one `admin_actions_log` row of a reserved-username action type with a
     * controlled trailing-hour age — for the rate-limit COUNT substrate (mirrors
     * the destructive-limiter test's `insertAudit`).
     */
    fun seedReservedAudit(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String = "reserved_username_added",
        ageMinutes: Int = 1,
        username: String = "x",
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at) " +
                    "VALUES (?, ?, 'reserved_username', ?, NOW() - (? * INTERVAL '1 minute'))",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, username)
                ps.setInt(4, ageMinutes)
                ps.executeUpdate()
            }
        }
    }
}
