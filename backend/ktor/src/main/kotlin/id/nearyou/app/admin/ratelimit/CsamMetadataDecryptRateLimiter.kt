package id.nearyou.app.admin.ratelimit

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Per-admin rate limiter for the CSAM archived-metadata decrypt-on-view
 * (`admin-csam-detection-log` capability, design D5): a cap of
 * [CSAM_METADATA_DECRYPT_CAP] decrypts per acting admin per trailing one-hour window.
 *
 * This is ANOTHER INSTANTIATION of the audit-log-COUNT soft-cap pattern established by
 * [DestructiveActionRateLimiter] and reused by [RejectedIdentifierClearRateLimiter] /
 * [CsamKominfoReportRateLimiter] (design D5) — NOT a different pattern: the immutable
 * `admin_actions_log` audit trail IS the rate-limit ledger (no second source of truth,
 * no Redis coupling, no migration).
 *
 * It is deliberately a DISTINCT counter from the shared [DestructiveActionRateLimiter]
 * (20/hr): a metadata decrypt is forensic/restorative-but-sensitive (it reveals the
 * pseudonymous uploader id one row at a time for a Kominfo cross-reference / investigation),
 * NOT user-punitive — so it must NOT consume the destructive budget (design D5). It counts
 * ONLY `action_type = 'csam_metadata_decrypted'` rows; the two budgets stay independent.
 *
 * **Ledger semantics (design D4):** a `csam_metadata_decrypted` row is written only on
 * an AUTHORIZED decrypt invocation (after the `owner`/`admin` + CSRF gate passes,
 * including both fail-soft cases) — a 403 rejection writes NO row, so a denied actor
 * neither pollutes this ledger nor consumes the counter.
 *
 * Same ±1 soft-cap tolerance (no `FOR UPDATE` on the ledger) as the other audit-trail
 * limiters — an abuse-prevention SOFT cap, NOT a hard authorization boundary.
 *
 *  - Raw read of `admin_actions_log` is permitted here (the admin module is exempt from
 *    the `RawFromPostsRule` Detekt rule); the query is a parameterized `PreparedStatement`.
 */
class CsamMetadataDecryptRateLimiter(
    private val dataSource: DataSource,
) {
    /** Count the acting admin's metadata decrypts in the trailing hour, on a caller-owned
     *  [conn] (shares the decrypt-audit transaction). */
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

    /** Own-connection count (writes nothing) — for the read-only quota chip. */
    fun countInTrailingHour(adminId: UUID): Int = dataSource.connection.use { conn -> countInTrailingHour(conn, adminId) }

    /** True when the acting admin is at or over the cap on [conn]'s snapshot — the caller
     *  then rejects the decrypt with no decryption and no audit row. */
    fun isAtOrOverCap(
        conn: Connection,
        adminId: UUID,
    ): Boolean = countInTrailingHour(conn, adminId) >= CSAM_METADATA_DECRYPT_CAP

    companion object {
        /** Max CSAM metadata decrypts per admin per trailing hour (design D5 — tunable). */
        const val CSAM_METADATA_DECRYPT_CAP = 30

        private val COUNT_SQL =
            """
            SELECT COUNT(*)
              FROM admin_actions_log
             WHERE admin_id = ?
               AND created_at > NOW() - INTERVAL '1 hour'
               AND action_type = 'csam_metadata_decrypted'
            """.trimIndent()
    }
}
