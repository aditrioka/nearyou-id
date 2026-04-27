package id.nearyou.app.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Single atomic upsert against `user_fcm_tokens` per design D6:
 * `INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE SET
 *  last_seen_at = NOW(), app_version = EXCLUDED.app_version`.
 *
 * The `RETURNING xmax = 0 AS created` distinguishes a fresh insert
 * (xmax always 0 for newly-inserted tuples on a non-partitioned table)
 * from an ON CONFLICT update (xmax = current xid, non-zero) — reliable
 * idiom for plain ON CONFLICT on PostgreSQL 9.5+.
 *
 * The `(SELECT COUNT(*) ...)` subquery is a single index scan on
 * `user_fcm_tokens_user_idx` and powers the design D3 tripwire
 * (per-user registration cadence visible in INFO logs).
 */
class FcmTokenRepository(private val dataSource: DataSource) {
    data class UpsertResult(val created: Boolean, val userTokenCount: Long)

    suspend fun upsert(
        userId: UUID,
        platform: String,
        token: String,
        appVersion: String?,
    ): UpsertResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_UPSERT).use { ps ->
                    ps.setObject(1, userId)
                    ps.setString(2, platform)
                    ps.setString(3, token)
                    if (appVersion != null) {
                        ps.setString(4, appVersion)
                    } else {
                        ps.setNull(4, java.sql.Types.VARCHAR)
                    }
                    // Re-bind user_id for the RETURNING subquery — the table alias
                    // inside the subquery shadows the outer table reference, so we
                    // can't reuse the conflict row's user_id implicitly.
                    ps.setObject(5, userId)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "INSERT ... ON CONFLICT DO UPDATE RETURNING produced no row" }
                        UpsertResult(
                            created = rs.getBoolean("created"),
                            userTokenCount = rs.getLong("user_token_count"),
                        )
                    }
                }
            }
        }

    private companion object {
        const val SQL_UPSERT = """
            INSERT INTO user_fcm_tokens (user_id, platform, token, app_version, last_seen_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (user_id, platform, token)
            DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version
            RETURNING
                xmax = 0 AS created,
                (SELECT COUNT(*) FROM user_fcm_tokens t WHERE t.user_id = ?) AS user_token_count
        """
    }
}
