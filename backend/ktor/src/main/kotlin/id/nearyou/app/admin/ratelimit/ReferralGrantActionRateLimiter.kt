package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the manual referral-grant write
 * (`admin-referral-manual-grant` capability): a cap of
 * [REFERRAL_MANUAL_GRANT_CAP] grants per acting admin per trailing one-hour
 * window.
 *
 * Another instantiation of the audit-log-COUNT soft-cap pattern (clones
 * [GraceExpediteActionRateLimiter] / [DeletionQueueExpediteRateLimiter] /
 * [RejectedIdentifierClearRateLimiter]) — the immutable `admin_actions_log`
 * audit trail IS the ledger. It is a DISTINCT counter from the 20/hr
 * destructive budget: granting Premium is RESTORATIVE (a support-desk remedy
 * for a false-negative referral), not user-punitive, so it counts ONLY
 * `action_type = 'referral_manual_grant'` rows and neither consumes nor is
 * blocked by the destructive budget or the other restorative caps.
 *
 * Same ±1 soft-cap tolerance (no `FOR UPDATE` on the ledger) as its siblings —
 * an abuse-prevention SOFT cap, NOT a hard authorization boundary (sufficient
 * given CSRF + owner/admin role + required reason + the immutable trail).
 *
 * Raw read of `admin_actions_log` is permitted (the admin module is exempt from
 * `RawFromPostsRule`); the query is a parameterized `PreparedStatement`.
 */
class ReferralGrantActionRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's manual grants in the trailing hour, on a
     *  caller-owned [conn] (shares the write's read-consistency snapshot). */
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

    /** True when the acting admin is at or over the cap — the caller then
     *  rejects the grant with no dispatch / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= REFERRAL_MANUAL_GRANT_CAP

    companion object {
        /** Max manual referral grants per admin per trailing hour (design D4). */
        const val REFERRAL_MANUAL_GRANT_CAP = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'referral_manual_grant'
            """.trimIndent()
    }
}
