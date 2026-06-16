package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the Premium Username Change Oversight writes
 * (`admin-premium-username-oversight` capability): two independent per-acting-admin
 * caps over a trailing one-hour window —
 *  - [FLAG_RESOLVE_CAP] (`username_flag_resolved`) flag resolutions, and
 *  - [HOLD_RELEASE_CAP] (`username_hold_released`) manual hold releases.
 *
 * This is a FURTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern
 * established by [DestructiveActionRateLimiter] + reused by
 * [ReservedUsernameActionRateLimiter] (design Decision 3) — NOT a different
 * pattern: the immutable `admin_actions_log` audit trail IS the rate-limit ledger
 * (no second source of truth to drift, no Redis coupling, no migration), the cap
 * is checked on a caller-supplied [Connection] inside the gated write's
 * transaction for read-consistency, and it differs from the sibling limiters only
 * in (a) the action types it counts and (b) the thresholds (10 & 5, per
 * `docs/07-Operations.md` § Premium Username Change Oversight). The two caps are
 * counted independently — each `COUNT(*)` filters to a SINGLE `action_type`, so a
 * flag-resolve never consumes hold-release quota and vice versa.
 *
 * Same ±1 soft-cap tolerance as the sibling limiters (no `FOR UPDATE` on the
 * ledger): two concurrent writes at count cap-1 can both pass → cap+1. For a
 * 10/hr & 5/hr abuse-prevention SOFT cap on a solo-to-few-admin panel this is
 * acceptable and is NOT a hard authorization boundary (design Decision 3).
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is exempt
 *    from the `RawFromPostsRule` Detekt rule); the query is a parameterized
 *    `PreparedStatement`.
 */
class UsernameOversightActionRateLimiter(
    private val dataSource: DataSource,
) {
    /**
     * Count the acting admin's [actionType] rows in the trailing hour, on a
     * caller-owned [conn] (shares the gated write's transaction). [actionType] is
     * one of the two oversight action types — the caller passes the type matching
     * the write it is gating, so the two caps stay independent.
     */
    fun countInTrailingHour(
        conn: Connection,
        adminId: UUID,
        actionType: String,
    ): Int =
        conn.prepareStatement(COUNT_SQL).use { ps ->
            ps.setObject(1, adminId)
            ps.setString(2, actionType)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "COUNT(*) yielded no row" }
                rs.getInt(1)
            }
        }

    /** True when the acting admin is at or over [cap] for [actionType] on [conn]'s
     *  snapshot — the caller then rejects the write with no mutation / no audit row
     *  (per the `admin-premium-username-oversight` spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
        actionType: String,
        cap: Int,
    ): Boolean = countInTrailingHour(conn, adminId, actionType) >= cap

    companion object {
        /** Max flag resolutions per admin per trailing hour
         *  (`docs/07-Operations.md` § Premium Username Change Oversight). */
        const val FLAG_RESOLVE_CAP = 10

        /** Max manual hold releases per admin per trailing hour
         *  (`docs/07-Operations.md` § Premium Username Change Oversight). */
        const val HOLD_RELEASE_CAP = 5

        /** The two `admin_actions_log.action_type` values these caps gate. */
        const val ACTION_FLAG_RESOLVED = "username_flag_resolved"
        const val ACTION_HOLD_RELEASED = "username_hold_released"

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND action_type = ?
               AND created_at > NOW() - INTERVAL '1 hour'
            """.trimIndent()
    }
}
