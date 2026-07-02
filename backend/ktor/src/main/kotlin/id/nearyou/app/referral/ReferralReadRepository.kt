package id.nearyou.app.referral

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/**
 * The caller's own referral state for `GET /api/v1/user/referral`: their shareable invite code plus
 * confirmed-referral progress. Server-observed state ONLY — nothing here is computed or granted (the
 * referral activity-check worker remains the sole authority for promoting tickets + claiming rewards).
 */
data class ReferralReadState(
    val inviteCode: String,
    val grantedReferrals: Int,
    val inviterRewardClaimed: Boolean,
)

/**
 * Read seam for the `referral-read` capability. An interface so [referralReadRoutes] can be driven by
 * a throwing stub in the fail-soft routes test (the `HideDistanceRoutesTest` injection pattern), while
 * [JdbcReferralReadRepository] is the production binding wired in `Application.kt`.
 */
interface ReferralReadRepository {
    suspend fun getReferralState(userId: UUID): ReferralReadState
}

/**
 * JDBC `ReferralReadRepository`. A single self-scoped read (`id = :caller`) returning the caller's
 * `users.invite_code_prefix` (returned verbatim — the value redemption resolves via
 * `findInviterByInviteCodePrefix`), their `granted` referral-ticket count, and whether the inviter
 * lifetime reward is already claimed (`users.inviter_reward_claimed_at IS NOT NULL`). A pure read —
 * it writes nothing and grants nothing.
 *
 * Own-content self read (the `id` is always the verified JWT `sub` of the caller — the route never
 * accepts a `user_id` param), scoped to a single row. It touches no `visible_*` view and is not a
 * feed/visibility read, so the SQL-holding property carries `@AllowMissingBlockJoin` (the
 * `HideDistanceRepository.SQL_SELECT` precedent). The `referral_tickets` granted-count sub-select is
 * NOT separately annotated — that table is deliberately outside the block-exclusion protected set.
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2). The JWT-`sub` caller's row always exists
 * (`configureUserJwt` validates `sub ∈ public.users`), so a missing row is unreachable post-auth; the
 * zero-row fallback fails safe rather than throwing.
 */
class JdbcReferralReadRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReferralReadRepository {
    override suspend fun getReferralState(userId: UUID): ReferralReadState =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_SELECT).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            ReferralReadState(
                                inviteCode = rs.getString("invite_code_prefix"),
                                grantedReferrals = rs.getInt("granted_count"),
                                inviterRewardClaimed = rs.getBoolean("inviter_reward_claimed"),
                            )
                        } else {
                            ReferralReadState(inviteCode = "", grantedReferrals = 0, inviterRewardClaimed = false)
                        }
                    }
                }
            }
        }

    private companion object {
        @AllowMissingBlockJoin(
            "own-row self read scoped to id = caller; a user cannot block themselves; not a feed/visibility read",
        )
        const val SQL_SELECT =
            "SELECT u.invite_code_prefix, " +
                "(u.inviter_reward_claimed_at IS NOT NULL) AS inviter_reward_claimed, " +
                "(SELECT COUNT(*) FROM referral_tickets WHERE inviter_user_id = u.id AND status = 'granted') AS granted_count " +
                "FROM users u WHERE u.id = ?"
    }
}
