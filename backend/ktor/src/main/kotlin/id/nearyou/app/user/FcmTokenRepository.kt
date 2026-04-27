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
 * The per-user count is read in a SECOND statement on the same JDBC
 * connection (auto-commit). Subqueries embedded inside an `INSERT ... ON
 * CONFLICT ... RETURNING` clause use the snapshot taken at the START of
 * the modifying statement, so they cannot see the just-inserted row;
 * a separate statement on the same connection observes the post-upsert
 * state. Powers the design D3 tripwire (per-user registration cadence
 * visible in INFO logs). Both statements run inside `Dispatchers.IO`.
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
                val created =
                    conn.prepareStatement(SQL_UPSERT).use { ps ->
                        ps.setObject(1, userId)
                        ps.setString(2, platform)
                        ps.setString(3, token)
                        if (appVersion != null) {
                            ps.setString(4, appVersion)
                        } else {
                            ps.setNull(4, java.sql.Types.VARCHAR)
                        }
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "INSERT ... ON CONFLICT DO UPDATE RETURNING produced no row" }
                            rs.getBoolean("created")
                        }
                    }
                val userTokenCount =
                    conn.prepareStatement(SQL_COUNT_BY_USER).use { ps ->
                        ps.setObject(1, userId)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "SELECT COUNT(*) returned no row" }
                            rs.getLong(1)
                        }
                    }
                UpsertResult(created = created, userTokenCount = userTokenCount)
            }
        }

    private companion object {
        const val SQL_UPSERT = """
            INSERT INTO user_fcm_tokens (user_id, platform, token, app_version, last_seen_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (user_id, platform, token)
            DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version
            RETURNING xmax = 0 AS created
        """

        const val SQL_COUNT_BY_USER =
            "SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ?"
    }
}
