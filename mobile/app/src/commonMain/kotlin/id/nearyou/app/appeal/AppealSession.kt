package id.nearyou.app.appeal

/**
 * In-memory holder for the limited **appeal token** captured from the sign-in `403` (the banned/suspended
 * path returns `{ error, appeal_token }`). Registered as a Koin `single` so the sign-in handler (write)
 * and the appeal screen's ViewModel (read) share one instance — the `PendingSignupIdentity` precedent.
 *
 * **Security:** the appeal token is a credential, so — exactly like the verified `id_token` in
 * `PendingSignupIdentity` — it MUST NOT live on a `NavKey` (the Nav3 back stack is serialized to disk on
 * iOS). Holding it in process memory only reproduces that privacy guarantee. Never persisted, logged, or
 * rendered. Because it is in-memory only, it does NOT survive process death — which is what makes a
 * restored back stack on the appeal route fall back to re-sign-in (a fresh token is re-minted at sign-in).
 *
 * [peek] is a non-clearing read so an in-screen retryable error can resubmit with the same token. [clear]
 * drops it on a terminal exit (e.g. an approved appeal → re-sign-in, or leaving the appeal flow).
 */
class AppealSession {
    private var appealToken: String? = null

    /** Stores the limited appeal token, set by the sign-in handler on the banned/suspended `403`. */
    fun set(token: String) {
        appealToken = token
    }

    /** Non-clearing read — the held appeal token, or `null` (process death / not on the banned path / cleared). */
    fun peek(): String? = appealToken

    /** Drops the held token. Idempotent. */
    fun clear() {
        appealToken = null
    }
}
