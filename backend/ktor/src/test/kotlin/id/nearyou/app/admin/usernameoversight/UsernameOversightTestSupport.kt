package id.nearyou.app.admin.usernameoversight

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the Premium Username Change Oversight integration
 * tests (`admin-premium-username-oversight` tasks.md Section 8). Seeds `users`,
 * `username_flagged` `moderation_queue` rows (carrying the candidate in `notes`),
 * `username_history` rows (with a controlled `released_at` so the hold list +
 * release path are deterministic), reads back `username_flag_overrides` (the accept
 * side-effect), reads `admin_actions_log`, and provides FK-safe teardown.
 *
 * Admin rows are seeded via `AdminAuthTestSupport.seedAdmin(...)`; this helper owns
 * only the queue/history/user/override fixtures + the count/assertion readers.
 * DB-backed; the consuming specs are tagged `database`. `created_at` / `released_at`
 * are bound as absolute [Instant]s via `Timestamp.from` (matching the production
 * repository bindings) so the keyset + hold-boundary comparisons are tz-deterministic.
 */
object UsernameOversightTestSupport {
    /** Seed a `users` row; returns its id. */
    fun seedUser(
        dataSource: DataSource,
        username: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "uo_$short")
                ps.setString(3, "Oversight Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "u${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed a `moderation_queue` row. Defaults to a pending `username_flagged` flag
     * carrying [candidate] in `notes`. [trigger] / [status] / [createdAt] are
     * overridable for the exclusion + keyset + already-resolved scenarios.
     */
    @Suppress("LongParameterList")
    fun seedQueueRow(
        dataSource: DataSource,
        targetId: UUID,
        candidate: String? = "borderlinehandle",
        trigger: String = "username_flagged",
        status: String = "pending",
        targetType: String = "user",
        createdAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO moderation_queue (id, target_type, target_id, trigger, notes, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, NOW()))
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, targetType)
                ps.setObject(3, targetId)
                ps.setString(4, trigger)
                if (candidate != null) ps.setString(5, candidate) else ps.setNull(5, Types.VARCHAR)
                ps.setString(6, status)
                if (createdAt != null) ps.setTimestamp(7, Timestamp.from(createdAt)) else ps.setNull(7, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed a `username_history` row. [releasedAt] controls whether it appears in the
     * hold list — default 10 days in the future (still held); pass a past instant to
     * model an elapsed hold. Returns the row id.
     */
    @Suppress("LongParameterList")
    fun seedHistory(
        dataSource: DataSource,
        userId: UUID,
        oldUsername: String,
        newUsername: String,
        changedAt: Instant = Instant.now(),
        releasedAt: Instant = Instant.now().plusSeconds(10L * 24 * 3600),
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO username_history (id, user_id, old_username, new_username, changed_at, released_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, oldUsername)
                ps.setString(4, newUsername)
                ps.setTimestamp(5, Timestamp.from(changedAt))
                ps.setTimestamp(6, Timestamp.from(releasedAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    // ---- moderation_queue readers -----------------------------------------

    data class QueueState(val status: String, val resolution: String?, val resolvedBy: UUID?)

    fun queueState(
        dataSource: DataSource,
        queueId: UUID,
    ): QueueState? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT status, resolution, resolved_by FROM moderation_queue WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, queueId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        QueueState(
                            status = rs.getString("status"),
                            resolution = rs.getString("resolution"),
                            resolvedBy = rs.getObject("resolved_by", UUID::class.java),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    // ---- username_history readers -----------------------------------------

    /** The row's `released_at` as an Instant (for the release / no-op assertions). */
    fun releasedAtOf(
        dataSource: DataSource,
        historyId: UUID,
    ): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT released_at FROM username_history WHERE id = ?").use { ps ->
                ps.setObject(1, historyId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("released_at").toInstant() else null }
            }
        }

    /** True when the row is still under hold (`released_at > NOW()`). */
    fun isHeld(
        dataSource: DataSource,
        historyId: UUID,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT (released_at > NOW()) AS held FROM username_history WHERE id = ?").use { ps ->
                ps.setObject(1, historyId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean("held") }
            }
        }

    // ---- username_flag_overrides readers ----------------------------------

    data class OverrideRow(val candidate: String, val approvedBy: UUID?, val consumed: Boolean)

    /** All override rows for [userId] (newest approval first). */
    fun overridesFor(
        dataSource: DataSource,
        userId: UUID,
    ): List<OverrideRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT candidate, approved_by, consumed_at FROM username_flag_overrides WHERE user_id = ? ORDER BY approved_at DESC",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OverrideRow(
                                    candidate = rs.getString("candidate"),
                                    approvedBy = rs.getObject("approved_by", UUID::class.java),
                                    consumed = rs.getTimestamp("consumed_at") != null,
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun overrideCount(
        dataSource: DataSource,
        userId: UUID,
    ): Int = overridesFor(dataSource, userId).size

    // ---- admin_actions_log readers ----------------------------------------

    data class AuditRow(
        val actionType: String,
        val targetType: String?,
        val targetId: String?,
        val beforeState: String?,
        val afterState: String?,
    )

    /** All audit rows for [adminId] (newest-first), with before/after JSONB as text. */
    fun auditRows(
        dataSource: DataSource,
        adminId: UUID,
    ): List<AuditRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT action_type, target_type, target_id, before_state::text AS bs, after_state::text AS afs " +
                    "FROM admin_actions_log WHERE admin_id = ? ORDER BY created_at DESC, id DESC",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AuditRow(
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

    fun auditCount(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String? = null,
    ): Int = auditRows(dataSource, adminId).count { actionType == null || it.actionType == actionType }

    /**
     * Seed one `admin_actions_log` row of an oversight action type with a controlled
     * trailing-hour age — for the rate-limit COUNT substrate (mirrors the
     * reserved-username limiter test's `seedReservedAudit`). [targetType] /
     * [targetId] match what the real writers emit so a stray cleanup matcher won't
     * miss it.
     */
    @Suppress("LongParameterList")
    fun seedAudit(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        ageMinutes: Int = 1,
        targetType: String = "user",
        targetId: String = UUID.randomUUID().toString(),
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at) " +
                    "VALUES (?, ?, ?, ?, NOW() - (? * INTERVAL '1 minute'))",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, targetType)
                ps.setString(4, targetId)
                ps.setInt(5, ageMinutes)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Clear every pending `username_flagged` row + every held `username_history`
     * row globally — for the handful of tests that assert a GLOBALLY-empty section
     * (the flagged-queue / holds lists are not per-admin, so an orphan row from a
     * crashed prior run or a concurrent writer would otherwise false-fail an
     * empty-state assertion). Safe because Kotest runs specs sequentially here (no
     * parallelism configured), so no concurrent spec is mid-write. Only touches the
     * exact rows the two global sections read.
     */
    fun clearGlobalSections(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM moderation_queue WHERE trigger = 'username_flagged' AND status = 'pending'")
                st.executeUpdate("DELETE FROM username_history WHERE released_at > NOW()")
            }
        }
    }

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

    /**
     * FK-safe teardown: per seeded user id, sweep `username_flag_overrides`,
     * `username_history`, `moderation_queue` (keyed on `target_id`), then the
     * `users` row. Admin audit rows are swept via `AdminAuthTestSupport.cleanupAdmin`.
     */
    fun cleanup(
        dataSource: DataSource,
        userIds: Collection<UUID>,
    ) {
        if (userIds.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            userIds.forEach { id ->
                runUpdate(conn, "DELETE FROM username_flag_overrides WHERE user_id = ?", id)
                runUpdate(conn, "DELETE FROM username_history WHERE user_id = ?", id)
                runUpdate(conn, "DELETE FROM moderation_queue WHERE target_id = ?", id)
                runUpdate(conn, "DELETE FROM users WHERE id = ?", id)
            }
        }
    }

    private fun runUpdate(
        conn: java.sql.Connection,
        sql: String,
        id: UUID,
    ) {
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
    }
}
