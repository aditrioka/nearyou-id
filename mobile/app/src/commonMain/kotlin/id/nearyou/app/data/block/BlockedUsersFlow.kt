package id.nearyou.app.data.block

/**
 * The block-list orchestration contract consumed by `BlockedUsersScreen` / `BlockedUsersViewModel`.
 * The production binding is [BlockedUsersRepository]; commonTest substitutes a `FakeBlockedUsersFlow`
 * so screen tests can drive a specific outcome without a backend.
 */
interface BlockedUsersFlow {
    /** `GET /api/v1/blocks` (+ optional [cursor]) → the status-driven [BlockedUsersOutcome]. */
    suspend fun fetchBlocks(cursor: String? = null): BlockedUsersOutcome

    /** `DELETE /api/v1/blocks/{userId}` → the status-driven [UnblockOutcome]. */
    suspend fun unblock(userId: String): UnblockOutcome
}

/**
 * Maps the low-level [BlockedUsersApiClient] results onto exactly one [BlockedUsersOutcome] /
 * [UnblockOutcome], keyed on the HTTP status / transport type — no generic fallthrough (mirrors
 * `ConsentRepository` / `NearbyTimelineRepository`).
 *
 * The optional [diagnosticLog] is a non-user-facing sink. It MUST NOT carry a blocked user's
 * `userId`/`username`/`displayName` — only a status / transport-cause-type envelope (PII discipline,
 * `mobile-settings` § "Block-list management").
 */
class BlockedUsersRepository(
    private val apiClient: BlockedUsersApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : BlockedUsersFlow {
    override suspend fun fetchBlocks(cursor: String?): BlockedUsersOutcome =
        when (val result = apiClient.fetchBlocks(cursor)) {
            is BlocksApiResult.Success ->
                BlockedUsersOutcome.Loaded(
                    blocks =
                        result.body.blocks.map {
                            BlockedUser(
                                userId = it.userId,
                                username = it.username,
                                displayName = it.displayName,
                                isPremium = it.isPremium,
                            )
                        },
                    nextCursor = result.body.nextCursor,
                )
            is BlocksApiResult.NetworkError -> {
                // Type-only, never cause.message (a transport message can embed the request URL).
                diagnosticLog("blocks_network_error: ${result.cause::class.simpleName}")
                BlockedUsersOutcome.RetryableError
            }
            is BlocksApiResult.HttpError ->
                when (result.status) {
                    401 -> BlockedUsersOutcome.TokenInvalid
                    else -> {
                        diagnosticLog("blocks_http_error: status=${result.status}")
                        BlockedUsersOutcome.RetryableError
                    }
                }
        }

    override suspend fun unblock(userId: String): UnblockOutcome =
        when (val result = apiClient.unblock(userId)) {
            is UnblockApiResult.Success -> UnblockOutcome.Success
            is UnblockApiResult.NetworkError -> {
                diagnosticLog("unblock_network_error: ${result.cause::class.simpleName}")
                UnblockOutcome.RetryableError
            }
            is UnblockApiResult.HttpError ->
                when (result.status) {
                    401 -> UnblockOutcome.TokenInvalid
                    else -> {
                        diagnosticLog("unblock_http_error: status=${result.status}")
                        UnblockOutcome.RetryableError
                    }
                }
        }
}
