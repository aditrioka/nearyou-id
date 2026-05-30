package id.nearyou.app.auth

import kotlinx.coroutines.sync.Mutex

/**
 * The sign-in orchestration contract consumed by `SignInScreen` + `RootRouterScreen`. The
 * production binding is [AuthRepository]; commonTest substitutes a `FakeAuthFlow` so screen
 * tests can drive a specific outcome / assert (non-)invocation without a backend.
 */
interface AuthFlow {
    suspend fun signInWithGoogle(): SignInOutcome

    suspend fun isAuthenticated(): Boolean

    suspend fun handleTerminal401()
}

/**
 * Orchestrates the end-to-end sign-in flow: Google ceremony → backend `/signin` → token
 * persistence → outcome emission. Maps every observed result onto exactly one
 * [SignInOutcome] per `design.md` Decision 7 — there is NO generic "sign-in failed"
 * fallthrough.
 *
 * Concurrency: a double-tap on the CTA while a sign-in is in flight is rejected by the
 * [signInMutex] tryLock guard (returns [SignInOutcome.Cancelled] without re-invoking the
 * ceremony) per the spec scenario "Double-tap on CTA rejects the second concurrent invocation".
 *
 * Auto-retry: a `401 invalid_id_token` triggers ONE automatic re-run of the Google ceremony
 * within the same `signInWithGoogle()` call (the retry budget is per-call, so it naturally
 * resets across SignInScreen re-entries — the spec's "screen-state-local counter").
 */
class AuthRepository(
    private val googleSignIn: GoogleSignInGateway,
    private val authApiClient: AuthApiClient,
    private val tokenStore: TokenStore,
    private val sessionInvalidator: SessionInvalidator,
    // Diagnostic sink for non-user-facing error detail (Google ceremony Failed message,
    // network cause). Wired to Sentry / OTel when that lands; no-op for now. MUST NOT carry
    // tokens (none are passed here).
    private val diagnosticLog: (String) -> Unit = {},
) : AuthFlow {
    private val signInMutex = Mutex()

    override suspend fun signInWithGoogle(): SignInOutcome {
        if (!signInMutex.tryLock()) {
            // A sign-in is already in flight — reject the concurrent invocation silently
            // (no second ceremony, no second token write). Cancelled = no error banner.
            return SignInOutcome.Cancelled
        }
        return try {
            attemptSignIn(allowRetry = true)
        } finally {
            signInMutex.unlock()
        }
    }

    /** True when a persisted [TokenPair] exists. The Ktor `Auth` plugin refreshes a stale
     *  access token lazily on the first authenticated call; if the refresh token is itself
     *  expired/revoked the backend rejects it, `SessionInvalidator` clears the store, and the
     *  next launch reads `null` here. (We do not track the refresh-token expiry client-side;
     *  the strictly-future access-expiry comparison the spec mentions is the Auth plugin's
     *  `loadTokens` concern, not a routing gate.) */
    override suspend fun isAuthenticated(): Boolean = tokenStore.read() != null

    /** Invoked when the bearer refresh fails terminally (Ktor `refreshTokens` returned null).
     *  Clears the whole TokenPair + signals `RootRouterScreen` to re-route to SignInScreen. */
    override suspend fun handleTerminal401() {
        sessionInvalidator.invalidate()
    }

    private suspend fun attemptSignIn(allowRetry: Boolean): SignInOutcome =
        when (val ceremony = googleSignIn.signIn()) {
            is GoogleSignInResult.UserCancelled -> SignInOutcome.Cancelled
            is GoogleSignInResult.Failed -> {
                diagnosticLog("google_sign_in_failed: ${ceremony.message}")
                SignInOutcome.NetworkError
            }
            is GoogleSignInResult.Success -> exchangeIdToken(ceremony.idToken, allowRetry)
        }

    private suspend fun exchangeIdToken(
        idToken: String,
        allowRetry: Boolean,
    ): SignInOutcome =
        when (val api = authApiClient.signIn(idToken)) {
            is SignInApiResult.Success -> {
                tokenStore.write(api.tokens)
                SignInOutcome.Success
            }
            is SignInApiResult.NetworkError -> {
                diagnosticLog("signin_network_error: ${api.cause.message}")
                SignInOutcome.NetworkError
            }
            is SignInApiResult.HttpError ->
                when {
                    api.status == 404 -> SignInOutcome.NoAccount
                    api.status == 403 -> SignInOutcome.Banned
                    api.status == 401 ->
                        // invalid_id_token: re-run the Google ceremony ONCE for a fresh token,
                        // then re-submit. A second 401 is terminal.
                        if (allowRetry) attemptSignIn(allowRetry = false) else SignInOutcome.InvalidIdToken
                    api.status in 500..599 -> SignInOutcome.NetworkError
                    // Unenumerated status: treat as a connectivity/server problem (a defined
                    // state) rather than a generic "failed" fallthrough.
                    else -> SignInOutcome.NetworkError
                }
        }
}

/**
 * The six distinct UI states from Decision 7. (Seven RESULT rows converge into six OUTCOMES:
 * `GoogleSignInResult.Failed` and HTTP-5xx/network-IO both map to [NetworkError].)
 */
sealed interface SignInOutcome {
    data object Success : SignInOutcome

    data object NoAccount : SignInOutcome

    data object Banned : SignInOutcome

    data object InvalidIdToken : SignInOutcome

    data object NetworkError : SignInOutcome

    data object Cancelled : SignInOutcome
}
