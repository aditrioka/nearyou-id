package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the reserved-username editor writes
 * (`admin-reserved-usernames-editor` capability): a cap of
 * [RESERVED_USERNAME_ACTION_CAP] add/edit/remove actions per acting admin per
 * trailing one-hour window.
 *
 * This is a SECOND INSTANTIATION of the audit-log-COUNT soft-cap pattern
 * established by [DestructiveActionRateLimiter] (design D1) — NOT a different
 * pattern: the immutable `admin_actions_log` audit trail IS the rate-limit
 * ledger (no second source of truth to drift, no Redis coupling, no migration),
 * and the cap is checked on a caller-supplied [Connection] inside the gated
 * write's transaction for read-consistency. It differs from the destructive
 * limiter only in (a) the action set — the three reserved-username action types —
 * and (b) the threshold (100, per `docs/07-Operations.md` § Reserved Usernames
 * Editor), so the destructive (20/hr) and reserved-username (100/hr) caps stay
 * independent. Same ±1 soft-cap tolerance (no `FOR UPDATE` on the ledger): two
 * concurrent writes at count 99 can both pass → 101. For a 100/hr abuse-
 * prevention SOFT cap on a solo-to-few-admin panel this is acceptable and is NOT
 * a hard authorization boundary (design D1).
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is
 *    exempt from the `RawFromPostsRule` Detekt rule); the query is a
 *    parameterized `PreparedStatement`.
 *  - The own-connection [countInTrailingHour] overload backs the read-only quota
 *    chip on the editor page.
 */
class ReservedUsernameActionRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's reserved-username write actions in the trailing
     *  hour, on a caller-owned [conn] (shares the write's transaction). */
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
     *  the caller then rejects the write with no mutation / no audit row (per the
     *  `admin-reserved-usernames-editor` spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= RESERVED_USERNAME_ACTION_CAP

    companion object {
        /** Max reserved-username write actions per admin per trailing hour
         *  (`docs/07-Operations.md` § Reserved Usernames Editor). */
        const val RESERVED_USERNAME_ACTION_CAP = 100

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type IN (
                     'reserved_username_added',
                     'reserved_username_edited',
                     'reserved_username_removed'
                   )
            """.trimIndent()
    }
}
