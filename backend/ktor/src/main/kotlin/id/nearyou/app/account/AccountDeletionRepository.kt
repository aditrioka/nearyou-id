package id.nearyou.app.account

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC for the user-initiated account-deletion lifecycle (`account-deletion`
 * capability): request (idempotent), cancel/restore within grace, and status read
 * against the `deletion_requests` table (V23).
 *
 * Own-account writes — the `userId` is always the verified JWT `sub` of the caller
 * (the routes accept no `user_id` param, so there is no IDOR surface). The table is
 * not a `visible_*` view nor one of the block/shadow-ban-protected tables, so no
 * `@allow-*-write` annotation and no block/shadow-ban join applies (the lint rules
 * match `FROM posts|users|...`).
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2); production passes
 * `DbDispatchers.db`.
 */
class AccountDeletionRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Idempotently schedules deletion for [userId] with a 30-day grace. If a
     * pending (un-cancelled, un-executed) request already exists, returns its
     * existing `scheduled_hard_delete_at` WITHOUT inserting a second row; otherwise
     * inserts a fresh `source = 'user'` row at `NOW() + 30 days` and returns that.
     * Atomic (single statement), so a sequential re-request is a pure no-op insert.
     */
    suspend fun requestDeletion(userId: UUID): Instant =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_REQUEST).use { ps ->
                    ps.setObject(1, userId)
                    ps.setObject(2, userId)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "deletion-request returned no scheduled timestamp" }
                        rs.getTimestamp("scheduled_hard_delete_at").toInstant()
                    }
                }
            }
        }

    /**
     * Cancels the caller's pending deletion (sets `cancelled_at = NOW()`), restoring
     * the account. Matches only an un-executed, un-cancelled, non-`apple_s2s_account_delete`
     * row. Returns `true` when a row was cancelled, `false` when there was nothing to
     * cancel (no pending request, already executed, or a non-cancellable source).
     */
    suspend fun cancelDeletion(userId: UUID): Boolean =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_CANCEL).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    /**
     * Returns the `scheduled_hard_delete_at` of the caller's pending deletion, or
     * `null` when none is scheduled. Never leaks another user's state (filtered on
     * `user_id`).
     */
    suspend fun getStatus(userId: UUID): Instant? =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_STATUS).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getTimestamp("scheduled_hard_delete_at").toInstant() else null
                    }
                }
            }
        }

    private companion object {
        // Atomic idempotent request: return the existing pending schedule if one
        // exists, else INSERT a fresh 30-day-grace row and return its schedule.
        const val SQL_REQUEST =
            """
            WITH existing AS (
                SELECT scheduled_hard_delete_at
                  FROM deletion_requests
                 WHERE user_id = ? AND cancelled_at IS NULL AND executed_at IS NULL
                 LIMIT 1
            ),
            inserted AS (
                INSERT INTO deletion_requests (user_id, scheduled_hard_delete_at, source)
                SELECT ?, NOW() + INTERVAL '30 days', 'user'
                 WHERE NOT EXISTS (SELECT 1 FROM existing)
                RETURNING scheduled_hard_delete_at
            )
            SELECT scheduled_hard_delete_at FROM inserted
            UNION ALL
            SELECT scheduled_hard_delete_at FROM existing
            LIMIT 1
            """

        const val SQL_CANCEL =
            """
            UPDATE deletion_requests
               SET cancelled_at = NOW()
             WHERE user_id = ?
               AND executed_at IS NULL
               AND cancelled_at IS NULL
               AND source <> 'apple_s2s_account_delete'
            RETURNING id
            """

        const val SQL_STATUS =
            """
            SELECT scheduled_hard_delete_at
              FROM deletion_requests
             WHERE user_id = ? AND cancelled_at IS NULL AND executed_at IS NULL
             ORDER BY requested_at DESC
             LIMIT 1
            """
    }
}
