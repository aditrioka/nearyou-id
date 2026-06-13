package id.nearyou.app.push

import kotlinx.coroutines.flow.collect

/**
 * Orchestrates FCM token acquisition → backend registration (`mobile-fcm-token-registration`). A stateless
 * Koin singleton: every call reads the current token from [FcmTokenProvider] and registers it via
 * [FcmTokenApiClient]. Invoked on session-active transitions (the authenticated app shell's first
 * composition, [registerCurrentToken]) and for the shell's lifetime ([observeTokenRefreshes]) — both from a
 * shell-scoped coroutine, so neither runs while unauthenticated (no shell ⇒ no registration; the spec's
 * "no register while unauthenticated" guarantee is structural, not an explicit session check).
 *
 * Registration is **idempotent and best-effort**: the backend upsert dedups `(user_id, platform, token)`, so
 * re-registering the same token is a cheap `last_seen_at` refresh; a [FcmRegistrationOutcome.TransportError]
 * is swallowed (returned as a value the trigger ignores, logged token-free) and naturally retried on the next
 * trigger — no bespoke backoff. Concurrent invocations (a cold-start register overlapping a refresh) are
 * intentionally unguarded — duplicate POSTs are harmless by backend idempotency.
 *
 * Token confidentiality (mirrors backend D11): the raw token is NEVER passed to [diagnosticLog] — only the
 * platform + outcome name. No other logging call site exists in this class.
 */
class FcmTokenRegistrar(
    private val provider: FcmTokenProvider,
    private val apiClient: FcmTokenApiClient,
    private val platform: String,
    // Token-free diagnostic sink (no-op by default; wired to the shared DiagnosticSink in Koin). MUST NOT
    // receive the token — only the platform + outcome name are passed.
    private val diagnosticLog: (String) -> Unit = {},
) {
    /**
     * Acquire the current token and register it. Returns [FcmRegistrationOutcome.NoTokenAvailable] without
     * issuing a request when the provider yields no token (e.g. iOS denied authorization, or no configured
     * `FirebaseApp`). Never throws (cancellation aside) — a transport failure is a returned value.
     */
    suspend fun registerCurrentToken(): FcmRegistrationOutcome {
        val token = provider.currentToken()
        val outcome =
            if (token == null) {
                FcmRegistrationOutcome.NoTokenAvailable
            } else {
                apiClient.register(token)
            }
        diagnosticLog("event=fcm_token_registered platform=$platform outcome=${outcome.name()}")
        return outcome
    }

    /**
     * Collect [FcmTokenProvider.tokenRefreshes] and register each rotated token. Suspends for the caller's
     * scope lifetime (the authenticated shell's composition). Because it runs only while the shell is
     * composed, a refresh emitted while signed out is never collected (the structural "no register while
     * unauthenticated" guarantee); on the next sign-in the shell re-composes, [registerCurrentToken] picks
     * up the current token, and this collector restarts.
     */
    suspend fun observeTokenRefreshes() {
        provider.tokenRefreshes.collect { token ->
            val outcome = apiClient.register(token)
            diagnosticLog("event=fcm_token_refreshed platform=$platform outcome=${outcome.name()}")
        }
    }

    /** The outcome's variant name for token-free diagnostics — never includes the token value. */
    private fun FcmRegistrationOutcome.name(): String =
        when (this) {
            is FcmRegistrationOutcome.Registered -> "registered"
            is FcmRegistrationOutcome.Rejected -> "rejected:$code"
            is FcmRegistrationOutcome.Unauthorized -> "unauthorized"
            is FcmRegistrationOutcome.TransportError -> "transport_error"
            is FcmRegistrationOutcome.NoTokenAvailable -> "no_token"
        }
}
