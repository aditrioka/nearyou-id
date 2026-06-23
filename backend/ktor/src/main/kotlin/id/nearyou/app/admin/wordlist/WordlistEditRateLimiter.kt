package id.nearyou.app.admin.wordlist

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin moderation-wordlist-publish rate limiter (`admin-moderation-wordlist-
 * editor` spec Req "Wordlist writes are capped per admin per trailing hour on a
 * distinct bucket"). Mirrors
 * [id.nearyou.app.admin.featureflags.FeatureFlagToggleRateLimiter]: COUNT over the
 * immutable `admin_actions_log` ledger (no second source of truth, no Redis, no
 * migration), on a caller-supplied [Connection] so the count and the success-path
 * audit INSERT share one snapshot (the count can't drift from the ledger it gates;
 * design D2).
 *
 * This is a DISTINCT bucket from BOTH the 5/hour `feature_flag_toggled` cap and the
 * 20/hour destructive cap: it counts ONLY `action_type = 'moderation_wordlist_
 * edited'`, which is in neither limiter's allowlist, so the three never cross-count.
 *
 * Same ±1 soft-cap tolerance as the sibling limiters (no `FOR UPDATE` on the
 * ledger): acceptable for a 10/hour abuse cap on a solo-to-few-admin panel; not a
 * hard authorization boundary.
 */
class WordlistEditRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's wordlist publishes in the trailing hour, on a caller-owned [conn]. */
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

    /** Own-connection count (e.g. for the editor quota chip); writes nothing. */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot — the caller then
     *  rejects with no publish / no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= WORDLIST_EDIT_CAP

    companion object {
        /** Max moderation-wordlist publishes per admin per trailing hour (design D2). */
        const val WORDLIST_EDIT_CAP: Int = 10

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'moderation_wordlist_edited'
            """.trimIndent()
    }
}
