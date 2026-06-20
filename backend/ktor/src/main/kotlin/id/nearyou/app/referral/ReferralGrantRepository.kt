package id.nearyou.app.referral

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A `pending_activity` ticket the activity-gate worker must evaluate. */
data class PendingTicket(
    val id: UUID,
    val inviterId: UUID,
    val inviteeId: UUID,
    val createdAt: Instant,
)

/** The inviter ban-status legs re-checked at grant time (docs/01 §233: shadow OR hard). */
data class InviterStanding(
    val isBanned: Boolean,
    val isShadowBanned: Boolean,
) {
    /** A ticket whose inviter is banned by either flag is voided, never granted. */
    val isVoiding: Boolean get() = isBanned || isShadowBanned
}

/**
 * Blocking-JDBC queries for the referral activity-gate worker
 * (`referral-grant-worker`). Every method takes the caller's [Connection] so the
 * service ([ReferralActivityCheckWorker]) owns the transaction boundary (the
 * `SubscriptionEventRepository` precedent); the service wraps invocations in
 * `withContext(dbDispatcher)` (docs/11 §3.2 bounded-dispatcher discipline).
 *
 * Owns `referral_tickets` (V23) + `granted_entitlements` (V29). Read-only
 * cross-feature signals — the invitee's own post count (`posts`), the inviter
 * ban flags (`users`), and a recipient's current entitlement end
 * (`subscription_events`) — are inlined here with the sanctioned lint
 * annotations; `subscription_events` is not a block/shadow-ban-protected table.
 */
class ReferralGrantRepository {
    /** Pass 1: set-based expiry of stale pending tickets. Returns the count expired. */
    fun expireStaleTickets(conn: Connection): Int =
        conn.prepareStatement(
            """
            UPDATE referral_tickets
               SET status = 'expired'
             WHERE status = 'pending_activity'
               AND expires_at < NOW()
            """.trimIndent(),
        ).use { ps -> ps.executeUpdate() }

    /** Pass 2: snapshot the remaining (non-expired) pending tickets to evaluate. */
    fun fetchPendingTickets(conn: Connection): List<PendingTicket> =
        conn.prepareStatement(
            """
            SELECT id, inviter_user_id, invitee_user_id, created_at
              FROM referral_tickets
             WHERE status = 'pending_activity'
             ORDER BY created_at
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PendingTicket(
                                id = rs.getObject("id", UUID::class.java),
                                inviterId = rs.getObject("inviter_user_id", UUID::class.java),
                                inviteeId = rs.getObject("invitee_user_id", UUID::class.java),
                                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * The invitee's authored-post count within `[since, NOW()]` — the durable
     * engagement leg (docs/01 §212, ≥ 2). A self-count of the invitee's OWN posts,
     * not a visibility-sensitive read: shadow-ban / block exclusion is irrelevant
     * (you measure the invitee's engagement regardless of who can see it), so the
     * two `posts` lint rules are satisfied by annotation, not a join.
     */
    @AllowRawPostsRead("referral activity gate counts the invitee's OWN authored posts")
    @AllowMissingBlockJoin("referral self-count of the invitee's own posts — engagement signal, not a visibility read")
    fun countInviteePosts(
        conn: Connection,
        inviteeId: UUID,
        since: Instant,
    ): Int =
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM posts
             WHERE author_id = ?
               AND created_at >= ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, inviteeId)
            ps.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /** Inviter ban-status read (docs/01 §233). Self-keyed lookup, not a feed read. */
    @AllowMissingBlockJoin("inviter ban-status re-check by id — not a content/feed read")
    fun inviterStanding(
        conn: Connection,
        inviterId: UUID,
    ): InviterStanding? =
        conn.prepareStatement(
            "SELECT is_banned, is_shadow_banned FROM users WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, inviterId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    InviterStanding(
                        isBanned = rs.getBoolean("is_banned"),
                        isShadowBanned = rs.getBoolean("is_shadow_banned"),
                    )
                } else {
                    null
                }
            }
        }

    /**
     * The recipient's current (furthest-future) entitlement end, for stacking.
     * `MAX(entitlement_end)` over the user's `subscription_events`; NULL when they
     * have never had a window — the caller floors with `NOW()` (fresh week).
     */
    fun currentEntitlementEnd(
        conn: Connection,
        userId: UUID,
    ): Instant? =
        conn.prepareStatement(
            "SELECT MAX(entitlement_end) FROM subscription_events WHERE user_id = ?",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getObject(1, OffsetDateTime::class.java)?.toInstant() else null
            }
        }

    /** Count of the inviter's `granted` tickets (the 5th-referral milestone signal). */
    fun grantedCountForInviter(
        conn: Connection,
        inviterId: UUID,
    ): Int =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM referral_tickets WHERE inviter_user_id = ? AND status = 'granted'",
        ).use { ps ->
            ps.setObject(1, inviterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /** Flip a passing ticket to `granted`. */
    fun markTicketGranted(
        conn: Connection,
        ticketId: UUID,
    ) {
        conn.prepareStatement(
            "UPDATE referral_tickets SET status = 'granted' WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, ticketId)
            ps.executeUpdate()
        }
    }

    /** Void a ticket whose inviter is banned (docs/01 §233) — no grant. */
    fun voidTicket(
        conn: Connection,
        ticketId: UUID,
    ) {
        conn.prepareStatement(
            "UPDATE referral_tickets SET status = 'expired' WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, ticketId)
            ps.executeUpdate()
        }
    }

    /**
     * Insert one grant row, idempotent via `UNIQUE (referral_ticket_id, user_id)`
     * (invitee per-ticket cap) and `granted_entitlements_inviter_once_idx`
     * (inviter lifetime cap). Returns true if newly inserted, false on conflict.
     */
    fun insertGrantIfNew(
        conn: Connection,
        ticketId: UUID,
        userId: UUID,
        grantRole: String,
        start: Instant,
        end: Instant,
        dedupKey: String,
    ): Boolean =
        conn.prepareStatement(
            """
            INSERT INTO granted_entitlements
                (referral_ticket_id, user_id, grant_role, entitlement_start, entitlement_end, dedup_key)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, ticketId)
            ps.setObject(2, userId)
            ps.setString(3, grantRole)
            ps.setObject(4, OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
            ps.setObject(5, OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
            ps.setString(6, dedupKey)
            ps.executeQuery().use { rs -> rs.next() }
        }

    /**
     * Claim the inviter lifetime reward by setting the `inviter_reward_claimed_at`
     * sentinel iff still NULL. Returns true if THIS call claimed it — the fast-path
     * guard before the inviter grant (the partial-unique index is the structural
     * backstop). A keyed UPDATE, not a content read.
     */
    fun claimInviterReward(
        conn: Connection,
        inviterId: UUID,
    ): Boolean =
        conn.prepareStatement(
            "UPDATE users SET inviter_reward_claimed_at = NOW() WHERE id = ? AND inviter_reward_claimed_at IS NULL",
        ).use { ps ->
            ps.setObject(1, inviterId)
            ps.executeUpdate() > 0
        }
}
