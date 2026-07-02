package id.nearyou.app.referral

import kotlinx.serialization.Serializable

/**
 * Response body for `GET /api/v1/user/referral` (the `referral-read` capability) — the caller's
 * shareable invite code + confirmed-referral progress. Bare camelCase keys (`inviteCode`,
 * `grantedReferrals`, `milestone`, `inviterRewardClaimed`), consistent with the hide-distance /
 * timeline wire convention.
 *
 * [inviteCode] is the caller's `users.invite_code_prefix` returned verbatim. [grantedReferrals] is the
 * count of their `granted` referral tickets. [milestone] is the inviter lifetime-reward threshold —
 * sourced from [ReferralActivityCheckWorker.INVITER_MILESTONE] (= 5) at the call site, never a second
 * literal. [inviterRewardClaimed] is whether that reward has already fired
 * (`users.inviter_reward_claimed_at IS NOT NULL`). The endpoint reports server state only.
 */
@Serializable
data class ReferralStateResponse(
    val inviteCode: String,
    val grantedReferrals: Int,
    val milestone: Int,
    val inviterRewardClaimed: Boolean,
)
