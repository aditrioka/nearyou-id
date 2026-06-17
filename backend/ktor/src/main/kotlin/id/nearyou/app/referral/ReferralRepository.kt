package id.nearyou.app.referral

import java.util.UUID
import javax.sql.DataSource

/**
 * Owns the `referral_tickets` table (V23). Inviter resolution lives in
 * [id.nearyou.app.infra.repo.UserRepository], NOT here: it reads the `users`
 * table, which belongs to the user feature (docs/11 §3.1 — cross-feature access
 * via the other feature's repository, never its tables).
 *
 * Blocking JDBC; the caller ([ReferralService]) wraps invocations in
 * `withContext(dbDispatcher)` per the bounded-dispatcher discipline (docs/11 §3.2).
 */
class ReferralRepository(
    private val dataSource: DataSource,
) {
    /**
     * Insert one `pending_activity` ticket. `expires_at = created_at + 14 days`
     * (docs/01 § Bonus Release Criteria) — both derive from the same statement's
     * `NOW()`, so they are exactly 14 days apart. Throws `java.sql.SQLException`
     * with SQLState 23505 if a ticket already exists for [inviteeUserId] (the
     * UNIQUE constraint) — the caller swallows it (idempotent best-effort).
     */
    fun insertPendingTicket(
        inviterId: UUID,
        inviteeUserId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO referral_tickets (inviter_user_id, invitee_user_id, status, expires_at)
                VALUES (?, ?, 'pending_activity', NOW() + INTERVAL '14 days')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inviterId)
                ps.setObject(2, inviteeUserId)
                ps.executeUpdate()
            }
        }
    }
}
