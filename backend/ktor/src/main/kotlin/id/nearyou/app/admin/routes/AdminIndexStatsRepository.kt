package id.nearyou.app.admin.routes

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * The three landing-page stat aggregates (admin-mockup-parity design.md D4,
 * spec Req "Scaffold landing renders greeting and live stat cards").
 *
 * Admin surface — raw-table reads are the established admin precedent (the
 * shadow-ban `visible_*` views exist for member-facing paths); none of the
 * three tables is `posts`/`users`, so the Detekt SQL rules are inert here.
 * All time arithmetic is UTC, computed in Kotlin and bound as parameters
 * (no `NOW()` needed; also keeps the queries clock-injectable for tests).
 * Per-request with no cache: solo-operator panel, three indexed aggregates.
 */
class AdminIndexStatsRepository(
    private val dataSource: DataSource,
    private val clock: () -> Instant = Instant::now,
) {
    data class IndexStats(
        /** Count of `reports.status = 'pending'`. */
        val pendingReports: Long,
        /** `created_at` of the oldest pending report; null when none. */
        val oldestPendingAt: Instant?,
        /** `rejected_identifiers` rows in the last 24 h. */
        val rejectedLast24h: Long,
        /** Most frequent rejection reason in that window (ties → alphabetical); null when none. */
        val rejectedTopReason: String?,
        /** `admin_actions_log` rows for the current UTC day. */
        val auditActionsToday: Long,
        /** `action_type` of the newest audit row (all-time); null when the log is empty. */
        val auditLastActionType: String?,
    )

    fun load(): IndexStats {
        val now = clock()
        val last24hStart = now.minusSeconds(24L * 60 * 60)
        val utcDayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()

        dataSource.connection.use { conn ->
            var pendingReports = 0L
            var oldestPendingAt: Instant? = null
            conn.prepareStatement(
                // Rides reports_status_idx (status, created_at DESC).
                "SELECT COUNT(*) AS pending_count, MIN(created_at) AS oldest_at FROM reports WHERE status = 'pending'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    pendingReports = rs.getLong("pending_count")
                    oldestPendingAt = rs.getTimestamp("oldest_at")?.toInstant()
                }
            }

            var rejectedLast24h = 0L
            var rejectedTopReason: String? = null
            conn.prepareStatement(
                """
                SELECT reason, COUNT(*) AS reason_count
                  FROM rejected_identifiers
                 WHERE rejected_at >= ?
                 GROUP BY reason
                 ORDER BY reason_count DESC, reason ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(last24hStart))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (rejectedTopReason == null) rejectedTopReason = rs.getString("reason")
                        rejectedLast24h += rs.getLong("reason_count")
                    }
                }
            }

            var auditActionsToday = 0L
            conn.prepareStatement(
                // Rides the V17 created_at index.
                "SELECT COUNT(*) AS today_count FROM admin_actions_log WHERE created_at >= ?",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(utcDayStart))
                ps.executeQuery().use { rs ->
                    rs.next()
                    auditActionsToday = rs.getLong("today_count")
                }
            }

            var auditLastActionType: String? = null
            conn.prepareStatement(
                "SELECT action_type FROM admin_actions_log ORDER BY created_at DESC LIMIT 1",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) auditLastActionType = rs.getString("action_type")
                }
            }

            return IndexStats(
                pendingReports = pendingReports,
                oldestPendingAt = oldestPendingAt,
                rejectedLast24h = rejectedLast24h,
                rejectedTopReason = rejectedTopReason,
                auditActionsToday = auditActionsToday,
                auditLastActionType = auditLastActionType,
            )
        }
    }
}
