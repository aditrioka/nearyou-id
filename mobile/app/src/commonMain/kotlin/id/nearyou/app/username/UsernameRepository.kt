package id.nearyou.app.username

/**
 * Orchestrates the two username endpoints: map each HTTP **status** to exactly one outcome (design D4),
 * reading the flat body `error` code ONLY to split the two same-status pairs (`429`
 * cooldown_active/rate_limited; `422` invalid_username/username_rejected). No generic "load failed"
 * fallthrough; terminal `401` → SessionExpired is delegated downstream to the shipped `Auth` plugin (this
 * repository MUST NOT reimplement token refresh / re-route). Mirrors `SearchRepository`.
 */
class UsernameRepository(
    private val apiClient: UsernameApiClient,
    // Diagnostic sink for non-user-facing error detail (no-op default for tests). MUST NOT carry tokens
    // or the raw candidate.
    private val diagnosticLog: (String) -> Unit = {},
) : UsernameFlow {
    override suspend fun change(newUsername: String): UsernameChangeOutcome =
        when (val result = apiClient.change(newUsername)) {
            is UsernameChangeApiResult.Success -> UsernameChangeOutcome.Success(result.body.username)
            is UsernameChangeApiResult.NetworkError -> {
                diagnosticLog("username_change_network_error: ${result.cause::class.simpleName}")
                UsernameChangeOutcome.NetworkError
            }
            is UsernameChangeApiResult.HttpError ->
                when (result.status) {
                    401 -> UsernameChangeOutcome.SessionExpired
                    403 -> UsernameChangeOutcome.PremiumGate
                    409 -> UsernameChangeOutcome.Unavailable
                    422 ->
                        // Split on the flat error code: invalid_username (format) vs username_rejected
                        // (moderation). An unknown 422 code defaults to InvalidFormat (the safe "fix your
                        // input" branch).
                        if (result.errorCode == "username_rejected") {
                            UsernameChangeOutcome.Moderated
                        } else {
                            UsernameChangeOutcome.InvalidFormat
                        }
                    429 ->
                        // Split on the flat error code: cooldown_active (30-day) vs rate_limited
                        // (failed-attempt throttle). An unknown 429 code defaults to RateLimited (the
                        // transient "try later" branch, never the 30-day cooldown copy).
                        if (result.errorCode == "cooldown_active") {
                            UsernameChangeOutcome.CooldownActive(result.retryAfterSeconds ?: 0L)
                        } else {
                            UsernameChangeOutcome.RateLimited(result.retryAfterSeconds ?: 0L)
                        }
                    503 -> UsernameChangeOutcome.Disabled
                    400 -> {
                        diagnosticLog("username_change_invalid_request: status=400")
                        UsernameChangeOutcome.Error
                    }
                    in 500..599 -> UsernameChangeOutcome.NetworkError
                    // Any other unenumerated non-2xx → the DEFINED retryable NetworkError fallback (the
                    // "no generic fallthrough" rule bans a generic copy, not a `when` else).
                    else -> UsernameChangeOutcome.NetworkError
                }
        }

    override suspend fun check(candidate: String): UsernameCheckOutcome =
        when (val result = apiClient.check(candidate)) {
            is UsernameCheckApiResult.Success -> UsernameCheckOutcome.Available(result.body.available)
            is UsernameCheckApiResult.NetworkError -> {
                diagnosticLog("username_check_network_error: ${result.cause::class.simpleName}")
                UsernameCheckOutcome.CheckNetworkError
            }
            is UsernameCheckApiResult.HttpError ->
                when (result.status) {
                    401 -> UsernameCheckOutcome.CheckSessionExpired
                    403 -> UsernameCheckOutcome.CheckPremiumGate
                    422 -> UsernameCheckOutcome.CheckInvalidFormat
                    429 -> UsernameCheckOutcome.ProbeExhausted
                    503 -> UsernameCheckOutcome.CheckDisabled
                    // 5xx / any other non-2xx → non-blocking (the probe is non-authoritative; the
                    // PATCH under-lock check is the backstop).
                    else -> UsernameCheckOutcome.CheckNetworkError
                }
        }
}
