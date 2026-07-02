package id.nearyou.app.account

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("id.nearyou.app.account.AccountHardDeleteWorker")

/** Result of one [AccountHardDeleteWorker.execute] run. */
data class HardDeleteResult(
    val deletedCount: Int,
    val durationMs: Long,
)

/**
 * Daily worker (Cloud-Scheduler-invoked via `/internal/account-hard-delete-worker`)
 * that executes due account deletions per the `account-hard-delete-worker`
 * capability + docs/06 § Account Deletion (the **tombstone** model — the `users`
 * row is `UPDATE`d, never row-deleted).
 *
 * For each due `deletion_requests` row, in its OWN transaction:
 *  1. Claim it with `FOR UPDATE SKIP LOCKED` (re-checking due/un-cancelled/un-executed)
 *     so concurrent invocations never double-process and a cancel racing the worker
 *     resolves deterministically.
 *  2. Tombstone the user (set `deleted_at`, erase PII — placeholder/sentinel for the
 *     `NOT NULL` columns, NULL the nullable ones, rename `username`).
 *  3. Cascade-DELETE the ephemeral/relational data (refresh tokens, follows + blocks
 *     both directions, FCM tokens, addressed notifications) — explicit DELETEs because
 *     the un-row-deleted user never fires the FK cascades.
 *  4. Insert a `deletion_log` row and stamp `executed_at` — atomic with the mutations.
 *
 * Authored content (posts/replies/likes/edits/chat/reports) is deliberately RETAINED;
 * it anonymizes against the tombstoned row. A failing row is rolled back (no partial
 * tombstone), logged, and left due (`executed_at IS NULL`) for the next run — it does
 * NOT block the rest of the batch. Re-runs skip executed rows.
 *
 * Thread-safe: holds no state across calls; one connection per row.
 */
class AccountHardDeleteWorker(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(): HardDeleteResult =
        withContext(dbDispatcher) {
            val startNanos = System.nanoTime()
            val candidates = snapshotCandidates()
            var deleted = 0
            for (requestId in candidates) {
                if (processOne(requestId)) deleted++
            }
            HardDeleteResult(
                deletedCount = deleted,
                durationMs = (System.nanoTime() - startNanos) / 1_000_000L,
            )
        }

    /**
     * Synchronously execute a single due deletion row by id, reusing the exact
     * per-row path [execute] uses ([processOne]: claim `FOR UPDATE SKIP LOCKED` →
     * tombstone+cascade → `deletion_log` → stamp `executed_at`). Idempotent and a
     * no-op if the row was already executed/cancelled or is claimed concurrently.
     *
     * The `apple-s2s-deletion-flows` handler calls this inline for an
     * `apple_s2s_account_delete` row (immediate, before the `200` to Apple). If it
     * throws or no-ops, the row stays due (`executed_at IS NULL`) and the daily
     * worker backstops it via `deletion_requests_immediate_idx`. Returns `true` iff
     * this call tombstoned the row.
     */
    suspend fun executeImmediate(requestId: UUID): Boolean = withContext(dbDispatcher) { processOne(requestId) }

    /** Phase 1: snapshot the due, un-cancelled, un-executed request ids (no lock held). */
    private fun snapshotCandidates(): List<UUID> {
        val ids = mutableListOf<UUID>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_CANDIDATES).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) ids += UUID.fromString(rs.getString("id"))
                }
            }
        }
        return ids
    }

    /**
     * Phase 2: process one candidate in its own transaction. Returns true if it was
     * tombstoned, false if it was skipped (claimed/cancelled by another runner) or
     * failed (rolled back, left due for retry).
     */
    private fun processOne(requestId: UUID): Boolean {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            return try {
                val claim = claimRow(conn, requestId)
                if (claim == null) {
                    conn.rollback()
                    false // already taken (SKIP LOCKED), cancelled, or executed since the snapshot
                } else {
                    val tombstoned = tombstoneAndCascade(conn, requestId, claim.userId, claim.source)
                    conn.commit()
                    tombstoned
                }
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                // Failing row stays due (executed_at IS NULL) for the next scheduled run;
                // does NOT block the batch. Error CLASS only — no throwable arg: an
                // SQLException message can embed bound key values (a user_id), and the
                // apple-s2s-deletion-flows spec requires no PII on any log path.
                logger.warn("event=account_hard_delete_row_failed error_class={}", e::class.simpleName)
                false
            }
        }
    }

    private data class Claim(val userId: UUID, val source: String)

    /** Lock + re-check the row; null when another runner holds it or it is no longer due. */
    private fun claimRow(
        conn: Connection,
        requestId: UUID,
    ): Claim? =
        conn.prepareStatement(SQL_CLAIM).use { ps ->
            ps.setObject(1, requestId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    Claim(UUID.fromString(rs.getString("user_id")), rs.getString("source"))
                } else {
                    null
                }
            }
        }

    /**
     * Returns `true` iff THIS call tombstoned the user. Per-USER idempotency guard
     * (apple-s2s-deletion-flows review): an `apple_s2s_account_delete` escalation can
     * leave an older grace row pending for an already-tombstoned user — when that row
     * later becomes due, the tombstone UPDATE matches nothing (`deleted_at IS NULL`
     * guard), and the row is mooted (stamped executed, NO second `deletion_log` entry,
     * no re-cascade) instead of re-processing the user.
     */
    private fun tombstoneAndCascade(
        conn: Connection,
        requestId: UUID,
        userId: UUID,
        source: String,
    ): Boolean {
        // 2. Tombstone the user (UPDATE — never a row-delete). Zero rows updated =
        //    user already tombstoned by an earlier request row → moot row.
        val tombstoned =
            conn.prepareStatement(SQL_TOMBSTONE).use { ps ->
                ps.setObject(1, userId)
                ps.executeUpdate()
            }
        if (tombstoned == 0) {
            execOne(conn, SQL_MARK_EXECUTED, requestId)
            return false
        }
        // 3. Cascade-DELETE ephemeral/relational data (explicit — the un-row-deleted
        //    user never fires the FK cascades). Both directions for follows + blocks.
        execOne(conn, SQL_DEL_REFRESH, userId)
        execTwo(conn, SQL_DEL_FOLLOWS, userId, userId)
        execTwo(conn, SQL_DEL_BLOCKS, userId, userId)
        execOne(conn, SQL_DEL_FCM, userId)
        execOne(conn, SQL_DEL_NOTIFS, userId)
        execOne(conn, SQL_DEL_LOGIN_EVENTS, userId)
        // 4. deletion_log + mark executed (atomic with the above; same transaction).
        conn.prepareStatement(SQL_INSERT_LOG).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, source)
            ps.executeUpdate()
        }
        execOne(conn, SQL_MARK_EXECUTED, requestId)
        return true
    }

    private fun execOne(
        conn: Connection,
        sql: String,
        id: UUID,
    ) {
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
    }

    private fun execTwo(
        conn: Connection,
        sql: String,
        a: UUID,
        b: UUID,
    ) {
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, a)
            ps.setObject(2, b)
            ps.executeUpdate()
        }
    }

    private companion object {
        const val SQL_CANDIDATES =
            """
            SELECT id FROM deletion_requests
             WHERE scheduled_hard_delete_at <= NOW()
               AND executed_at IS NULL
               AND cancelled_at IS NULL
            """

        const val SQL_CLAIM =
            """
            SELECT user_id, source FROM deletion_requests
             WHERE id = ?
               AND scheduled_hard_delete_at <= NOW()
               AND executed_at IS NULL
               AND cancelled_at IS NULL
             FOR UPDATE SKIP LOCKED
            """

        // Tombstone: erase PII. NOT-NULL columns (display_name, date_of_birth) take a
        // placeholder/sentinel (you cannot NULL them); nullable PII is NULLed; username
        // is renamed to a unique deleted_user_ handle. `deleted_at IS NULL` = the
        // per-user idempotency guard (see tombstoneAndCascade KDoc).
        // @allow-username-write: deletion  (username + display_name erasure on the tombstoned row)
        const val SQL_TOMBSTONE =
            """
            UPDATE users SET
                deleted_at              = NOW(),
                display_name            = 'Akun Dihapus',
                bio                     = NULL,
                email                   = NULL,
                google_id_hash          = NULL,
                apple_id_hash           = NULL,
                device_fingerprint_hash = NULL,
                date_of_birth           = DATE '1900-01-01',
                apple_relay_email       = FALSE,
                username                = 'deleted_user_' || left(id::text, 8)
             WHERE id = ? AND deleted_at IS NULL
            """

        const val SQL_DEL_REFRESH = "DELETE FROM refresh_tokens WHERE user_id = ?"
        const val SQL_DEL_FOLLOWS = "DELETE FROM follows WHERE follower_id = ? OR followee_id = ?"
        const val SQL_DEL_BLOCKS = "DELETE FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?"
        const val SQL_DEL_FCM = "DELETE FROM user_fcm_tokens WHERE user_id = ?"
        const val SQL_DEL_NOTIFS = "DELETE FROM notifications WHERE user_id = ?"

        // login-history (V35) — explicit (the tombstoned, un-row-deleted user never fires the
        // login_events FK ON DELETE CASCADE). Erases the departing user's IP / device / identity.
        const val SQL_DEL_LOGIN_EVENTS = "DELETE FROM login_events WHERE user_id = ?"
        const val SQL_INSERT_LOG = "INSERT INTO deletion_log (user_id, source) VALUES (?, ?)"
        const val SQL_MARK_EXECUTED = "UPDATE deletion_requests SET executed_at = NOW() WHERE id = ?"
    }
}
