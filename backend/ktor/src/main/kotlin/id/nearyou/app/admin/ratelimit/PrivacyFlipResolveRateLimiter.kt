package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the privacy-flip-monitor resolve write
 * (`admin-privacy-flip-resolve`): a cap of [PRIVACY_FLIP_RESOLVE_CAP] resolves
 * per acting admin per trailing one-hour window.
 *
 * ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern established by
 * [DestructiveActionRateLimiter] (siblings: [GraceExpediteActionRateLimiter],
 * [DeletionQueueExpediteRateLimiter], [RejectedIdentifierClearRateLimiter]) — NOT
 * a different pattern: the immutable `admin_actions_log` audit trail IS the
 * ledger, and the cap is checked on a caller-supplied [Connection] inside the
 * gated write's transaction.
 *
 * Deliberately a DISTINCT counter from the destructive limiter (design D3): a
 * resolve is corrective bookkeeping that punishes no one, so it counts ONLY
 * `action_type = 'privacy_flip_resolved'` rows and neither consumes nor is
 * blocked by the 20/hour destructive budget. Same ±1 soft-cap tolerance (no
 * `FOR UPDATE` on the ledger) as every sibling — an abuse-prevention SOFT cap,
 * not an authorization boundary.
 */
class PrivacyFlipResolveRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's resolves in the trailing hour, on a caller-owned
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

    /** Own-connection count, for the read-only quota chip (writes nothing). */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot —
     *  the caller then rejects the resolve with no mutation / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= PRIVACY_FLIP_RESOLVE_CAP

    companion object {
        /** Max privacy-flip resolves per admin per trailing hour (design D3). */
        const val PRIVACY_FLIP_RESOLVE_CAP = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'privacy_flip_resolved'
            """.trimIndent()
    }
}
