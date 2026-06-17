package id.nearyou.app.subscription

import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * What the status-apply UPDATE should do to `users.privacy_flip_scheduled_at`
 * alongside the `subscription_status` write (privacy-flip-worker capability —
 * the webhook owns scheduling/clearing; the acting worker owns applying). The
 * privacy action is keyed off the EVENT, not the resulting status, so the
 * coupling stays explicit even though only `EXPIRATION` produces `free` and
 * only purchase/renewal produce `premium_active` today.
 */
enum class PrivacyFlipAction {
    /** No privacy-flip column change (BILLING_ISSUE, CANCELLATION). */
    NONE,

    /** EXPIRATION: schedule a 72h flip for private profiles, idempotent via COALESCE. */
    SCHEDULE_IF_PRIVATE,

    /** INITIAL_PURCHASE / RENEWAL: clear any pending flip on re-activation. */
    CLEAR,
}

/**
 * Result of [SubscriptionEventRepository.applyStatus] read off the
 * `UPDATE ... RETURNING` (design D6, read-free). [exists] `false` is the orphan
 * signal. [wasPrivate] is the user's `private_profile_opt_in` (the webhook never
 * changes it, so RETURNING reflects the pre-existing value) — it gates the
 * `privacy_flip_warning` emit. [privacyFlipScheduledAt] is the post-update
 * deadline (the just-set/COALESCE'd value on a private EXPIRATION, `null` after
 * a CLEAR), carried into the warning's `body_data`.
 */
data class StatusApplyResult(
    val exists: Boolean,
    val wasPrivate: Boolean,
    val privacyFlipScheduledAt: Instant?,
)

/**
 * Transactional SQL for the RevenueCat webhook. Both methods take an explicit
 * [Connection] so they ride [SubscriptionService]'s single per-event
 * transaction (status apply + event insert + notification commit/rollback
 * together — `subscription-billing-webhook` spec § "ingestion is idempotent").
 *
 * No `SELECT ... FROM users` is issued: user existence is read off the
 * status `UPDATE ... RETURNING` (design D6, read-free path). An `UPDATE users`
 * by id is NOT a viewer-scoped feed read, so the block-exclusion-join /
 * shadow-ban-view invariants do not apply (precedent: the suspension/unban
 * worker writes `users` directly).
 */
class SubscriptionEventRepository {
    /**
     * Applies [newStatus] to `users.subscription_status` for [userId] and, per
     * [privacyFlip], schedules/clears `users.privacy_flip_scheduled_at` in the
     * same statement; returns the [StatusApplyResult] read off `RETURNING`
     * (`exists = false` is the orphan signal — the RevenueCat `app_user_id`
     * maps to no user). When [newStatus] is `null` (a `CANCELLATION`, which does
     * not change status) the status write is a no-op self-assignment whose sole
     * purpose is the existence check; [privacyFlip] is `NONE` for that path, so
     * the privacy column is untouched. This relies on `users` having no
     * row-touch BEFORE-UPDATE trigger (no `updated_at`-style side effect) — if
     * one is ever added, swap the cancellation path for an explicit existence
     * read carrying `@AllowMissingBlockJoin` so a cancellation doesn't silently
     * bump a row. An `UPDATE users` by id is not a viewer-scoped feed read, so
     * the block-exclusion-join / shadow-ban-view invariants do not apply.
     */
    fun applyStatus(
        conn: Connection,
        userId: UUID,
        newStatus: String?,
        privacyFlip: PrivacyFlipAction,
    ): StatusApplyResult {
        // Both fragments are internal enum-driven literals — no user input in the SQL string.
        val statusSet =
            if (newStatus != null) "subscription_status = ?" else "subscription_status = subscription_status"
        val privacySet =
            when (privacyFlip) {
                PrivacyFlipAction.NONE -> ""
                PrivacyFlipAction.CLEAR -> ", privacy_flip_scheduled_at = NULL"
                PrivacyFlipAction.SCHEDULE_IF_PRIVATE ->
                    ", privacy_flip_scheduled_at = CASE WHEN private_profile_opt_in " +
                        "THEN COALESCE(privacy_flip_scheduled_at, NOW() + INTERVAL '72 hours') " +
                        "ELSE privacy_flip_scheduled_at END"
            }
        val sql =
            "UPDATE users SET $statusSet$privacySet WHERE id = ? " +
                "RETURNING private_profile_opt_in, privacy_flip_scheduled_at"
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            if (newStatus != null) ps.setString(idx++, newStatus)
            ps.setObject(idx, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    StatusApplyResult(exists = false, wasPrivate = false, privacyFlipScheduledAt = null)
                } else {
                    StatusApplyResult(
                        exists = true,
                        wasPrivate = rs.getBoolean("private_profile_opt_in"),
                        privacyFlipScheduledAt = rs.getTimestamp("privacy_flip_scheduled_at")?.toInstant(),
                    )
                }
            }
        }
    }

    /**
     * Idempotent event insert keyed by `revenuecat_event_id` (UNIQUE).
     * `INSERT ... ON CONFLICT (revenuecat_event_id) DO NOTHING RETURNING id`
     * returns a row only on a fresh insert; a re-delivered event produces zero
     * rows. Returns `true` when a new row was written, `false` for a duplicate.
     * The DB resolves the concurrent-delivery race — exactly one of two racing
     * inserts wins.
     */
    fun insertEventIfNew(
        conn: Connection,
        userId: UUID,
        eventType: String,
        source: String,
        revenuecatEventId: String,
        entitlementStart: Instant?,
        entitlementEnd: Instant?,
        amountRupiah: Long?,
        platform: String?,
    ): Boolean =
        conn.prepareStatement(SQL_INSERT_EVENT).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, eventType)
            ps.setString(3, source)
            ps.setString(4, revenuecatEventId)
            ps.setObjectOrNull(5, entitlementStart?.let { java.sql.Timestamp.from(it) }, Types.TIMESTAMP)
            ps.setObjectOrNull(6, entitlementEnd?.let { java.sql.Timestamp.from(it) }, Types.TIMESTAMP)
            ps.setObjectOrNull(7, amountRupiah, Types.BIGINT)
            ps.setObjectOrNull(8, platform, Types.VARCHAR)
            ps.executeQuery().use { rs -> rs.next() }
        }

    private fun java.sql.PreparedStatement.setObjectOrNull(
        index: Int,
        value: Any?,
        sqlType: Int,
    ) {
        if (value == null) setNull(index, sqlType) else setObject(index, value)
    }

    private companion object {
        const val SQL_INSERT_EVENT = """
            INSERT INTO subscription_events (
                user_id, event_type, source, revenuecat_event_id,
                entitlement_start, entitlement_end, amount_rupiah, platform
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (revenuecat_event_id) DO NOTHING
            RETURNING id
        """
    }
}
