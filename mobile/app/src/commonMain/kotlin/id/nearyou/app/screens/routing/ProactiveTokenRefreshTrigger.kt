package id.nearyou.app.screens.routing

import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.auth.TokenStore
import io.ktor.client.HttpClient
import kotlin.time.Clock

/**
 * The window before access-token expiry within which an on-resume proactive refresh fires (5 min,
 * per `docs/05-Implementation.md` § Session management line 38). The `<5-min` window IS the idle
 * gate: a brief app-switch leaves >5 min on the token and refreshes nothing; a resume after the
 * token lapsed (or is about to) refreshes once.
 */
const val PROACTIVE_REFRESH_WINDOW_MILLIS: Long = 5L * 60L * 1000L

/**
 * Drives the preemptive token refresh on app resume (mobile-session-expiry-and-proactive-refresh,
 * design D3). On each [onResume], reads the stored [TokenStore] access-token expiry and — when a
 * token pair exists AND the access token is within [PROACTIVE_REFRESH_WINDOW_MILLIS] of expiry (or
 * already expired) — triggers the **shared single-flight** [TokenRefresher], so a proactive refresh
 * coalesces with any concurrent reactive (401-driven) refresh into ONE network round-trip.
 *
 * The expiry comparison uses an injectable [nowMillis] seam (precedent `AuthApiClient.kt:93`), NOT
 * `TimeSource.Monotonic` — monotonic time cannot be compared against an absolute epoch — so the
 * `<5-min` boundary is deterministically unit-testable. A proactive refresh whose refresh token is
 * rejected funnels through `SessionInvalidator.invalidate()` inside [TokenRefresher] → the reliable
 * re-route (no separate error surface here).
 *
 * This class holds the logic so it is testable as a plain `suspend` function; `ProactiveRefreshEffect`
 * is the thin app-root `ON_RESUME` composable that invokes it off the composition (non-blocking).
 */
class ProactiveTokenRefreshTrigger(
    private val tokenStore: TokenStore,
    private val tokenRefresher: TokenRefresher,
    private val httpClient: HttpClient,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun onResume() {
        val tokens = tokenStore.read() ?: return // not signed in → nothing to refresh
        // `<=` is boundary-inclusive AND covers an ALREADY-expired token (the delta goes negative when
        // `accessExpiresAtEpochMillis < now`), so a resume after the token lapsed refreshes once too.
        if (tokens.accessExpiresAtEpochMillis - nowMillis() <= PROACTIVE_REFRESH_WINDOW_MILLIS) {
            tokenRefresher.refresh(httpClient)
        }
    }
}
