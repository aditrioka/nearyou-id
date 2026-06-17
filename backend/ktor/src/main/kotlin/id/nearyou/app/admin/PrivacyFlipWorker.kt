package id.nearyou.app.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * Result of a single [PrivacyFlipWorker.execute] run. The handler in
 * [PrivacyFlipWorkerRoute] uses [flippedCount] for the response body and
 * [flippedUserIds] for the structured INFO log (capped at the first
 * [MAX_LOGGED_USER_IDS] by the route, not by the worker).
 */
data class PrivacyFlipResult(
    val flippedCount: Int,
    val flippedUserIds: List<UUID>,
    val durationMs: Long,
)

/**
 * Hourly worker that applies elapsed Premium→Free privacy flips: for every user
 * whose 72h grace window has passed it sets `private_profile_opt_in = FALSE` and
 * clears `privacy_flip_scheduled_at`, writing one immutable `admin_actions_log`
 * audit row per flip, atomically, in a single data-modifying CTE (mirrors
 * [SuspensionUnbanWorker] design D4). The audit write is attributed to the
 * seeded `system` sentinel admin actor ([SuspensionUnbanWorker.SYSTEM_ACTOR_ID]).
 *
 * The deadline was set by the RevenueCat `EXPIRATION` webhook handler
 * (`subscription-billing-webhook`); the "effectively still private during the
 * window" read short-circuit already lives in `JdbcUserProfileReader` — by the
 * deadline the profile already reads public (status is `free` and the `> now()`
 * guard is false), so this worker is the durability/bookkeeping step, not the
 * privacy-enforcement boundary.
 *
 * Thread-safe: holds no state across calls. Each `execute()` opens a fresh JDBC
 * connection, runs one transaction, closes the connection.
 *
 * Atomicity: the UPDATE and the audit INSERT are one statement in one
 * transaction, so a failed audit INSERT rolls back the flip (the user stays
 * private). Cloud Scheduler's retry re-runs the idempotent endpoint.
 *
 * Idempotency: the flip clears `privacy_flip_scheduled_at`, so the WHERE
 * predicate naturally filters already-flipped rows — a retry returns
 * `flippedCount = 0` AND writes zero audit rows.
 */
class PrivacyFlipWorker(
    private val dataSource: DataSource,
) {
    suspend fun execute(): PrivacyFlipResult =
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
            PrivacyFlipResult(
                flippedCount = ids.size,
                flippedUserIds = ids,
                durationMs = durationMs,
            )
        }

    companion object {
        // Single data-modifying CTE (mirrors SuspensionUnbanWorker design D4):
        // snapshot the elapsed-grace rows FOR UPDATE -> flip them (private off +
        // clear the deadline) -> INSERT one admin_actions_log row per flip (atomic
        // with the UPDATE) -> RETURNING the affected user ids. Eligibility filters
        // ONLY on the deadline + deleted_at (NOT on private_profile_opt_in), so a
        // soft-deleted row is left for the admin monitor as a stuck-row anomaly and
        // a stale-scheduled already-public row still has its deadline cleaned up.
        // before_state captures the ACTUAL prior private flag (true for real flips,
        // since only the EXPIRATION webhook sets the deadline and only for private
        // users). The admin_id literal matches SYSTEM_ACTOR_ID (drift caught by the
        // audit-row test asserting admin_id == SYSTEM_ACTOR_ID + the FK to the
        // seeded sentinel). `action_type` parallels the unban worker's
        // `system_unban_applied` (admin_actions_log.action_type is a plain
        // VARCHAR(64) — no enum CHECK, V16 — so no migration is needed).
        val SQL: String =
            """
            WITH eligible AS (
                SELECT id, privacy_flip_scheduled_at, private_profile_opt_in
                  FROM users
                 WHERE privacy_flip_scheduled_at IS NOT NULL
                   AND privacy_flip_scheduled_at <= NOW()
                   AND deleted_at IS NULL
                 FOR UPDATE
            ),
            flipped AS (
                -- @allow-privacy-write: worker (the sanctioned privacy-flag writer —
                -- privacy-flip-worker capability; mirrors the EXPIRATION grace flow)
                UPDATE users u
                   SET private_profile_opt_in = FALSE, privacy_flip_scheduled_at = NULL
                  FROM eligible
                 WHERE u.id = eligible.id
                RETURNING u.id,
                          eligible.privacy_flip_scheduled_at AS prev_deadline,
                          eligible.private_profile_opt_in    AS prev_private
            )
            INSERT INTO admin_actions_log
                (admin_id, action_type, target_type, target_id, reason, before_state, after_state)
            SELECT
                '${SuspensionUnbanWorker.SYSTEM_ACTOR_ID}'::uuid,
                'system_privacy_flip_applied', 'user', flipped.id::text, 'premium_lapsed_grace_elapsed',
                jsonb_build_object('private_profile_opt_in', flipped.prev_private, 'privacy_flip_scheduled_at', flipped.prev_deadline),
                jsonb_build_object('private_profile_opt_in', false, 'privacy_flip_scheduled_at', null)
              FROM flipped
            RETURNING target_id
            """
    }
}
