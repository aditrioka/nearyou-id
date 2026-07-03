package id.nearyou.app.admin.retention

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * The three retention sweeps that the `scheduled-retention-cleanup` worker
 * (`POST /internal/cleanup`) runs on each invocation. Each method is one
 * idempotent threshold `DELETE` returning the number of rows it reclaimed
 * (`executeUpdate()`), per `docs/05-Implementation.md` §§112/582/1120.
 *
 * The thresholds are fixed literals from `docs/05`; none is client- or
 * query-supplied (design Risks/Trade-offs).
 */
interface RetentionCleanupRepository {
    /**
     * Delete every `refresh_tokens` row that is expired
     * (`expires_at < NOW() - INTERVAL '1 day'`) OR stale
     * (`last_used_at < NOW() - INTERVAL '90 days'`), including rows already
     * marked `revoked_at` (the sweep is NOT filtered by `revoked_at`; design
     * D2). `last_used_at` is nullable — a never-used row (`last_used_at IS
     * NULL`) is reaped only via the `expires_at` branch (a NULL comparison is
     * UNKNOWN, never true), so it survives while its expiry is in the future.
     */
    suspend fun deleteExpiredAndStaleRefreshTokens(): Int

    /**
     * Delete every `notifications` row older than the 90-day window
     * (`created_at < NOW() - INTERVAL '90 days'`). Type-agnostic (no `type` is
     * exempted) and NOT filtered by `read_at` (design D2 / spec).
     */
    suspend fun purgeOldNotifications(): Int

    /**
     * Delete every `user_fcm_tokens` row unseen for more than 30 days
     * (`last_seen_at < NOW() - INTERVAL '30 days'`). The scheduled bulk
     * backstop only — the on-send `404/410` single-token delete is owned by
     * `fcm-push-dispatch` and is NOT touched here.
     */
    suspend fun deleteStaleFcmTokens(): Int

    /**
     * Delete every `login_events` row older than the 90-day window
     * (`occurred_at < NOW() - INTERVAL '90 days'`) — the canonical "Session trail"
     * retention (`docs/06` § Retention Policy). The login-history PII purge added by
     * `login-history-tracking`.
     */
    suspend fun deleteOldLoginEvents(): Int
}

/**
 * JDBC [RetentionCleanupRepository]. All work runs on the pool-bounded DB
 * dispatcher (`docs/11-Engineering-Standards.md` §3.2 — never raw
 * `Dispatchers.IO`; production passes `DbDispatchers.db`), mirroring
 * [id.nearyou.app.user.FcmTokenRepository].
 *
 * Each sweep is an independent single-statement `DELETE` on its OWN
 * auto-commit connection (design D4): the tables are unrelated and there are
 * no audit rows to keep atomic, so a failure in one sweep MUST NOT roll back a
 * sibling sweep's already-reclaimed rows. The worker
 * ([RetentionCleanupWorker]) orchestrates the sequencing and count
 * aggregation.
 */
class JdbcRetentionCleanupRepository(
    private val dataSource: DataSource,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RetentionCleanupRepository {
    override suspend fun deleteExpiredAndStaleRefreshTokens(): Int = executeDelete(SQL_REFRESH_TOKENS)

    override suspend fun purgeOldNotifications(): Int = executeDelete(SQL_NOTIFICATIONS)

    override suspend fun deleteStaleFcmTokens(): Int = executeDelete(SQL_FCM_TOKENS)

    override suspend fun deleteOldLoginEvents(): Int = executeDelete(SQL_LOGIN_EVENTS)

    private suspend fun executeDelete(sql: String): Int =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.executeUpdate()
                }
            }
        }

    private companion object {
        // Single OR sweep (design D2): expired-by-expires_at OR stale-by-last_used_at,
        // NOT filtered by revoked_at (revoked rows past either threshold are reclaimed
        // too). last_used_at is nullable → the OR lets the expires_at branch reap a
        // never-used expired row while a never-used unexpired row survives.
        const val SQL_REFRESH_TOKENS =
            """
            DELETE FROM refresh_tokens
             WHERE expires_at < NOW() - INTERVAL '1 day'
                OR last_used_at < NOW() - INTERVAL '90 days'
            """

        // Type-agnostic 90-day purge (no type exempted, not filtered by read_at).
        const val SQL_NOTIFICATIONS =
            "DELETE FROM notifications WHERE created_at < NOW() - INTERVAL '90 days'"

        // Stale FCM tokens unseen for >30 days (index-served by user_fcm_tokens_last_seen_idx, V14).
        const val SQL_FCM_TOKENS =
            "DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'"

        // Login-history PII purge — the 90-day "Session trail" window (docs/06 § Retention),
        // index-served by login_events_user_occurred_idx (V35).
        const val SQL_LOGIN_EVENTS =
            "DELETE FROM login_events WHERE occurred_at < NOW() - INTERVAL '90 days'"
    }
}
