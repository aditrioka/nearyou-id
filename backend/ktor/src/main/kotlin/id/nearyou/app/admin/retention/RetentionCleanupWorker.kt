package id.nearyou.app.admin.retention

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("id.nearyou.app.admin.retention.RetentionCleanupWorker")

/**
 * Result of a single [RetentionCleanupWorker.execute] run — the per-sweep
 * deleted-row counts the route ([retentionCleanupRoutes]) returns in the
 * response body.
 */
data class RetentionCleanupResult(
    val refreshTokensDeleted: Int,
    val notificationsDeleted: Int,
    val fcmTokensDeleted: Int,
)

/**
 * Daily worker (Cloud-Scheduler-invoked via `/internal/cleanup`) that enforces
 * the three written retention windows (`scheduled-retention-cleanup`
 * capability + `docs/05` §§112/582/1120):
 *
 *  1. `refresh_tokens` — expired (`expires_at < NOW() - INTERVAL '1 day'`) OR
 *     stale (`last_used_at < NOW() - INTERVAL '90 days'`), revoked rows included.
 *  2. `notifications`  — `created_at < NOW() - INTERVAL '90 days'` (type-agnostic).
 *  3. `user_fcm_tokens` — `last_seen_at < NOW() - INTERVAL '30 days'`.
 *
 * The three sweeps run sequentially, each an independent statement on its own
 * connection (design D4 — one sweep's failure does not roll back a sibling's
 * reclaimed rows). The run measures its duration and emits exactly ONE
 * structured INFO log line (`event=retention_cleanup`) carrying the three
 * counts + `duration_ms` (design D3 — no per-row logging, no
 * `admin_actions_log` writes, no `system` sentinel actor: routine hygiene, not
 * a user-visible state change). Mirrors the single-log discipline of
 * [id.nearyou.app.admin.PrivacyFlipWorker].
 *
 * Idempotent: each sweep is a threshold `DELETE`, so a re-run with no
 * newly-aged rows reclaims zero and returns all-zero counts.
 *
 * Thread-safe: holds no state across calls.
 */
class RetentionCleanupWorker(
    private val repository: RetentionCleanupRepository,
) {
    suspend fun execute(): RetentionCleanupResult {
        val startNanos = System.nanoTime()
        val refreshTokensDeleted = repository.deleteExpiredAndStaleRefreshTokens()
        val notificationsDeleted = repository.purgeOldNotifications()
        val fcmTokensDeleted = repository.deleteStaleFcmTokens()
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000L

        logger.info(
            "event=retention_cleanup refresh_tokens_deleted={} notifications_deleted={} fcm_tokens_deleted={} duration_ms={}",
            refreshTokensDeleted,
            notificationsDeleted,
            fcmTokensDeleted,
            durationMs,
        )

        return RetentionCleanupResult(
            refreshTokensDeleted = refreshTokensDeleted,
            notificationsDeleted = notificationsDeleted,
            fcmTokensDeleted = fcmTokensDeleted,
        )
    }
}
