package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the CSAM Kominfo-report write
 * (`admin-csam-detection-log` capability, design D5): a cap of
 * [CSAM_KOMINFO_REPORT_CAP] filings per acting admin per trailing one-hour window.
 *
 * This is ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern established by
 * [DestructiveActionRateLimiter] and reused by [RejectedIdentifierClearRateLimiter] /
 * [DeletionQueueExpediteRateLimiter] (design D5) — NOT a different pattern: the immutable
 * `admin_actions_log` audit trail IS the rate-limit ledger (no second source of truth,
 * no Redis coupling, no migration), and the cap is checked on a caller-supplied
 * [Connection] inside the gated write's transaction for read-consistency.
 *
 * It is deliberately a DISTINCT counter from the shared [DestructiveActionRateLimiter]
 * (20/hr): that limiter's set is the user-PUNITIVE / content-removal actions (warn /
 * suspend / ban / shadow-ban / redaction). Recording a Kominfo filing is
 * bookkeeping/forensic — it mutates no user, removes no content, and merely records the
 * legal-report id that releases the archive row to the purge worker — so it must NOT
 * consume the destructive budget (design D5). It counts ONLY `action_type =
 * 'csam_kominfo_reported'` rows; the two budgets stay independent. Same ±1 soft-cap
 * tolerance (no `FOR UPDATE` on the ledger) as the other audit-trail limiters — an
 * abuse-prevention SOFT cap on a solo-to-few-admin panel, NOT a hard authorization
 * boundary.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is exempt from
 *    the `RawFromPostsRule` Detekt rule); the query is a parameterized `PreparedStatement`.
 */
class CsamKominfoReportRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's Kominfo filings in the trailing hour, on a caller-owned
     *  [conn] (shares the write's transaction). */
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

    /** Own-connection count (writes nothing) — for the read-only quota chip. */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot — the caller
     *  then rejects the filing with no mutation / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= CSAM_KOMINFO_REPORT_CAP

    companion object {
        /** Max CSAM Kominfo-report filings per admin per trailing hour (design D5 — tunable). */
        const val CSAM_KOMINFO_REPORT_CAP = 30

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'csam_kominfo_reported'
            """.trimIndent()
    }
}
