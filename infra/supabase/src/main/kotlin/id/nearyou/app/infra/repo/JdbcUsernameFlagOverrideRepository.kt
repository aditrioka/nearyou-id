package id.nearyou.app.infra.repo

import id.nearyou.data.repository.UsernameFlagOverrideRepository
import java.sql.Connection
import java.util.UUID

/**
 * JDBC binding for [UsernameFlagOverrideRepository] — the `username_flag_overrides`
 * table (V23). All methods take the caller's [Connection] so the consult/consume run
 * inside the username-change `FOR UPDATE` transaction and the upsert runs inside the
 * admin resolve transaction.
 *
 * Every `candidate` is expected normalized lowercase by the caller (see the
 * interface contract); these statements do NOT re-normalize.
 */
class JdbcUsernameFlagOverrideRepository : UsernameFlagOverrideRepository {
    override fun findUnconsumed(
        conn: Connection,
        userId: UUID,
        candidate: String,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM username_flag_overrides
             WHERE user_id = ? AND candidate = ? AND consumed_at IS NULL
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, candidate)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun consume(
        conn: Connection,
        userId: UUID,
        candidate: String,
    ): Int {
        conn.prepareStatement(
            """
            UPDATE username_flag_overrides
               SET consumed_at = NOW()
             WHERE user_id = ? AND candidate = ? AND consumed_at IS NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, candidate)
            return ps.executeUpdate()
        }
    }

    override fun upsertApproval(
        conn: Connection,
        userId: UUID,
        candidate: String,
        approvedBy: UUID,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO username_flag_overrides (user_id, candidate, approved_by)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, candidate)
            DO UPDATE SET approved_by = EXCLUDED.approved_by, approved_at = NOW(), consumed_at = NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, candidate)
            ps.setObject(3, approvedBy)
            ps.executeUpdate()
        }
    }
}
