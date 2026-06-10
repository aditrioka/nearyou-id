package id.nearyou.app.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.LocalDate

/**
 * The auth orchestration contract consumed by `SignInScreen` / `AgeGateScreen` / `RootRouterScreen`.
 * The production binding is [AuthRepository]; commonTest substitutes a `FakeAuthFlow` so screen
 * tests can drive a specific outcome / assert (non-)invocation without a backend.
 */
interface AuthFlow {
    suspend fun signInWithGoogle(): SignInOutcome

    /**
     * The Mobile #4 signup-new-user flow. [idToken] is the verified Google ID token carried from
     * the sign-in `404 user_not_found` (reused, NOT re-fetched — design D3); [dateOfBirth] is the
     * picked DOB. Returns the status-driven [SignUpOutcome] (design D8).
     */
    suspend fun signUpWithGoogle(
        idToken: String,
        dateOfBirth: LocalDate,
    ): SignUpOutcome

    suspend fun isAuthenticated(): Boolean
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
    private val signUpMutex = Mutex()

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

    // handleTerminal401 was removed by the 2026-06-10 audit (finding 05-#8): it had no
    // production caller and its KDoc claimed a re-route responsibility that has been
    // owned by TokenRefresher → SessionInvalidator (+ SessionExpiryEffect) since #172 —
    // a duplicated session-invalidation seam inviting a second, conflicting wiring.

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
                    // 404 user_not_found is NOT an error — carry the verified Google id_token into
                    // the age-gate/signup flow (Mobile #4 MODIFIED routing). The screen navigates
                    // to AgeGateScreen; no on-SignInScreen banner.
                    api.status == 404 -> SignInOutcome.NoAccount(idToken)
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

    /**
     * The Mobile #4 signup-new-user orchestration: `POST /api/v1/auth/signup` (reusing the carried
     * Google [idToken]) → token persistence on `201` → a status-driven [SignUpOutcome] (design D8).
     *
     * Concurrency: a double-tap while a signup is in flight is rejected by the [signUpMutex]
     * tryLock guard (returns [SignUpOutcome.Cancelled] — no second `/signup`, no second token
     * write), mirroring [signInWithGoogle]'s defense.
     *
     * Token refresh: a `401 invalid_id_token` (the carried token staled between the sign-in `404`
     * and the DOB submission) triggers ONE fresh `GoogleSignInClient.signIn()` + retry within this
     * call; a second consecutive `401` is terminal ([SignUpOutcome.InvalidIdToken]) — the ceremony
     * is never invoked a third time.
     */
    override suspend fun signUpWithGoogle(
        idToken: String,
        dateOfBirth: LocalDate,
    ): SignUpOutcome {
        if (!signUpMutex.tryLock()) {
            // A signup is already in flight — reject the concurrent invocation silently.
            return SignUpOutcome.Cancelled
        }
        return try {
            // ISO-8601 YYYY-MM-DD (kotlinx-datetime LocalDate.toString()), per the auth-signup wire.
            attemptSignUp(idToken, dateOfBirth.toString(), allowRetry = true)
        } finally {
            signUpMutex.unlock()
        }
    }

    /**
     * Status-driven `/signup` outcome mapping (design D2/D8) — keyed on HTTP status / transport
     * type, NEVER on a parsed `error.code` (the `403` body is the flat `{"error":"user_blocked"}`
     * shape with no `code`). Exhaustive over the documented results with no generic-failure
     * fallthrough: `201`→Success, `403`→Blocked, `409`→AccountExists, `401`→one refresh+retry then
     * terminal, `400`/`503`/`5xx`/IO→[SignUpOutcome.RetryableError].
     */
    private suspend fun attemptSignUp(
        idToken: String,
        dateOfBirth: String,
        allowRetry: Boolean,
    ): SignUpOutcome =
        when (val api = authApiClient.signUp(idToken, dateOfBirth)) {
            is SignInApiResult.Success -> {
                tokenStore.write(api.tokens)
                SignUpOutcome.Success
            }
            is SignInApiResult.NetworkError -> {
                diagnosticLog("signup_network_error: ${api.cause.message}")
                SignUpOutcome.RetryableError
            }
            is SignInApiResult.HttpError ->
                when {
                    // 403 user_blocked — under-18 OR already-rejected identifier (byte-identical
                    // flat bodies). Status-driven (D2): a code-based map would misroute it to the
                    // retryable fallthrough since the flat body has no parseable `error.code`. No
                    // token write, no nav; the screen shows the local age_gate_under18_blocked copy.
                    api.status == 403 -> SignUpOutcome.Blocked
                    api.status == 409 -> SignUpOutcome.AccountExists
                    // invalid_id_token: refresh the Google token ONCE and retry; a second 401 is
                    // terminal (the inner attempt below runs with allowRetry = false).
                    api.status == 401 ->
                        if (allowRetry) refreshTokenAndRetrySignUp(dateOfBirth) else SignUpOutcome.InvalidIdToken
                    // 400 invalid_request is not expected from a well-formed picker submission —
                    // surface it as retryable WITH a logged diagnostic (not a silent no-op/crash).
                    api.status == 400 -> {
                        diagnosticLog("signup_invalid_request: status=400 code=${api.code}")
                        SignUpOutcome.RetryableError
                    }
                    // 5xx incl. 503 username_generation_failed, and any unenumerated status: a
                    // defined retryable state (signin_error_network + cta_retry), NOT a generic
                    // "signup failed" fallthrough.
                    else -> SignUpOutcome.RetryableError
                }
        }

    /**
     * On `401 invalid_id_token`, re-run the Google ceremony ONCE for a fresh token then retry
     * `/signup` (allowRetry = false, so a second 401 yields [SignUpOutcome.InvalidIdToken]). A
     * cancelled refresh sheet is treated as terminal token-invalid; a failed ceremony is a
     * retryable connectivity problem (mirroring the sign-in `Failed`→NetworkError mapping).
     */
    private suspend fun refreshTokenAndRetrySignUp(dateOfBirth: String): SignUpOutcome =
        when (val ceremony = googleSignIn.signIn()) {
            is GoogleSignInResult.Success -> attemptSignUp(ceremony.idToken, dateOfBirth, allowRetry = false)
            is GoogleSignInResult.UserCancelled -> SignUpOutcome.InvalidIdToken
            is GoogleSignInResult.Failed -> {
                diagnosticLog("signup_refresh_ceremony_failed: ${ceremony.message}")
                SignUpOutcome.RetryableError
            }
        }
}

/**
 * The six distinct UI states from Decision 7. (Seven RESULT rows converge into six OUTCOMES:
 * `GoogleSignInResult.Failed` and HTTP-5xx/network-IO both map to [NetworkError].)
 */
sealed interface SignInOutcome {
    data object Success : SignInOutcome

    /**
     * `404 user_not_found` — no account yet for this verified Google identity. Carries the
     * verified [idToken] from the `GoogleSignInResult.Success` so the screen can navigate to
     * `AgeGateScreen` and the signup flow reuses it without a second Google ceremony (Mobile #4
     * MODIFIED routing, design D3). NOT an error state — no on-`SignInScreen` banner.
     */
    data class NoAccount(val idToken: String) : SignInOutcome

    data object Banned : SignInOutcome

    data object InvalidIdToken : SignInOutcome

    data object NetworkError : SignInOutcome

    data object Cancelled : SignInOutcome
}
