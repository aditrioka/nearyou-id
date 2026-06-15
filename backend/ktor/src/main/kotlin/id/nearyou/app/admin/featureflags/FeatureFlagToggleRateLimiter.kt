package id.nearyou.app.admin.featureflags

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin feature-flag-publish rate limiter (`admin-feature-flags` spec Req
 * "Flag writes are capped at 5 per admin per trailing hour, distinct from the
 * destructive-action cap"). Mirrors
 * [id.nearyou.app.admin.ratelimit.DestructiveActionRateLimiter]: COUNT over the
 * immutable `admin_actions_log` ledger (no second source of truth, no Redis, no
 * migration), on a caller-supplied [Connection] so the count and the success-path
 * audit INSERT share one snapshot (the count can't drift from the ledger it gates;
 * design D3).
 *
 * This is a DISTINCT bucket from the 20/hour destructive cap: it counts ONLY
 * `action_type = 'feature_flag_toggled'`, which is not in the destructive
 * limiter's allowlist (`user_warned` / `user_suspended` / punitive
 * `moderation_queue_resolved`), so the two never cross-count.
 *
 * Same ±1 soft-cap tolerance as the destructive limiter (no `FOR UPDATE` on the
 * ledger): acceptable for a 5/hour abuse cap on a solo-to-few-admin panel; not a
 * hard authorization boundary.
 */
class FeatureFlagToggleRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's feature-flag publishes in the trailing hour, on a caller-owned [conn]. */
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

    /** Own-connection count (e.g. for a quota chip); writes nothing. */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot — the caller then
     *  rejects with no publish / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= FEATURE_FLAG_TOGGLE_CAP

    companion object {
        /** Max feature-flag publishes per admin per trailing hour (docs/07 § Security). */
        const val FEATURE_FLAG_TOGGLE_CAP: Int = 5

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'feature_flag_toggled'
            """.trimIndent()
    }
}
