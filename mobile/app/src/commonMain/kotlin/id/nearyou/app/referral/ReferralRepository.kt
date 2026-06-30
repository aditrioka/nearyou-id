package id.nearyou.app.referral

/** The referral surface's domain state: the user's shareable code + confirmed-referral progress. */
data class ReferralState(
    val inviteCode: String,
    val grantedReferrals: Int,
    val milestone: Int,
    val inviterRewardClaimed: Boolean,
)

/**
 * Data-layer seam (docs/11 §2.6) for the referral surface. The `ReferralViewModel` talks to this,
 * never to the [ReferralApiClient]; a fake implementation backs the screen + ViewModel tests. Mirrors
 * `HideDistanceRepository`.
 */
interface ReferralRepository {
    /** The referral state, or `null` on a load failure (the ViewModel renders the error state). */
    suspend fun loadState(): ReferralState?
}

class DefaultReferralRepository(
    private val apiClient: ReferralApiClient,
) : ReferralRepository {
    override suspend fun loadState(): ReferralState? =
        when (val result = apiClient.fetchState()) {
            is ReferralStateResult.Success ->
                ReferralState(
                    inviteCode = result.inviteCode,
                    grantedReferrals = result.grantedReferrals,
                    milestone = result.milestone,
                    inviterRewardClaimed = result.inviterRewardClaimed,
                )
            ReferralStateResult.Failure -> null
        }
}
