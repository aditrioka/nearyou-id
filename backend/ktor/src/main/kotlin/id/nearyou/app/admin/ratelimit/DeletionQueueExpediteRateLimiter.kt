package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the hard-delete-queue manual-expedite write
 * (`admin-hard-delete-queue` capability): a cap of [DELETION_EXPEDITE_ACTION_CAP]
 * expedite actions per acting admin per trailing one-hour window.
 *
 * This is ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern
 * established by [DestructiveActionRateLimiter] and reused by
 * [GraceExpediteActionRateLimiter] / [ReservedUsernameActionRateLimiter] (design
 * D2) — NOT a different pattern: the immutable `admin_actions_log` audit trail IS
 * the rate-limit ledger (no second source of truth to drift, no Redis coupling,
 * no migration), and the cap is checked on a caller-supplied [Connection] inside
 * the gated write's transaction for read-consistency.
 *
 * It is deliberately a DISTINCT counter from the destructive limiter: the
 * `admin-destructive-action-rate-limit` destructive set is user-punitive /
 * content-removal only (warn / suspend / ban / shadow-ban / redact). A hard-delete
 * expedite is a user-REQUESTED accommodation ("delete me now"), semantically
 * disjoint from the punitive set — so it counts ONLY `action_type =
 * 'deletion_request_expedited'` rows, and the two budgets stay independent: an
 * expedite neither consumes nor is blocked by the destructive budget, and
 * vice-versa (design D2; mirrors [GraceExpediteActionRateLimiter]).
 *
 * The cap is the **tighter 10/hour** (vs grace's 20) because expedite accelerates
 * an IRREVERSIBLE erasure — a smaller blast radius for a scripted-tool or
 * compromised-session mistake (matches the `admin-rejected-identifiers-clear-action`
 * 10/hour cap for a sensitive, low-volume support action). Same ±1 soft-cap
 * tolerance (no `FOR UPDATE` on the ledger) as the destructive limiter — an
 * abuse-prevention SOFT cap, NOT a hard authorization boundary.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is
 *    exempt from the `RawFromPostsRule` Detekt rule); the query is a
 *    parameterized `PreparedStatement`.
 *  - The own-connection [countInTrailingHour] overload backs the read-only quota
 *    chip on the hard-delete-queue page.
 */
class DeletionQueueExpediteRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's deletion-expedite actions in the trailing hour, on
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
     *  the `admin-hard-delete-queue` spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= DELETION_EXPEDITE_ACTION_CAP

    companion object {
        /** Max hard-delete-queue expedite actions per admin per trailing hour
         *  (`docs/07-Operations.md` § Hard Delete Queue; design D2). */
        const val DELETION_EXPEDITE_ACTION_CAP = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'deletion_request_expedited'
            """.trimIndent()
    }
}
