package id.nearyou.app.subscription

import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.util.UUID

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
     * Applies [newStatus] to `users.subscription_status` for [userId] and
     * returns whether a row matched — `false` is the orphan signal (the
     * RevenueCat `app_user_id` maps to no user). When [newStatus] is `null`
     * (a `CANCELLATION`, which does not change status) the statement is a
     * no-op self-assignment whose sole purpose is the existence check; it
     * rolls back cleanly with the surrounding transaction if the event turns
     * out to be a duplicate.
     */
    fun applyStatusReturningExists(
        conn: Connection,
        userId: UUID,
        newStatus: String?,
    ): Boolean {
        val sql =
            if (newStatus != null) {
                "UPDATE users SET subscription_status = ? WHERE id = ? RETURNING id"
            } else {
                // Existence touch only — see KDoc. Self-assignment leaves the value
                // unchanged; the RETURNING row tells us the user exists.
                "UPDATE users SET subscription_status = subscription_status WHERE id = ? RETURNING id"
            }
        return conn.prepareStatement(sql).use { ps ->
            if (newStatus != null) {
                ps.setString(1, newStatus)
                ps.setObject(2, userId)
            } else {
                ps.setObject(1, userId)
            }
            ps.executeQuery().use { rs -> rs.next() }
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
