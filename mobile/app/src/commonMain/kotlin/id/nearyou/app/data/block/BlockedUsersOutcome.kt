package id.nearyou.app.data.block

/**
 * A blocked user as rendered by `BlockedUsersScreen` — the display identity ([displayName] + the
 * @-handle of [username]) plus [userId], which is the unblock `DELETE` path parameter ONLY. [userId]
 * is NOT a rendered field: the screen shows [displayName] / [username] and passes [userId] straight
 * back into [BlockedUsersFlow.unblock]; no UI node ever displays the UUID (PII discipline,
 * `mobile-settings` § "Block-list management").
 */
data class BlockedUser(
    val userId: String,
    val username: String,
    val displayName: String,
    val isPremium: Boolean,
)

/**
 * Terminal outcome of [BlockedUsersFlow.fetchBlocks]. Status-driven (mirrors `ConsentOutcome` /
 * `NearbyApiResult` mapping) — keyed on the HTTP status / transport-failure type, NEVER on a parsed
 * `error.code`. Exhaustive with no generic fallthrough: `200`→[Loaded], `401`→[TokenInvalid],
 * `5xx`/IO/`400`→[RetryableError]. No variant carries PII — the UI maps each to a static
 * `Res.string.*` key.
 */
sealed interface BlockedUsersOutcome {
    /** `200 OK` — the page of blocks plus the cursor for the next page (null = last page). */
    data class Loaded(
        val blocks: List<BlockedUser>,
        val nextCursor: String?,
    ) : BlockedUsersOutcome

    /** `401` — the access token failed verification. Terminal: route to sign-in. */
    data object TokenInvalid : BlockedUsersOutcome

    /** `5xx` / IO / `400` — a retryable error (reuses `signin_error_network` + `cta_retry`). */
    data object RetryableError : BlockedUsersOutcome
}

/**
 * Terminal outcome of [BlockedUsersFlow.unblock]. On [RetryableError] the screen keeps the row in
 * place and surfaces a non-trapping error — it does NOT optimistically drop a row whose unblock did
 * not succeed (`mobile-settings` § "Block-list management").
 */
sealed interface UnblockOutcome {
    /** Any 2xx (`204`) — the row is removed from the list. */
    data object Success : UnblockOutcome

    /** `401` — token failed verification. Terminal: route to sign-in. */
    data object TokenInvalid : UnblockOutcome

    /** `5xx` / IO / `400` / `404` — the row stays; surface a non-trapping error. */
    data object RetryableError : UnblockOutcome
}
