package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the Data Export Queue manual-trigger write
 * (`admin-data-export-queue` capability): a cap of [DATA_EXPORT_TRIGGER_ACTION_CAP]
 * triggers per acting admin per trailing one-hour window.
 *
 * ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern established by
 * [DestructiveActionRateLimiter] and reused by [DeletionQueueExpediteRateLimiter] /
 * [GraceExpediteActionRateLimiter] / [RejectedIdentifierClearRateLimiter] (design
 * D4) — NOT a different pattern: the immutable `admin_actions_log` audit trail IS
 * the rate-limit ledger (no second source of truth to drift, no Redis coupling, no
 * migration), and the cap is checked on a caller-supplied [Connection] inside the
 * gated write's transaction for read-consistency.
 *
 * It is deliberately a DISTINCT counter from the destructive limiter: the
 * `admin-destructive-action-rate-limit` destructive set is user-punitive /
 * content-removal only (warn / suspend / ban / shadow-ban / redact). A data-export
 * re-run is an OPERATIONAL recovery accommodation (re-run a stalled/failed export
 * the scheduler missed), semantically disjoint from the punitive set — so it counts
 * ONLY `action_type = 'data_export_triggered'` rows, and the two budgets stay
 * independent: a trigger neither consumes nor is blocked by the destructive budget,
 * and vice-versa (mirrors [DeletionQueueExpediteRateLimiter]).
 *
 * The cap is the **tighter 10/hour** (matching the deletion-expedite +
 * rejected-identifiers-clear sensitive low-volume support actions): a manual trigger
 * runs the producer's heavy per-request work (gather + ZIP + R2 upload) inside the
 * admin request, so a smaller blast radius bounds a scripted-tool or compromised-session
 * mistake. Same ±1 soft-cap tolerance (no `FOR UPDATE` on the ledger) as the
 * destructive limiter — an abuse-prevention SOFT cap, NOT a hard authorization boundary.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is exempt
 *    from the `RawFromPostsRule` Detekt rule); the query is a parameterized
 *    `PreparedStatement`.
 *  - The own-connection [countInTrailingHour] overload backs the read-only quota chip
 *    on the data-export-queue page.
 */
class DataExportTriggerRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's data-export triggers in the trailing hour, on a
     *  caller-owned [conn] (shares the write's transaction). */
    fun countInTrailingHour(
        conn: Connection,
        adminId: UUID,
    ): Int =
        conn.prepareStatement(COUNT_SQL).use { ps ->
            ps.setObject(1, adminId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "COUNT(*) yielded no row" }
                rs.getInt(1)
            }
        }

    /** Own-connection count, for the read-only quota chip (writes nothing). */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot — the
     *  caller then rejects the trigger with no mutation / no audit row (per the
     *  `admin-data-export-queue` spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= DATA_EXPORT_TRIGGER_ACTION_CAP

    companion object {
        /** Max Data-Export-Queue trigger actions per admin per trailing hour
         *  (`docs/07-Operations.md` § Data Export Queue). */
        const val DATA_EXPORT_TRIGGER_ACTION_CAP = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'data_export_triggered'
            """.trimIndent()
    }
}
