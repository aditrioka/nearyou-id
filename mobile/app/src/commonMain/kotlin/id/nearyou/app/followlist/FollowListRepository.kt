package id.nearyou.app.followlist

/**
 * Orchestrates a follower/following list read: delegates to [apiClient] and maps each low-level
 * [FollowListApiResult] to EXACTLY one [FollowListOutcome] member, keyed on the HTTP **status**, with no
 * generic "failed" wildcard. A stateless Koin singleton: every call takes the target [userId] + [tab] +
 * [cursor] explicitly (so `single<FollowListFlow> { … }` is safe). Mirrors `ProfileRepository`.
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` primitive — never a body, the
 * `userId`, or a coordinate (these endpoints carry no coordinate at all); the repository never `println`s/
 * logs bodies.
 */
class FollowListRepository(
    private val apiClient: FollowListApiClient,
    // Diagnostic sink for non-user-facing error detail (status only). MUST NOT carry tokens, bodies, or
    // the userId.
    private val diagnosticLog: (status: Int) -> Unit = {},
) : FollowListFlow {
    override suspend fun load(
        userId: String,
        tab: FollowListTab,
        cursor: String?,
    ): FollowListOutcome =
        when (val result = apiClient.fetch(userId, tab, cursor)) {
            is FollowListApiResult.Success ->
                FollowListOutcome.Loaded(
                    FollowListPage(
                        users = result.body.users.map { it.toDomain() },
                        nextCursor = result.body.nextCursor,
                    ),
                )
            is FollowListApiResult.NetworkError -> FollowListOutcome.NetworkError
            is FollowListApiResult.HttpError -> mapHttpError(result)
        }

    /** The constant `404 user_not_found` (every cause) → the single [FollowListOutcome.NotFound]; the
     *  unreachable-from-UI `400 invalid_cursor` + `5xx` + any other unenumerated status → the retryable
     *  [FollowListOutcome.NetworkError]. `401` never reaches here (the `Auth` plugin consumes it). */
    private fun mapHttpError(error: FollowListApiResult.HttpError): FollowListOutcome =
        when (error.status) {
            404 -> FollowListOutcome.NotFound
            else -> {
                diagnosticLog(error.status)
                FollowListOutcome.NetworkError
            }
        }
}

private fun FollowListItemDto.toDomain(): FollowListUser =
    FollowListUser(
        userId = userId,
        username = username,
        displayName = displayName,
        isPremium = isPremium,
    )
