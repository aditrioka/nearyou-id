package id.nearyou.data.repository

import java.time.Instant
import java.util.UUID

/**
 * Read + on-send-delete contract over `user_fcm_tokens`. Defined here in
 * `:core:data` so `:infra:fcm` consumes it without a JDBC dependency; the
 * production impl lives in `:backend:ktor` and reads via the JOOQ/JDBC surface.
 *
 * The `dispatchStartedAt: Instant` carried alongside the token list comes from
 * the database clock domain (`SELECT NOW()` on the same connection as the
 * `SELECT ... FROM user_fcm_tokens` read). Both ends of the race-guard
 * predicate `last_seen_at <= :dispatch_started_at` therefore live in the same
 * clock domain — a JVM `Instant.now()` would introduce JVM-vs-DB clock skew
 * that could cause a freshly-re-registered row to be falsely deleted when the
 * dispatcher races a `POST /api/v1/user/fcm-token` upsert (see
 * `openspec/changes/fcm-push-dispatch/design.md` D12).
 */
interface UserFcmTokenReader {
    /**
     * Single index lookup against `user_fcm_tokens_user_idx`. Returns every
     * row for [userId] AND the DB-clock `NOW()` captured atomically with the
     * token-read on the same connection. Order is platform-stable
     * (`ORDER BY platform, token`) so test snapshots are deterministic, but
     * the order MUST NOT carry semantic meaning to callers.
     */
    fun activeTokens(userId: UUID): TokenSnapshot

    /**
     * Single-row index DELETE guarded by `last_seen_at <= :dispatchStartedAt`.
     * Returns 1 when the row matched the predicate AND was deleted, 0 when
     * either no row matches `(userId, platform, token)` OR the row's
     * `last_seen_at` is now later than [dispatchStartedAt] (a fresh
     * re-registration upsert raced the dispatch — preserve the live row).
     */
    fun deleteTokenIfStale(
        userId: UUID,
        platform: String,
        token: String,
        dispatchStartedAt: Instant,
    ): Int
}

/**
 * Snapshot of `user_fcm_tokens` for a recipient at a single dispatch instant.
 * The [dispatchStartedAt] field is propagated through the dispatch lifecycle
 * to [UserFcmTokenReader.deleteTokenIfStale] as the race-guard predicate.
 */
data class TokenSnapshot(
    val tokens: List<FcmTokenRow>,
    val dispatchStartedAt: Instant,
)

/**
 * One row of `user_fcm_tokens`. The [lastSeenAt] field is exposed so the
 * dispatcher (or its tests) can assert race-guard behaviour; production
 * callers may ignore it, but the field is required for the on-send-prune
 * test surface.
 */
data class FcmTokenRow(
    val platform: String,
    val token: String,
    val lastSeenAt: Instant,
)
