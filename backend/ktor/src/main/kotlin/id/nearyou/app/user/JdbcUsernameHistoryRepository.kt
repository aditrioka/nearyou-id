package id.nearyou.app.user

import id.nearyou.app.auth.signup.UsernameHistoryRepository
import java.sql.Connection
import java.util.UUID

/**
 * Real JDBC binding for [UsernameHistoryRepository] — the `username_history`
 * table (V3) read + write for the 30-day release hold. Replaces
 * [id.nearyou.app.auth.signup.NoopUsernameHistoryRepository] in production DI,
 * so signup AND the Premium username-change path both honour the hold.
 *
 * All methods take the caller's [Connection]: `existsOnHold` runs inside the
 * change's `FOR UPDATE` transaction (authoritative re-check), and
 * `insertReleaseHold` commits atomically with the `users.username` write.
 */
class JdbcUsernameHistoryRepository : UsernameHistoryRepository {
    override fun existsOnHold(
        conn: Connection,
        lowercaseUsername: String,
    ): Boolean {
        // Uses username_history_old_lower_idx (LOWER(old_username)). released_at is
        // a plain B-tree (NOW() is STABLE, not IMMUTABLE — no partial index on it).
        conn.prepareStatement(
            """
            SELECT 1 FROM username_history
             WHERE LOWER(old_username) = ? AND released_at > NOW()
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, lowercaseUsername)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun insertReleaseHold(
        conn: Connection,
        userId: UUID,
        oldUsername: String,
        newUsername: String,
    ): java.time.Instant {
        // released_at = changed_at + 30 days (changed_at defaults to NOW()); the
        // old handle is unclaimable until released_at passes (anti-impersonation).
        // RETURNING released_at so the caller's confirmation notification carries
        // the exact value written to the row.
        conn.prepareStatement(
            """
            INSERT INTO username_history (user_id, old_username, new_username, released_at)
            VALUES (?, ?, ?, NOW() + INTERVAL '30 days')
            RETURNING released_at
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, oldUsername)
            ps.setString(3, newUsername)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT … RETURNING released_at returned no row" }
                return rs.getTimestamp("released_at").toInstant()
            }
        }
    }
}
