package id.nearyou.app.referral

import id.nearyou.app.infra.revenuecatapi.GrantRequest
import id.nearyou.app.infra.revenuecatapi.GrantResult
import id.nearyou.app.infra.revenuecatapi.ReferralEntitlementGranter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Summary of one [ReferralActivityCheckWorker.execute] run (the route's response body). */
data class ReferralActivityCheckResult(
    val expired: Int,
    val voided: Int,
    val granted: Int,
    val stillPending: Int,
    val reconciled: Int,
    val durationMs: Long,
)

/**
 * Daily `/internal/referral-activity-check` worker (`referral-grant-worker`).
 * Three passes over `referral_tickets`:
 *  1. expire stale `pending_activity` tickets (`expires_at < NOW()`) — set-based;
 *  2. evaluate each remaining ticket's activity gate — the inviter must be neither hard- nor
 *     shadow-banned; the device-fingerprint anti-collision legs VOID (the invitee's signup
 *     fingerprint in the inviter's 90-day login history, or the invitee's identity seen on an
 *     inviter device — the /24 subnet is recorded but never voids, D8a); the engagement legs
 *     (invitee ≥ 2 posts AND ≥ 3 login-days AND ≥ 5 app sessions, all from `login_events`) keep
 *     a clean ticket pending until met — grant on full pass;
 *  3. reconcile — re-dispatch any `granted_entitlements` whose prior-run RevenueCat
 *     call failed (`revenuecat_dispatched_at IS NULL`), so a transient RC failure
 *     never silently loses an earned grant.
 *
 * A passing ticket flips to `granted`, the invitee gets a 1-week promotional
 * entitlement (extend-if-active / fresh-if-not), and at the inviter's 5th granted
 * referral the inviter gets the same — exactly once per lifetime, enforced by the
 * `inviter_reward_claimed_at` sentinel + the `granted_entitlements` partial-unique
 * index. The DB writes per ticket are one transaction; the RevenueCat dispatch runs
 * AFTER the commit (idempotent via the ledger + `dedup_key`), stamping
 * `revenuecat_dispatched_at` on success; so it never holds a tx across the network,
 * a rolled-back grant is never dispatched, and a failed dispatch is retried by pass 3.
 * The worker never writes `users.subscription_status` — that is applied by the
 * subscription-billing-webhook `GRANT` echo when RevenueCat reports the grant.
 *
 * Thread-safe: holds no state across calls.
 */
class ReferralActivityCheckWorker(
    private val dataSource: DataSource,
    private val repository: ReferralGrantRepository,
    private val granter: ReferralEntitlementGranter,
    private val clock: () -> Instant = Instant::now,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(): ReferralActivityCheckResult =
        withContext(dbDispatcher) {
            val startNanos = System.nanoTime()
            val staleExpired = tx { repository.expireStaleTickets(it) }
            val pending = tx { repository.fetchPendingTickets(it) }

            var granted = 0
            var voided = 0
            var stillPending = 0
            for (ticket in pending) {
                when (processTicket(ticket)) {
                    TicketOutcome.GRANTED -> granted++
                    TicketOutcome.VOIDED -> voided++
                    TicketOutcome.STILL_PENDING -> stillPending++
                }
            }

            // Pass 3 — reconcile: re-dispatch grants whose prior run's RC call failed.
            val reconciled = reconcileUndispatchedGrants()

            val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
            log.info(
                "event=referral_activity_check expired={} voided={} granted={} pending={} reconciled={} duration_ms={}",
                staleExpired,
                voided,
                granted,
                stillPending,
                reconciled,
                durationMs,
            )
            ReferralActivityCheckResult(
                expired = staleExpired,
                voided = voided,
                granted = granted,
                stillPending = stillPending,
                reconciled = reconciled,
                durationMs = durationMs,
            )
        }

    private suspend fun processTicket(ticket: PendingTicket): TicketOutcome {
        // Phase 1 — all DB reads + writes for this ticket in one transaction.
        val plan =
            tx { conn ->
                val standing = repository.inviterStanding(conn, ticket.inviterId)
                if (standing == null || standing.isVoiding) {
                    repository.voidTicket(conn, ticket.id)
                    return@tx GrantPlan.Voided
                }
                // Anti-collision (device-fingerprint based) — a positive match VOIDS the ticket
                // (terminal, taking precedence over an engagement shortfall). The /24 subnet is
                // recorded but is NEVER a voiding signal (carrier-grade-NAT safety, design D8a).
                val since90d = clock().minus(Duration.ofDays(ANTI_COLLISION_WINDOW_DAYS))
                val antiCollisionHit =
                    repository.inviteeFingerprintInInviterHistory(conn, ticket.inviterId, ticket.inviteeId, since90d) ||
                        repository.inviteeIdentifierSeenOnInviterDevice(conn, ticket.inviterId, ticket.inviteeId, since90d)
                if (antiCollisionHit) {
                    repository.voidTicket(conn, ticket.id)
                    return@tx GrantPlan.Voided
                }
                // Engagement legs (posts + login-days + sessions) — below ANY threshold stays
                // pending and is re-evaluated next run until it passes or the 14-day TTL expires.
                val since = ticket.createdAt
                val belowEngagement =
                    repository.countInviteePosts(conn, ticket.inviteeId, since) < MIN_POSTS ||
                        repository.countInviteeLoginDays(conn, ticket.inviteeId, since) < MIN_LOGIN_DAYS ||
                        repository.countInviteeAppSessions(conn, ticket.inviteeId, since) < MIN_SESSIONS
                if (belowEngagement) {
                    return@tx GrantPlan.StillPending
                }
                // Gate passed → flip to granted, then build the grant(s).
                repository.markTicketGranted(conn, ticket.id)
                val now = clock()
                val inviteeGrant = buildGrant(conn, ticket, ticket.inviteeId, ROLE_INVITEE, now)
                val inviterGrant = buildInviterGrantIfMilestone(conn, ticket, now)
                GrantPlan.Granted(inviteeGrant, inviterGrant)
            }

        // Phase 2 — RevenueCat dispatch AFTER commit (never inside the tx).
        return when (plan) {
            GrantPlan.Voided -> TicketOutcome.VOIDED
            GrantPlan.StillPending -> TicketOutcome.STILL_PENDING
            is GrantPlan.Granted -> {
                dispatch(plan.invitee)
                plan.inviter?.let { dispatch(it) }
                TicketOutcome.GRANTED
            }
        }
    }

    /** Insert (idempotently) the recipient's grant row and return the dispatch payload. */
    private fun buildGrant(
        conn: Connection,
        ticket: PendingTicket,
        recipientId: UUID,
        role: String,
        now: Instant,
    ): DispatchPayload {
        val end = stackedEnd(repository.currentEntitlementEnd(conn, recipientId), now)
        val dedupKey = "${ticket.id}:$role"
        repository.insertGrantIfNew(conn, ticket.id, recipientId, role, now, end, dedupKey)
        return DispatchPayload(recipientId, end, dedupKey)
    }

    /**
     * If THIS granted ticket brings the inviter to ≥ 5 granted referrals AND the
     * lifetime sentinel is still unclaimed, claim it and build the inviter grant.
     * `>= 5` (not `== 5`) is race-robust: if two overlapping runs push the count
     * past 5, the first to claim the sentinel still fires exactly one grant (the
     * partial-unique index is the structural backstop). Counts 1-4 and any later
     * ticket (sentinel already set) build nothing.
     */
    private fun buildInviterGrantIfMilestone(
        conn: Connection,
        ticket: PendingTicket,
        now: Instant,
    ): DispatchPayload? {
        if (repository.grantedCountForInviter(conn, ticket.inviterId) < INVITER_MILESTONE) return null
        if (!repository.claimInviterReward(conn, ticket.inviterId)) return null
        return buildGrant(conn, ticket, ticket.inviterId, ROLE_INVITER, now)
    }

    /**
     * Dispatch is best-effort and never fatal: the grant is already committed to the
     * ledger, so a dispatch failure (a [GrantResult.Failed] or even an unexpected
     * throw from a misbehaving client) is logged and the batch continues — pass 3
     * (reconcile) retries it. On success, stamps `revenuecat_dispatched_at` so reconcile
     * skips it. Returns true iff RevenueCat accepted the grant. Cancellation propagates.
     */
    private suspend fun dispatch(payload: DispatchPayload): Boolean {
        val result =
            try {
                granter.grant(
                    GrantRequest(
                        appUserId = payload.recipientId.toString(),
                        entitlementId = ENTITLEMENT_ID,
                        endTimeMs = payload.end.toEpochMilli(),
                        dedupKey = payload.dedupKey,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("event=referral_grant_dispatch_error dedup_key={} error={}", payload.dedupKey, e.message, e)
                return false
            }
        return when (result) {
            GrantResult.Dispatched -> {
                tx { repository.markGrantDispatched(it, payload.dedupKey) }
                true
            }
            GrantResult.NotConfigured -> {
                log.info("event=referral_grant_not_dispatched reason=unconfigured dedup_key={}", payload.dedupKey)
                false
            }
            is GrantResult.Failed -> {
                log.warn("event=referral_grant_dispatch_failed dedup_key={} reason={}", payload.dedupKey, result.reason)
                false
            }
        }
    }

    /**
     * Pass 3 — re-dispatch grants whose RevenueCat call never succeeded
     * (`revenuecat_dispatched_at IS NULL`), bounded by [RECONCILE_LIMIT]. Returns the
     * count newly dispatched. Re-dispatch is idempotent (RevenueCat + `dedup_key`); a
     * still-failing grant stays NULL and is retried next run.
     */
    private suspend fun reconcileUndispatchedGrants(): Int {
        val undispatched = tx { repository.fetchUndispatchedGrants(it, RECONCILE_LIMIT) }
        var reconciled = 0
        for (grant in undispatched) {
            if (dispatch(DispatchPayload(grant.userId, grant.entitlementEnd, grant.dedupKey))) reconciled++
        }
        return reconciled
    }

    /** `GREATEST(current_end, NOW()) + 7 days` — extend-if-active, fresh-if-not. */
    private fun stackedEnd(
        currentEnd: Instant?,
        now: Instant,
    ): Instant = maxOf(currentEnd ?: now, now).plus(Duration.ofDays(GRANT_DAYS))

    private suspend fun <T> tx(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                conn.autoCommit = true
            }
        }

    private data class DispatchPayload(
        val recipientId: UUID,
        val end: Instant,
        val dedupKey: String,
    )

    private sealed interface GrantPlan {
        data object Voided : GrantPlan

        data object StillPending : GrantPlan

        data class Granted(val invitee: DispatchPayload, val inviter: DispatchPayload?) : GrantPlan
    }

    private enum class TicketOutcome { GRANTED, VOIDED, STILL_PENDING }

    private companion object {
        const val MIN_POSTS = 2
        const val MIN_LOGIN_DAYS = 3
        const val MIN_SESSIONS = 5
        const val ANTI_COLLISION_WINDOW_DAYS = 90L
        const val INVITER_MILESTONE = 5
        const val GRANT_DAYS = 7L
        const val ENTITLEMENT_ID = "premium"
        const val ROLE_INVITEE = "invitee"
        const val ROLE_INVITER = "inviter"
        const val RECONCILE_LIMIT = 500
        val log = LoggerFactory.getLogger(ReferralActivityCheckWorker::class.java)
    }
}
