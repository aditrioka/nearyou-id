package id.nearyou.app.screens.routing

/**
 * In-memory holder for the verified Google `id_token` carried from the sign-in no-account path
 * (`SignInOutcome.NoAccount`) to the age-gate signup flow. Registered as a Koin `single` (see
 * `mobileModule`) so `SignInScreen` (write) and `AgeGateScreen` (read) share one instance.
 *
 * **Security (design Decision 4 + `mobile-age-gate` § "The verified id_token is never written to the
 * serialized back stack"):** under Nav3 the back stack IS serialized on iOS, so the token MUST NOT
 * live on a `NavKey`. Holding it here — in process memory only — reproduces the privacy guarantee
 * the prior Voyager (never-serialized) back stack provided for free. The token is never persisted,
 * never logged, and never rendered.
 *
 * **Lifecycle (design Decision 4):** [peek] is a **non-clearing** read so an in-screen retryable
 * error (network/5xx) can resubmit with the same identity. [clear] is called on every **terminal**
 * transition OUT of the age-gate flow (Success → Home, AccountExists → SignIn, absent-identity
 * re-route → SignIn) so the verified token does not linger in memory past the flow. Because the
 * holder is in-memory only, it does NOT survive process death — which is what makes a restored
 * back stack on `AgeGateRoute` re-route to sign-in (the process-death contract).
 */
class PendingSignupIdentity {
    private var idToken: String? = null

    /** Stores the verified Google [idToken] just before the sign-in no-account path appends `AgeGateRoute`. */
    fun set(idToken: String) {
        this.idToken = idToken
    }

    /** Non-clearing read — returns the held `id_token`, or `null` if absent (process death / already cleared). */
    fun peek(): String? = idToken

    /** Drops the held token. Called on every terminal exit from the age-gate flow; idempotent. */
    fun clear() {
        idToken = null
    }
}
