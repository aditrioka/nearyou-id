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
 * Phase 1 worker that flips elapsed time-bound suspensions back to active AND
 * writes one immutable `admin_actions_log` audit row per unban, atomically, in a
 * single data-modifying CTE (`system-actor-and-worker-audit-rows` design D4). The
 * four-conjunct eligibility predicate is preserved verbatim inside the `eligible`
 * CTE per the `suspension-unban-worker` capability spec; the audit write is
 * attributed to the seeded `system` sentinel admin user ([SYSTEM_ACTOR_ID]).
 *
 * Thread-safe: the worker holds no state across calls. Each `execute()` opens a
 * fresh JDBC connection, runs one transaction, closes the connection.
 *
 * Atomicity: the UPDATE and the audit INSERT are one statement in one transaction,
 * so a failed audit INSERT rolls back the unban (the user stays banned). Cloud
 * Scheduler's retry re-runs the idempotent endpoint.
 *
 * Idempotency: the WHERE predicate naturally filters already-flipped rows, so a
 * retry returns `unbannedCount = 0` AND writes zero audit rows. See the
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
                                ids += UUID.fromString(rs.getString("target_id"))
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
        /**
         * Deterministic `UUID.nameUUIDFromBytes("system")` — the `system` sentinel
         * admin actor seeded by `V18__seed_system_actor.sql`. `SystemActorTest`
         * asserts this equals `UUID.nameUUIDFromBytes("system".toByteArray())` and
         * the migration literal, so the hardcoded SQL UUID below and this constant
         * cannot silently drift.
         */
        val SYSTEM_ACTOR_ID: UUID = UUID.fromString("54b53072-540e-3eb8-b8e9-343e71f28176")

        // Single data-modifying CTE (design D4): snapshot the eligible rows
        // FOR UPDATE (the four-conjunct predicate is verbatim from
        // docs/05-Implementation.md and MUST NOT be relaxed) -> UPDATE them ->
        // INSERT one admin_actions_log row per unban (atomic with the UPDATE) ->
        // RETURNING the affected user ids (drives unbanned_count + the INFO log's
        // unbanned_user_ids). Snapshotting suspended_until in `eligible` preserves
        // each user's prior expiry for before_state (post-UPDATE RETURNING would
        // only see the NULLed value). The admin_id literal matches SYSTEM_ACTOR_ID
        // (drift is caught by the audit-row integration test asserting
        // admin_id == SYSTEM_ACTOR_ID + the FK to the seeded sentinel row).
        val SQL: String =
            """
            WITH eligible AS (
                SELECT id, suspended_until
                  FROM users
                 WHERE is_banned = TRUE
                   AND suspended_until IS NOT NULL
                   AND suspended_until <= NOW()
                   AND deleted_at IS NULL
                 FOR UPDATE
            ),
            unbanned AS (
                UPDATE users u
                   SET is_banned = FALSE, suspended_until = NULL
                  FROM eligible
                 WHERE u.id = eligible.id
                RETURNING u.id, eligible.suspended_until AS prev_suspended_until
            )
            INSERT INTO admin_actions_log
                (admin_id, action_type, target_type, target_id, reason, before_state, after_state)
            SELECT
                '$SYSTEM_ACTOR_ID'::uuid,
                'system_unban_applied', 'user', unbanned.id::text, 'suspension_elapsed',
                jsonb_build_object('is_banned', true,  'suspended_until', unbanned.prev_suspended_until),
                jsonb_build_object('is_banned', false, 'suspended_until', null)
              FROM unbanned
            RETURNING target_id
            """
    }
}
