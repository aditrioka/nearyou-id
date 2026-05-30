package id.nearyou.app.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single owner of the "terminal-401 ⇒ clear tokens + re-route to SignInScreen" responsibility.
 *
 * Breaks the otherwise-circular dependency between the `HttpClient` (whose `refreshTokens`
 * callback must clear the store on refresh failure) and `AuthRepository` (which the spec
 * names as the owner of the clear + re-route). Both depend on this; neither depends on the
 * other. `RootRouterScreen` collects [sessionExpired] to perform the navigator re-route.
 *
 * Per `openspec/specs/mobile-auth-signin/spec.md` § "Refresh failure produces terminal 401 +
 * store cleared by AuthRepository": [invalidate] clears BOTH tokens AND the expiry (i.e. the
 * whole [TokenPair]) — not just the access token.
 */
class SessionInvalidator(
    private val tokenStore: TokenStore,
) {
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once each time the session is invalidated (refresh failed / terminal 401). */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    suspend fun invalidate() {
        tokenStore.clear()
        _sessionExpired.tryEmit(Unit)
    }
}
