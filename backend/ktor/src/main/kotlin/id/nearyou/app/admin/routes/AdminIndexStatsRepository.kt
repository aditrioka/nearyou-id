package id.nearyou.app.admin.routes

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * Aggregates for the admin Operational Dashboard at `GET /admin/`
 * (`admin-operational-dashboard` spec; expands the three scaffold cards from
 * `admin-mockup-parity` into the full frame-3 dashboard).
 *
 * Admin surface — raw-table reads are the established admin precedent. The
 * whole `.../app/admin/` module is **path-exempt** from `RawFromPostsRule`
 * and `BlockExclusionJoinRule` (project.md § Coding Conventions; the shipped
 * `ReportResolutionRepository` reads raw `posts` un-annotated), so the raw
 * `posts` / `users` aggregate reads below need NO `@AllowRawPostsRead` /
 * `@AllowMissingBlockJoin` annotation. They count ALL rows — including posts
 * by shadow-banned authors and auto-hidden posts — on purpose: an operator
 * monitoring true platform volume needs the full total, not the member-facing
 * `visible_posts` subset. (Excluding moderated content would defeat the
 * health signal.)
 *
 * All time arithmetic is UTC, computed in Kotlin and bound as parameters (no
 * `NOW()`; keeps the queries clock-injectable for tests). Per-hour buckets are
 * keyed by the TZ-independent epoch-hour (`floor(extract(epoch …)/3600)`), so
 * the bucketing is immune to the DB session timezone. Per-request with no
 * cache: solo-operator panel, a handful of indexed aggregates.
 */
class AdminIndexStatsRepository(
    private val dataSource: DataSource,
    private val clock: () -> Instant = Instant::now,
) {
    /** One row of the top-active-cities widget. */
    data class CityCount(
        val cityName: String,
        val count: Long,
    )

    data class IndexStats(
        /** Count of `reports.status = 'pending'`. */
        val pendingReports: Long,
        /** `created_at` of the oldest pending report; null when none. */
        val oldestPendingAt: Instant?,
        /** `rejected_identifiers` rows in the last 24 h. */
        val rejectedLast24h: Long,
        /**
         * Most frequent rejection reason in that window (ties → alphabetical);
         * null when none. Safe to render: `reason` is CHECK-constrained to the
         * V3 enum, so the value space is server-controlled.
         */
        val rejectedTopReason: String?,
        /** `admin_actions_log` rows for the current UTC day. */
        val auditActionsToday: Long,
        /** `action_type` of the newest audit row (all-time); null when the log is empty. */
        val auditLastActionType: String?,
        /** Posts created in the last 24 h (raw `posts`, incl. moderated). */
        val postsLast24h: Long,
        /** Posts per hour over the last 24 hourly buckets (oldest → current), zero-filled. */
        val postsByHour: List<Long>,
        /** New `users` rows in the last 24 h. */
        val signupsLast24h: Long,
        /** Signups per hour over the last 24 hourly buckets, zero-filled. */
        val signupsByHour: List<Long>,
        /** `rejected_identifiers` rows with reason `age_under_18` in the last 24 h. */
        val ageGateRejections24h: Long,
        /** Reports created in the last 24 h (inflow rate, distinct from the pending-queue card). */
        val reportsLast24h: Long,
        /** Reports per hour over the last 24 hourly buckets, zero-filled. */
        val reportsByHour: List<Long>,
        /** Top 10 cities by post count over the last 7 days (denormalized `posts.city_name`). */
        val topCities: List<CityCount>,
        /** Current DB size in bytes; null when `pg_database_size` is unavailable to the role. */
        val dbSizeBytes: Long?,
    )

    fun load(): IndexStats {
        val now = clock()
        val last24hStart = now.minusSeconds(24L * 60 * 60)
        val last7dStart = now.minusSeconds(7L * 24 * 60 * 60)
        val utcDayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        // 24 hourly buckets ending at the current (partial) hour.
        val firstHour = now.truncatedTo(ChronoUnit.HOURS).minusSeconds(23L * 60 * 60)

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

            // ---- Operational widgets (admin-path-exempt raw reads; see class header) ----

            val postsLast24h = countSince(conn, "SELECT COUNT(*) FROM posts WHERE created_at >= ?", last24hStart)
            val postsByHour = hourlyBuckets(conn, SQL_POSTS_BY_HOUR, firstHour)

            val signupsLast24h = countSince(conn, "SELECT COUNT(*) FROM users WHERE created_at >= ?", last24hStart)
            val signupsByHour = hourlyBuckets(conn, SQL_SIGNUPS_BY_HOUR, firstHour)
            val ageGateRejections24h =
                countSince(
                    conn,
                    "SELECT COUNT(*) FROM rejected_identifiers WHERE rejected_at >= ? AND reason = 'age_under_18'",
                    last24hStart,
                )

            val reportsLast24h = countSince(conn, "SELECT COUNT(*) FROM reports WHERE created_at >= ?", last24hStart)
            val reportsByHour = hourlyBuckets(conn, SQL_REPORTS_BY_HOUR, firstHour)

            val topCities = topCities(conn, last7dStart)
            val dbSizeBytes = dbSizeBytes(conn)

            return IndexStats(
                pendingReports = pendingReports,
                oldestPendingAt = oldestPendingAt,
                rejectedLast24h = rejectedLast24h,
                rejectedTopReason = rejectedTopReason,
                auditActionsToday = auditActionsToday,
                auditLastActionType = auditLastActionType,
                postsLast24h = postsLast24h,
                postsByHour = postsByHour,
                signupsLast24h = signupsLast24h,
                signupsByHour = signupsByHour,
                ageGateRejections24h = ageGateRejections24h,
                reportsLast24h = reportsLast24h,
                reportsByHour = reportsByHour,
                topCities = topCities,
                dbSizeBytes = dbSizeBytes,
            )
        }
    }

    private fun countSince(
        conn: Connection,
        sql: String,
        since: Instant,
    ): Long =
        conn.prepareStatement(sql).use { ps ->
            ps.setTimestamp(1, Timestamp.from(since))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    /**
     * Run a `GROUP BY` epoch-hour query (one `Timestamp` bind = the window
     * start) and project a dense 24-element series, oldest → current hour,
     * with empty hours zero-filled in Kotlin.
     */
    private fun hourlyBuckets(
        conn: Connection,
        sql: String,
        firstHour: Instant,
    ): List<Long> {
        val firstHourIdx = firstHour.epochSecond / 3600
        val byHour = HashMap<Long, Long>()
        conn.prepareStatement(sql).use { ps ->
            ps.setTimestamp(1, Timestamp.from(firstHour))
            ps.executeQuery().use { rs ->
                while (rs.next()) byHour[rs.getLong("hr")] = rs.getLong("c")
            }
        }
        return (0 until HOURLY_BUCKETS).map { i -> byHour[firstHourIdx + i] ?: 0L }
    }

    private fun topCities(
        conn: Connection,
        since: Instant,
    ): List<CityCount> =
        conn.prepareStatement(SQL_TOP_CITIES).use { ps ->
            ps.setTimestamp(1, Timestamp.from(since))
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(CityCount(rs.getString("city_name"), rs.getLong("c")))
                }
            }
        }

    /**
     * Current database size, or null when the role can't run `pg_database_size`
     * — the widget is then omitted rather than erroring the page (spec
     * "Database-size widget is omitted when unavailable").
     */
    private fun dbSizeBytes(conn: Connection): Long? =
        runCatching {
            conn.prepareStatement("SELECT pg_database_size(current_database())").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }.getOrNull()

    private companion object {
        /** 24 hourly buckets ending at the current (partial) hour. */
        const val HOURLY_BUCKETS = 24

        // Epoch-hour grouping is TZ-independent (epoch seconds are UTC), so the
        // buckets don't shift with the DB session timezone. Raw `posts`/`users`
        // reads are intentional + admin-path-exempt (see class header).
        const val SQL_POSTS_BY_HOUR =
            "SELECT floor(extract(epoch from created_at) / 3600)::bigint AS hr, COUNT(*) AS c " +
                "FROM posts WHERE created_at >= ? GROUP BY hr"
        const val SQL_SIGNUPS_BY_HOUR =
            "SELECT floor(extract(epoch from created_at) / 3600)::bigint AS hr, COUNT(*) AS c " +
                "FROM users WHERE created_at >= ? GROUP BY hr"
        const val SQL_REPORTS_BY_HOUR =
            "SELECT floor(extract(epoch from created_at) / 3600)::bigint AS hr, COUNT(*) AS c " +
                "FROM reports WHERE created_at >= ? GROUP BY hr"

        // Top cities by post count over the window. Groups by the denormalized
        // posts.city_name (V11 posts_set_city_tg) — NO admin_regions join / no
        // ST_Contains, matching the global-timeline read-time invariant.
        const val SQL_TOP_CITIES =
            "SELECT city_name, COUNT(*) AS c FROM posts " +
                "WHERE created_at >= ? AND city_name IS NOT NULL " +
                "GROUP BY city_name ORDER BY COUNT(*) DESC, city_name ASC LIMIT 10"
    }
}
