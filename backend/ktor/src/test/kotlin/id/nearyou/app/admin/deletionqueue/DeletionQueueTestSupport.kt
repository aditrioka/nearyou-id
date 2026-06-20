package id.nearyou.app.admin.deletionqueue

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the hard-delete-queue integration tests
 * (`admin-hard-delete-queue` tasks.md Section 6).
 *
 * Seeds `users` rows (optionally soft-deleted), `deletion_requests` rows with a
 * controllable lifecycle state (`scheduled_hard_delete_at`, `cancelled_at`,
 * `executed_at`, `source`), and `admin_actions_log` rows (for the rate-limit-cap,
 * budget-independence, and already-expedited-indicator cases). Cleanup is
 * per-seeded-id (NOT a full-table wipe): deleting a seeded `users` row cascades its
 * `deletion_requests` rows (FK `ON DELETE CASCADE`); `admin_actions_log` rows are
 * cleaned per-admin by `AdminAuthTestSupport.cleanupAdmin`.
 */
object DeletionQueueTestSupport {
    /** Seed a `users` row (optionally soft-deleted via [deletedAt]). */
    fun seedUser(
        dataSource: DataSource,
        username: String,
        displayName: String = "Deletion Tester",
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "d${short.take(7)}")
                ps.setObject(6, deletedAt?.let { Timestamp.from(it) })
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a `deletion_requests` row for [userId]; returns the request id. */
    fun seedDeletionRequest(
        dataSource: DataSource,
        userId: UUID,
        scheduledHardDeleteAt: Instant,
        source: String = "user",
        requestedAt: Instant = Instant.now().minusSeconds(60),
        cancelledAt: Instant? = null,
        executedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO deletion_requests (
                    id, user_id, requested_at, scheduled_hard_delete_at, cancelled_at, executed_at, source
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setObject(3, Timestamp.from(requestedAt))
                ps.setObject(4, Timestamp.from(scheduledHardDeleteAt))
                ps.setObject(5, cancelledAt?.let { Timestamp.from(it) })
                ps.setObject(6, executedAt?.let { Timestamp.from(it) })
                ps.setString(7, source)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed an `admin_actions_log` row of an arbitrary [actionType] for the acting
     *  [adminId] — backs the cap + budget-independence + already-expedited tests.
     *  [targetId] is the optional target (the deletion-request id, TEXT column). */
    fun seedAuditRow(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        targetId: UUID? = null,
        createdAt: Instant = Instant.now(),
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, if (targetId != null) "deletion_request" else null)
                ps.setString(4, targetId?.toString())
                ps.setObject(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    /** Count `deletion_request_expedited` audit rows targeting [requestId]. */
    fun countExpeditesFor(
        dataSource: DataSource,
        requestId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM admin_actions_log " +
                    "WHERE action_type = 'deletion_request_expedited' AND target_id = ?",
            ).use { ps ->
                ps.setString(1, requestId.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** The current `scheduled_hard_delete_at` of [requestId] (for the deadline-advance assertion). */
    fun scheduledHardDeleteAtOf(
        dataSource: DataSource,
        requestId: UUID,
    ): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT scheduled_hard_delete_at FROM deletion_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1).toInstant() else null }
            }
        }

    /** The `executed_at` of [requestId] (NULL ⇒ the worker has not run; the route never erases). */
    fun executedAtOf(
        dataSource: DataSource,
        requestId: UUID,
    ): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT executed_at FROM deletion_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
            }
        }

    /** Count `deletion_log` rows for [userId] — MUST stay 0 after an expedite (the route does not erase). */
    fun deletionLogCountFor(
        dataSource: DataSource,
        userId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM deletion_log WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Count of pending (un-executed, un-cancelled) deletion requests — the baseline for delta-based count assertions. */
    fun pendingCount(dataSource: DataSource): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM deletion_requests WHERE executed_at IS NULL AND cancelled_at IS NULL",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /** Remove a seeded user by id; the `ON DELETE CASCADE` FK removes its `deletion_requests` rows. */
    fun deleteUser(
        dataSource: DataSource,
        id: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM deletion_log WHERE user_id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }
}
