package id.nearyou.app.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Result of a single [SuspensionUnbanWorker.execute] run. The handler in
 * [UnbanWorkerRoute] uses [unbannedCount] for the response body and
 * [unbannedUserIds] for the structured INFO log (capped at the first 50 by the
 * route, not by the worker).
 */
data class UnbanResult(
    val unbannedCount: Int,
    val unbannedUserIds: List<UUID>,
    val durationMs: Long,
)

/**
 * Phase 1 worker that flips elapsed time-bound suspensions back to active. The
 * SQL is verbatim from `docs/05-Implementation.md` § Suspension Unban Worker
 * (the canonical predicate), with `RETURNING id` added so the handler can populate
 * the structured INFO log's `unbanned_user_ids` field.
 *
 * Thread-safe: the worker holds no state across calls. Each `execute()` opens a
 * fresh JDBC connection, runs one transaction, closes the connection.
 *
 * Idempotency: the WHERE predicate naturally filters already-flipped rows, so a
 * retry within the same minute returns `unbannedCount = 0`. See the
 * `suspension-unban-worker` capability spec § "Endpoint is idempotent".
 */
class SuspensionUnbanWorker(
    private val dataSource: DataSource,
) {
    suspend fun execute(): UnbanResult =
        withContext(Dispatchers.IO) {
            val startNanos = System.nanoTime()
            val ids = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(SQL).use { ps ->
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                ids += rs.getObject("id", UUID::class.java)
                            }
                        }
                    }
                    conn.commit()
                } catch (e: Throwable) {
                    runCatching { conn.rollback() }
                    throw e
                }
            }
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
            UnbanResult(
                unbannedCount = ids.size,
                unbannedUserIds = ids,
                durationMs = durationMs,
            )
        }

    companion object {
        // Verbatim from docs/05-Implementation.md § Suspension Unban Worker (with
        // RETURNING id appended). The four WHERE conjuncts are required by the
        // capability spec and MUST NOT be relaxed even though the partial index
        // `users_suspended_idx` already implies the second conjunct.
        const val SQL: String =
            """
            UPDATE users
            SET is_banned = FALSE,
                suspended_until = NULL
            WHERE is_banned = TRUE
              AND suspended_until IS NOT NULL
              AND suspended_until <= NOW()
              AND deleted_at IS NULL
            RETURNING id
            """
    }
}
