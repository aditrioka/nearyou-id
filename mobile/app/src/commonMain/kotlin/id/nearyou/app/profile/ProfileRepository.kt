package id.nearyou.app.profile

/**
 * Orchestrates the profile read + the follow / block / report actions: delegates to [profileApiClient]
 * and maps each low-level `*ApiResult` to EXACTLY one member of its sealed outcome, keyed on the HTTP
 * **status** (+ the parsed `error.code` where the backend distinguishes by code — the constant 404
 * `user_not_found` and the 409 `duplicate_report`), with no generic "failed" wildcard. A stateless Koin
 * singleton: every method takes the target [userId] explicitly (so `single<ProfileFlow> { … }` is safe).
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` + `errorCode` primitives —
 * never a body, the `userId`, or a coordinate (these endpoints carry no coordinate at all); the
 * repository never `println`s/logs bodies.
 */
class ProfileRepository(
    private val profileApiClient: ProfileApiClient,
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

    override suspend fun block(userId: String): BlockOutcome =
        when (val result = profileApiClient.block(userId)) {
            ActionApiResult.NoContent -> BlockOutcome.Blocked
            is ActionApiResult.NetworkError -> BlockOutcome.NetworkError
            is ActionApiResult.HttpError ->
                when {
                    result.status == 429 -> BlockOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                    // An unreachable-from-UI 404 (kebab only on a read profile) + 5xx + any other →
                    // the retryable NetworkError (a non-actionable failure).
                    else -> {
                        diagnosticLog(result.status, result.errorCode)
                        BlockOutcome.NetworkError
                    }
                }
        }

    override suspend fun report(
        userId: String,
        reasonCategory: ReportReasonCategory,
        note: String?,
    ): ReportOutcome =
        when (val result = profileApiClient.report(userId, reasonCategory.toWire(), note)) {
            ActionApiResult.NoContent -> ReportOutcome.Submitted
            is ActionApiResult.NetworkError -> ReportOutcome.NetworkError
            is ActionApiResult.HttpError ->
                when {
                    result.status == 409 && result.errorCode == DUPLICATE_REPORT -> ReportOutcome.Duplicate
                    result.status == 429 -> ReportOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                    else -> {
                        diagnosticLog(result.status, result.errorCode)
                        ReportOutcome.NetworkError
                    }
                }
        }

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

    private companion object {
        // The SHIPPED reports duplicate error code (ReportRoutes.kt + reports-spec requirement); NOT the
        // stale `reports.duplicate` in that spec's purpose line.
        const val DUPLICATE_REPORT = "duplicate_report"
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
