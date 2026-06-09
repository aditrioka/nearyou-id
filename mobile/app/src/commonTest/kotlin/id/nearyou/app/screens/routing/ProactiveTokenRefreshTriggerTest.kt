package id.nearyou.app.screens.routing

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val NOW = 1_000_000L
private const val REFRESH_OK_BODY =
    """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}"""
private val JSON_CT = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * 9.3 — [ProactiveTokenRefreshTrigger] window logic (mobile-session-expiry-and-proactive-refresh, D3).
 * Drives the expiry comparison with the injected `nowMillis` seam (precedent `AuthApiClient.kt:93`)
 * plus a call-counting MockEngine: `<5 min` to expiry on resume ⇒ one non-blocking refresh; `>5 min`
 * ⇒ none; no stored token ⇒ none; a rejected refresh token ⇒ `invalidate()` (store cleared) → the
 * reliable re-route. The shared single-flight is covered by `TokenRefresherTest`.
 */
class ProactiveTokenRefreshTriggerTest {
    private fun trigger(
        store: TokenStore,
        refreshStatus: HttpStatusCode = HttpStatusCode.OK,
        onRefresh: () -> Unit = {},
    ): ProactiveTokenRefreshTrigger {
        val invalidator = SessionInvalidator(store)
        val client =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = store,
                sessionInvalidator = invalidator,
                engine =
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/v1/auth/refresh") {
                            onRefresh()
                            respond(
                                if (refreshStatus == HttpStatusCode.OK) REFRESH_OK_BODY else "",
                                refreshStatus,
                                JSON_CT,
                            )
                        } else {
                            respond("[]", HttpStatusCode.OK, JSON_CT)
                        }
                    },
                installLogging = false,
                nowMillis = { NOW },
            )
        val refresher = TokenRefresher(store, invalidator) { NOW }
        return ProactiveTokenRefreshTrigger(store, refresher, client) { NOW }
    }

    @Test
    fun withinExpiryWindow_refreshesOnce() =
        runTest {
            var calls = 0
            // 4 min to expiry → inside the 5-min window.
            val store = InMemoryTokenStore(TokenPair("at", "rt", NOW + 4L * 60 * 1000))
            trigger(store, onRefresh = { calls++ }).onResume()
            assertEquals(1, calls, "<5 min to expiry ⇒ exactly one proactive POST /api/v1/auth/refresh")
        }

    @Test
    fun outsideExpiryWindow_doesNotRefresh() =
        runTest {
            var calls = 0
            // 10 min to expiry → outside the 5-min window.
            val store = InMemoryTokenStore(TokenPair("at", "rt", NOW + 10L * 60 * 1000))
            trigger(store, onRefresh = { calls++ }).onResume()
            assertEquals(0, calls, ">5 min to expiry ⇒ no proactive refresh")
        }

    @Test
    fun noStoredToken_doesNotRefresh() =
        runTest {
            var calls = 0
            trigger(InMemoryTokenStore(), onRefresh = { calls++ }).onResume()
            assertEquals(0, calls, "not signed in ⇒ nothing to refresh")
        }

    @Test
    fun rejectedRefreshToken_invalidatesAndReRoutes() =
        runTest {
            var calls = 0
            // 1 min to expiry (inside the window); the refresh endpoint rejects the refresh token.
            val store = InMemoryTokenStore(TokenPair("at", "rt", NOW + 60L * 1000))
            trigger(store, refreshStatus = HttpStatusCode.Unauthorized, onRefresh = { calls++ }).onResume()
            assertEquals(1, calls, "within window ⇒ the proactive refresh is attempted")
            assertNull(store.read(), "a rejected refresh token funnels through invalidate() → store cleared (re-route)")
        }
}
