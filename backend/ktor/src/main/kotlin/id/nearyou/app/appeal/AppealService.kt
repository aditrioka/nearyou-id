package id.nearyou.app.appeal

import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.infra.repo.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Appeal submission + own-status orchestration (`content-moderation-appeal`).
 *
 * Eligibility is purely `is_banned`, loaded from the `users` row — the appeal-realm
 * principal deliberately omits ban state (the ban-exempt realm authenticates without
 * the ban gate). A non-banned caller — a normal user OR a shadow-banned-only user —
 * gets the SAME [SubmitOutcome.NoActionableModeration]; the branch never observes
 * `is_shadow_banned`, so the shadow-ban state cannot leak (spec § "Appeal eligibility
 * is is_banned and never reveals shadow-ban").
 *
 * `action_type` is server-derived from the row (`suspended_until IS NULL →
 * 'permanent_ban'`, else `'suspension'`); any client-supplied value is ignored.
 */
class AppealService(
    private val users: UserRepository,
    private val appeals: JdbcAppealRepository,
    private val rateLimiter: AppealRateLimiter,
    private val nowProvider: () -> Instant = Instant::now,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface SubmitOutcome {
        data class Created(val appealId: UUID) : SubmitOutcome

        /** Caller is not `is_banned` — identical for normal + shadow-banned-only (no state leak). */
        data object NoActionableModeration : SubmitOutcome

        data object AlreadyPending : SubmitOutcome

        data class RateLimited(val retryAfterSeconds: Long) : SubmitOutcome
    }

    /**
     * Submits an appeal for [userId]. [appealText] is already length-validated +
     * normalized by the route's ContentLengthGuard (≤1000 cp, non-blank).
     */
    suspend fun submit(
        userId: UUID,
        appealText: String,
    ): SubmitOutcome {
        val user =
            withContext(dbDispatcher) { users.findById(userId) }
                ?: return SubmitOutcome.NoActionableModeration
        // Eligibility: only an actioned (banned/suspended) user may appeal. The SAME
        // outcome for a shadow-banned-only user and a normal user — no is_shadow_banned
        // branch, so the shadow-ban state never leaks.
        if (!user.isBanned) return SubmitOutcome.NoActionableModeration

        // App-level one-pending pre-check (fast path; consumes no rate-limit slot).
        val latest = appeals.findLatest(userId)
        if (latest?.status == STATUS_PENDING) return SubmitOutcome.AlreadyPending

        // Per-user daily submission cap — only eligible callers reach the limiter.
        val rl =
            withContext(dbDispatcher) {
                rateLimiter.tryAcquire(userId, computeTTLToNextReset(userId, nowProvider()))
            }
        if (rl is AppealRateLimiter.Outcome.RateLimited) {
            return SubmitOutcome.RateLimited(rl.retryAfterSeconds)
        }

        // Server-derived action_type: timed suspension vs permanent ban.
        val actionType = if (user.suspendedUntil == null) ACTION_PERMANENT_BAN else ACTION_SUSPENSION
        return when (val result = appeals.insertPending(userId, actionType, appealText)) {
            is JdbcAppealRepository.InsertResult.Inserted -> SubmitOutcome.Created(result.id)
            // A concurrent submission won the partial-unique race backstop.
            JdbcAppealRepository.InsertResult.PendingConflict -> SubmitOutcome.AlreadyPending
        }
    }

    /** The caller's most-recent appeal (the pull-only decision-outcome surface), or null. */
    suspend fun latestStatus(userId: UUID): AppealRow? = appeals.findLatest(userId)

    private companion object {
        const val STATUS_PENDING = "pending"
        const val ACTION_SUSPENSION = "suspension"
        const val ACTION_PERMANENT_BAN = "permanent_ban"
    }
}
