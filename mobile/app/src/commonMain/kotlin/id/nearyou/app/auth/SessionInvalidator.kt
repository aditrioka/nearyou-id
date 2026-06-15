package id.nearyou.app.auth

import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.NoOpCrashReporter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Single owner of the "terminal-401 ⇒ clear tokens + re-route to SignInScreen" responsibility.
 *
 * Breaks the otherwise-circular dependency between the `HttpClient` (whose `refreshTokens`
 * callback must clear the store on refresh failure) and `AuthRepository` (which the spec
 * names as the owner of the clear + re-route). Both depend on this; neither depends on the
 * other. `SessionExpiryEffect` collects [sessionExpired] to perform the navigator re-route.
 *
 * Per `openspec/specs/mobile-auth-signin/spec.md` § "Refresh failure produces terminal 401 +
 * store cleared by AuthRepository": [invalidate] clears BOTH tokens AND the expiry (i.e. the
 * whole [TokenPair]) — not just the access token.
 *
 * **Delivery (mobile-session-expiry-and-proactive-refresh, D1):** the carrier is a buffered,
 * **consume-once** [Channel] (`CONFLATED`) exposed via [receiveAsFlow], NOT a
 * `MutableSharedFlow(replay = 0)`. The prior replay=0 SharedFlow DROPPED an [invalidate] that
 * fired before `SessionExpiryEffect` subscribed (cold start / no screen hosting the observer),
 * stranding the user. A `CONFLATED` channel BUFFERS a pre-subscription signal and delivers it to
 * the first collector (the signal cannot be lost); receiving CONSUMES it, so a freshly-signed-in
 * user's later-mounted observer does NOT replay a stale invalidation (no spurious re-route loop).
 * `CONFLATED` (capacity-1, drop-oldest) also coalesces a burst of invalidations into one pending
 * signal — re-routing once is the correct behavior.
 */
class SessionInvalidator(
    private val tokenStore: TokenStore,
    // mobile-crash-reporting — clear Sentry user correlation on logout. Defaults no-op so existing
    // constructions/tests are unaffected; MobileModule binds the real reporter.
    private val crashReporter: CrashReporter = NoOpCrashReporter,
) {
    // CONFLATED: never suspends on send, buffers the latest signal for a not-yet-present collector,
    // and a second invalidate before consumption coalesces (one pending re-route, not a queue).
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Emits once per delivered invalidation (consume-once). Buffered for the first collector. */
    val sessionExpired: Flow<Unit> = signal.receiveAsFlow()

    suspend fun invalidate() {
        // Clear the store FIRST (so a re-route observer that reacts immediately reads no token),
        // THEN offer the re-route signal. trySend never fails on a CONFLATED channel.
        tokenStore.clear()
        crashReporter.clearUser()
        signal.trySend(Unit)
    }
}
