package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the rejected-identifier manual-clear write
 * (`admin-rejected-identifiers-clear-action` capability): a cap of
 * [REJECTED_IDENTIFIER_CLEAR_CAP] clears per acting admin per trailing one-hour
 * window.
 *
 * This is a FURTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern
 * established by [DestructiveActionRateLimiter] / [ReservedUsernameActionRateLimiter]
 * (design D1) — NOT a different pattern: the immutable `admin_actions_log` audit
 * trail IS the rate-limit ledger (no second source of truth, no Redis coupling,
 * no migration), and the cap is checked on a caller-supplied [Connection] inside
 * the gated write's transaction for read-consistency.
 *
 * It is a DEDICATED cap, deliberately NOT folded into the shared
 * [DestructiveActionRateLimiter] (20/hr): that limiter's set is the user-PUNITIVE /
 * content-removal actions (warn / suspend / ban / shadow-ban / chat-redaction) and
 * explicitly EXCLUDES restorative actions; clearing a rejected identifier is
 * restorative-but-sensitive (it re-opens signup for a previously-blocked identity,
 * like an unban), so it does not belong in the punitive set
 * (`admin-rejected-identifiers-clear-action` design.md D1). The threshold (10,
 * `docs/07-Operations.md` § Rejected Identifiers Viewer) is an order of magnitude
 * below the reserved-username editor's 100/hr — clearing is a rare, sensitive
 * support op. Same ±1 soft-cap tolerance (no `FOR UPDATE` on the ledger): two
 * concurrent clears at count 9 can both pass → 11. For a 10/hr abuse-prevention
 * SOFT cap on a solo-to-few-admin panel this is acceptable and is NOT a hard
 * authorization boundary (design D1).
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is
 *    exempt from the `RawFromPostsRule` Detekt rule); the query is a
 *    parameterized `PreparedStatement`.
 */
class RejectedIdentifierClearRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's clear actions in the trailing hour, on a
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

    /** Own-connection count (writes nothing) — for a quota chip / read use. */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot —
     *  the caller then rejects the clear with no mutation / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= REJECTED_IDENTIFIER_CLEAR_CAP

    companion object {
        /** Max rejected-identifier clears per admin per trailing hour
         *  (`docs/07-Operations.md` § Rejected Identifiers Viewer; design.md D1 — tunable). */
        const val REJECTED_IDENTIFIER_CLEAR_CAP = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'rejected_identifier_cleared'
            """.trimIndent()
    }
}
