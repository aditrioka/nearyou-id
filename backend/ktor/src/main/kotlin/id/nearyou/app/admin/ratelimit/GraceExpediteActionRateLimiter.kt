package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the subscription-grace manual-expedite write
 * (`admin-subscription-grace-monitor` capability): a cap of
 * [GRACE_EXPEDITE_ACTION_CAP] expedite actions per acting admin per trailing
 * one-hour window.
 *
 * This is ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern
 * established by [DestructiveActionRateLimiter] and reused by
 * [ReservedUsernameActionRateLimiter] (design D2) — NOT a different pattern:
 * the immutable `admin_actions_log` audit trail IS the rate-limit ledger (no
 * second source of truth to drift, no Redis coupling, no migration), and the
 * cap is checked on a caller-supplied [Connection] inside the gated write's
 * transaction for read-consistency.
 *
 * It is deliberately a DISTINCT counter from the destructive limiter: the
 * `admin-destructive-action-rate-limit` destructive set is user-punitive /
 * content-removal only (warn / suspend / ban / shadow-ban / redact). A grace
 * expedite is NON-punitive support-desk bookkeeping (it changes no entitlement),
 * so it is OUTSIDE that set — it counts ONLY `action_type =
 * 'subscription_grace_expedite'` rows, and the two budgets (20/hr destructive,
 * 20/hr expedite) stay independent: an expedite neither consumes nor is blocked
 * by the destructive budget, and vice-versa. Same ±1 soft-cap tolerance (no
 * `FOR UPDATE` on the ledger) as the destructive limiter — acceptable for an
 * abuse-prevention SOFT cap on a non-destructive action, NOT a hard
 * authorization boundary.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is
 *    exempt from the `RawFromPostsRule` Detekt rule); the query is a
 *    parameterized `PreparedStatement`.
 *  - The own-connection [countInTrailingHour] overload backs the read-only quota
 *    chip on the grace-monitor page.
 */
class GraceExpediteActionRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's grace-expedite actions in the trailing hour, on
     *  a caller-owned [conn] (shares the write's transaction). */
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
     *  the caller then rejects the expedite with no mutation / no audit row (per
     *  the `admin-subscription-grace-monitor` spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= GRACE_EXPEDITE_ACTION_CAP

    companion object {
        /** Max grace-expedite actions per admin per trailing hour
         *  (`docs/07-Operations.md` § Subscription Grace Monitor; design D2). */
        const val GRACE_EXPEDITE_ACTION_CAP = 20

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'subscription_grace_expedite'
            """.trimIndent()
    }
}
