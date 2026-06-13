package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared per-admin destructive-action rate limiter (`admin-destructive-action-
 * rate-limit` capability): a cap of [DESTRUCTIVE_ACTION_CAP] destructive admin
 * actions per acting admin per trailing one-hour window. Pulls forward the
 * `docs/08-Roadmap-Risk.md` § Pre-Launch #9 limiter the `admin-user-moderation`
 * spec Purpose flagged as deferred.
 *
 * Substrate decision (design D2): COUNT over `admin_actions_log` — the immutable
 * audit trail IS the rate-limit ledger, so there is no second source of truth to
 * drift, no new infra coupling (the admin panel does not depend on Redis), and
 * no migration. The destructive set (design D3) is the user-punitive actions:
 *
 *  - `user_warned` + `user_suspended` (direct `action_type`), and
 *  - the destructive report-queue resolutions, which all log
 *    `action_type = 'moderation_queue_resolved'` (shared with `keep`/`hide`) and
 *    so are isolated by `after_state ->> 'resolution' IN (suspend_author_7d,
 *    ban_author, shadow_ban_author)`.
 *
 * The two predicate arms are DISJOINT — a single suspend logs exactly one of
 * `user_suspended` (user-page) or `moderation_queue_resolved`+`resolution`
 * (report-queue), never both — so no action is double-counted. Restorative /
 * non-punitive actions (`user_unbanned`, content `keep`/`hide`, `report_resolved`
 * bookkeeping, login) are NOT in the set and are never counted or capped.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is
 *    exempt from the `RawFromPostsRule` Detekt rule); the query is a
 *    parameterized `PreparedStatement`.
 *  - [countInTrailingHour] / [isAtOrOverCap] take a caller-supplied [Connection]
 *    so the cap check reads a CONSISTENT ledger snapshot inside the same
 *    transaction as the destructive action it gates. This gives read-consistency
 *    but NOT serialization (no `FOR UPDATE` on the ledger): two concurrent
 *    requests at count 19 can both pass → 21. For a 20/hr abuse-prevention SOFT
 *    cap on a solo-to-few-admin panel this ±1 tolerance is acceptable and is NOT
 *    a hard authorization boundary (design D4).
 *  - The own-connection [countInTrailingHour] overload backs the read-only quota
 *    chip on the profile page.
 */
class DestructiveActionRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's destructive actions in the trailing hour, on a
     *  caller-owned [conn] (shares the destructive action's transaction). */
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
     *  the caller then rejects the destructive action with no mutation / no
     *  audit row (per the consuming capability's spec). */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= DESTRUCTIVE_ACTION_CAP

    companion object {
        /** Max destructive actions per admin per trailing hour (Pre-Launch #9). */
        const val DESTRUCTIVE_ACTION_CAP = 20

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND (
                     action_type IN ('user_warned', 'user_suspended')
                  OR (
                        action_type = 'moderation_queue_resolved'
                    AND after_state ->> 'resolution' IN ('suspend_author_7d', 'ban_author', 'shadow_ban_author')
                     )
                   )
            """.trimIndent()
    }
}
