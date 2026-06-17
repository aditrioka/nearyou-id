package id.nearyou.app.referral

import id.nearyou.app.infra.repo.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Creates a referral ticket at signup when an invite code resolves to an eligible
 * inviter. Best-effort + silent-to-invitee + audit-logged (docs/01/05/08;
 * design.md D1/D5/D6). See [ReferralTicketCreator] for the contract.
 *
 * Gate order (design.md D5): resolve code → inviter eligibility → invitee
 * fingerprint non-collision → per-inviter burst limit (LAST, so only genuine
 * creations consume budget) → INSERT. Every rejection is logged with its reason;
 * nothing propagates to signup. The whole operation runs inside one
 * `withContext(dbDispatcher)` (DB lookup + Redis limiter + DB insert are all IO).
 */
class ReferralService(
    private val users: UserRepository,
    private val referrals: ReferralRepository,
    private val rateLimiter: ReferralTicketRateLimiter = ReferralTicketRateLimiter(),
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) : ReferralTicketCreator {
    private val log = LoggerFactory.getLogger(ReferralService::class.java)

    override suspend fun createTicketIfInvited(
        inviteCode: String?,
        inviteeUserId: UUID,
        inviteeDeviceFingerprintHash: String?,
    ) {
        try {
            // Absent / blank code → skip referral entirely (no lookup, no ticket).
            val code = inviteCode?.trim()?.takeIf { it.isNotEmpty() } ?: return
            withContext(dbDispatcher) {
                val inviter =
                    users.findInviterByInviteCodePrefix(code)
                        ?: return@withContext reject(inviteeUserId, "unresolved")
                if (inviter.id == inviteeUserId) return@withContext reject(inviteeUserId, "self_invite")
                if (inviter.isBanned) return@withContext reject(inviteeUserId, "inviter_banned")
                val ageCutoff = clock.instant().minus(INVITER_MIN_AGE_DAYS, ChronoUnit.DAYS)
                if (!inviter.createdAt.isBefore(ageCutoff)) return@withContext reject(inviteeUserId, "inviter_too_new")
                if (inviteeDeviceFingerprintHash != null &&
                    inviter.deviceFingerprintHash != null &&
                    inviter.deviceFingerprintHash == inviteeDeviceFingerprintHash
                ) {
                    return@withContext reject(inviteeUserId, "fingerprint_collision")
                }
                // Burst limit is the LAST gate so only genuine creations consume budget.
                when (rateLimiter.tryAcquire(inviter.id)) {
                    is ReferralTicketRateLimiter.Outcome.RateLimited ->
                        return@withContext reject(inviteeUserId, "burst_rate")
                    is ReferralTicketRateLimiter.Outcome.Allowed -> Unit
                }
                try {
                    referrals.insertPendingTicket(inviterId = inviter.id, inviteeUserId = inviteeUserId)
                    log.info("referral.ticket_created inviter={} invitee={}", inviter.id, inviteeUserId)
                } catch (e: SQLException) {
                    // UNIQUE(invitee_user_id) duplicate or any insert failure — swallow.
                    reject(inviteeUserId, "insert_failed sqlstate=${e.sqlState}")
                }
            }
        } catch (t: Throwable) {
            // Best-effort: a referral failure must never fail signup.
            log.warn("referral.attempt_error invitee={} err={}", inviteeUserId, t.toString())
        }
    }

    /** Server-side audit only — never surfaced to the invitee (anti-probing). */
    private fun reject(
        inviteeUserId: UUID,
        reason: String,
    ) {
        log.info("referral.ticket_rejected invitee={} reason={}", inviteeUserId, reason)
    }

    companion object {
        const val INVITER_MIN_AGE_DAYS: Long = 30
    }
}
