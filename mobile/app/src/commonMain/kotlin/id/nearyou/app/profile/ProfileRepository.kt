package id.nearyou.app.profile

import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.data.report.ReportTargetType

/**
 * Orchestrates the profile read + the follow / block / report actions: delegates to [profileApiClient]
 * (and, for report, the shared [reportSubmitter]) and maps each low-level `*ApiResult` to EXACTLY one
 * member of its sealed outcome, keyed on the HTTP **status** (+ the parsed `error.code` where the backend
 * distinguishes by code — the constant 404 `user_not_found`), with no generic "failed" wildcard. A
 * stateless Koin singleton: every method takes the target [userId] explicitly (so
 * `single<ProfileFlow> { … }` is safe).
 *
 * Report submission delegates to the shared [reportSubmitter] (`data/report/` — mobile-content-report),
 * so the user/post/reply report-submission mapping lives in exactly ONE place; the profile surface fixes
 * the target to [ReportTargetType.USER]. Block-create likewise delegates to the shared [blockSubmitter]
 * (`data/block/` — mobile-block-from-content D2): the block mapping previously inlined here now lives in
 * exactly ONE place, consumed by profile AND post-detail.
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` + `errorCode` primitives —
 * never a body, the `userId`, or a coordinate (these endpoints carry no coordinate at all); the
 * repository never `println`s/logs bodies.
 */
class ProfileRepository(
    private val profileApiClient: ProfileApiClient,
    private val reportSubmitter: ReportSubmitter,
    private val blockSubmitter: BlockSubmitter,
    // Diagnostic sink for non-user-facing error detail (status + error code only). MUST NOT carry
    // tokens, bodies, the userId, or coordinates.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) : ProfileFlow {
    override suspend fun loadProfile(userId: String): ProfileOutcome =
        when (val result = profileApiClient.getProfile(userId)) {
            is ProfileReadApiResult.Success -> ProfileOutcome.Loaded(result.body.toDomain())
            is ProfileReadApiResult.NetworkError -> ProfileOutcome.NetworkError
            is ProfileReadApiResult.HttpError -> mapReadHttpError(result)
        }

    override suspend fun follow(userId: String): FollowToggleOutcome =
        when (val result = profileApiClient.follow(userId)) {
            ActionApiResult.NoContent -> FollowToggleOutcome.Followed
            is ActionApiResult.NetworkError -> FollowToggleOutcome.NetworkError
            is ActionApiResult.HttpError -> mapFollowHttpError(result)
        }

    override suspend fun unfollow(userId: String): FollowToggleOutcome =
        when (val result = profileApiClient.unfollow(userId)) {
            // DELETE is an idempotent 204 no-op — it never 404s, so no TargetGone branch is reachable here.
            ActionApiResult.NoContent -> FollowToggleOutcome.Unfollowed
            is ActionApiResult.NetworkError -> FollowToggleOutcome.NetworkError
            is ActionApiResult.HttpError -> mapUnfollowHttpError(result)
        }

    override suspend fun block(userId: String): BlockOutcome = blockSubmitter.submit(userId)

    override suspend fun report(
        userId: String,
        reasonCategory: ReportReasonCategory,
        note: String?,
    ): ReportOutcome = reportSubmitter.submit(ReportTargetType.USER, userId, reasonCategory, note)

    /** Maps a non-200 profile read. The constant `404 user_not_found` (every cause) → the single
     *  [ProfileOutcome.NotFound]; `5xx` + any other unenumerated status → the retryable
     *  [ProfileOutcome.NetworkError]. 401 never reaches here (the `Auth` plugin consumes it). */
    private fun mapReadHttpError(error: ProfileReadApiResult.HttpError): ProfileOutcome =
        when (error.status) {
            404 -> ProfileOutcome.NotFound
            else -> {
                diagnosticLog(error.status, error.errorCode)
                ProfileOutcome.NetworkError
            }
        }

    /** Maps a non-204 follow `POST`. The constant `404 user_not_found` → [FollowToggleOutcome.TargetGone];
     *  `429` → [FollowToggleOutcome.RateLimited]; `5xx` + any other → [FollowToggleOutcome.NetworkError]. */
    private fun mapFollowHttpError(error: ActionApiResult.HttpError): FollowToggleOutcome =
        when {
            error.status == 404 -> FollowToggleOutcome.TargetGone
            error.status == 429 -> FollowToggleOutcome.RateLimited(error.retryAfterSeconds ?: 0L)
            else -> {
                diagnosticLog(error.status, error.errorCode)
                FollowToggleOutcome.NetworkError
            }
        }

    /** Maps a non-204 unfollow `DELETE`. The DELETE never 404s, so a 429 → [RateLimited] and everything
     *  else → [NetworkError] (no TargetGone for the no-op delete). */
    private fun mapUnfollowHttpError(error: ActionApiResult.HttpError): FollowToggleOutcome =
        when {
            error.status == 429 -> FollowToggleOutcome.RateLimited(error.retryAfterSeconds ?: 0L)
            else -> {
                diagnosticLog(error.status, error.errorCode)
                FollowToggleOutcome.NetworkError
            }
        }
}

private fun UserProfileResponse.toDomain(): UserProfile =
    UserProfile(
        userId = userId,
        username = username,
        displayName = displayName,
        bio = bio,
        followerCount = followerCount,
        followingCount = followingCount,
        isSelf = isSelf,
        followedByViewer = followedByViewer,
        isPremium = isPremium,
        isPrivate = isPrivate,
    )
