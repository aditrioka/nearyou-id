package id.nearyou.app.user

import id.nearyou.data.repository.FcmTokenRow
import id.nearyou.data.repository.TokenSnapshot
import id.nearyou.data.repository.UserFcmTokenReader
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Production [UserFcmTokenReader] backed by the existing `user_fcm_tokens`
 * schema (V14). The read query AND the `dispatchStartedAt = NOW()` capture
 * run on the SAME JDBC connection so both ends of the on-send-prune
 * race-guard predicate (`last_seen_at <= :dispatch_started_at`) live in the
 * DB clock domain — eliminates the JVM-vs-DB clock-skew false-delete vector
 * documented at `openspec/changes/fcm-push-dispatch/design.md` D12.
 *
 * The DELETE includes the predicate so a freshly re-registered token (whose
 * `last_seen_at` advanced past the captured `dispatchStartedAt` while the
 * dispatch was in flight) is preserved.
 */
class JdbcUserFcmTokenReader(
    private val dataSource: DataSource,
) : UserFcmTokenReader {
    override fun activeTokens(userId: UUID): TokenSnapshot {
        dataSource.connection.use { conn ->
            // Capture NOW() and read tokens on the same connection so both
            // ends of the race-guard predicate come from the DB clock domain.
            val dispatchStartedAt: Instant =
                conn.prepareStatement("SELECT NOW()").use { ps ->
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "SELECT NOW() returned no row" }
                        rs.getTimestamp(1).toInstant()
                    }
                }
            val tokens =
                conn.prepareStatement(SQL_SELECT_TOKENS).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<FcmTokenRow>()
                        while (rs.next()) {
                            out +=
                                FcmTokenRow(
                                    platform = rs.getString("platform"),
                                    token = rs.getString("token"),
                                    lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                                )
                        }
                        out
                    }
                }
            return TokenSnapshot(tokens = tokens, dispatchStartedAt = dispatchStartedAt)
        }
    }

    override fun deleteTokenIfStale(
        userId: UUID,
        platform: String,
        token: String,
        dispatchStartedAt: Instant,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_DELETE_IF_STALE).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, platform)
                ps.setString(3, token)
                ps.setTimestamp(4, Timestamp.from(dispatchStartedAt))
                return ps.executeUpdate()
            }
        }
    }

    private companion object {
        const val SQL_SELECT_TOKENS = """
            SELECT platform, token, last_seen_at
              FROM user_fcm_tokens
             WHERE user_id = ?
             ORDER BY platform, token
        """

        const val SQL_DELETE_IF_STALE = """
            DELETE FROM user_fcm_tokens
             WHERE user_id = ?
               AND platform = ?
               AND token = ?
               AND last_seen_at <= ?
        """
    }
}
