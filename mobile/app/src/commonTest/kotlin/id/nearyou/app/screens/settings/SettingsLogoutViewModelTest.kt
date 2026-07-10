package id.nearyou.app.screens.settings

import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.push.FakeFcmTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comfortably unexpired so the Auth plugin attaches the Bearer without a proactive refresh. */
private const val FUTURE_EPOCH = Long.MAX_VALUE / 2

/**
 * Unit coverage of [SettingsViewModel.confirmLogout]'s server-revoke path (`mobile-settings` delta,
 * logout-revocation). Pins: the `POST /api/v1/auth/logout` is issued BEFORE the token wipe (with the
 * stored refresh token + the device FCM token; the store is still populated at request time); a null
 * FCM token omits the `fcm_token` key; a failing call still wipes + raises [SettingsViewModel.loggedOut]
 * (best-effort contract); no wired [AuthApiClient] degrades to the client-side-only wipe. The cancel
 * path issues no request structurally — the screen's cancel affordance never invokes `confirmLogout`
 * (covered by `SettingsScreenTest`'s cancel test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLogoutViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun client(
        store: InMemoryTokenStore,
        handler: MockRequestHandler,
    ): HttpClient =
        HttpClientFactory.create(
            apiBaseUrl = "http://test.local",
            tokenStore = store,
            sessionInvalidator = SessionInvalidator(store),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    @Test
    fun confirmLogout_postsRefreshAndFcmTokenBeforeTheWipe() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt-1", FUTURE_EPOCH))
            var requestPath: String? = null
            var requestBody: String? = null
            var storePopulatedAtRequestTime = false
            val http =
                client(store) { request ->
                    requestPath = request.url.encodedPath
                    requestBody = (request.body as TextContent).text
                    storePopulatedAtRequestTime = store.read() != null
                    respond("", HttpStatusCode.NoContent, headersOf())
                }
            val vm =
                SettingsViewModel(
                    tokenStore = store,
                    authApi = AuthApiClient(http),
                    fcmTokenProvider = FakeFcmTokenProvider(token = "fcm-A"),
                )

            vm.confirmLogout()
            vm.loggedOut.first { it }

            assertEquals("/api/v1/auth/logout", requestPath)
            assertTrue(storePopulatedAtRequestTime, "the revoke must be issued BEFORE the wipe (Bearer still stored)")
            assertTrue(requestBody!!.contains("\"refresh_token\":\"rt-1\""), "body was: $requestBody")
            assertTrue(requestBody!!.contains("\"fcm_token\":\"fcm-A\""), "body was: $requestBody")
            assertNull(store.read(), "the wipe must still happen after the call")
        }

    @Test
    fun confirmLogout_withoutAnFcmTokenOmitsTheKey() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt-2", FUTURE_EPOCH))
            var requestBody: String? = null
            val http =
                client(store) { request ->
                    requestBody = (request.body as TextContent).text
                    respond("", HttpStatusCode.NoContent, headersOf())
                }
            val vm =
                SettingsViewModel(
                    tokenStore = store,
                    authApi = AuthApiClient(http),
                    fcmTokenProvider = FakeFcmTokenProvider(token = null),
                )

            vm.confirmLogout()
            vm.loggedOut.first { it }

            assertTrue(requestBody!!.contains("\"refresh_token\":\"rt-2\""), "body was: $requestBody")
            assertFalse(requestBody!!.contains("fcm_token"), "null FCM token must omit the key; body was: $requestBody")
            assertNull(store.read())
        }

    @Test
    fun confirmLogout_stillWipesAndRoutesWhenTheServerCallFails() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt-3", FUTURE_EPOCH))
            val http = client(store) { throw RuntimeException("network down") }
            val vm =
                SettingsViewModel(
                    tokenStore = store,
                    authApi = AuthApiClient(http),
                    fcmTokenProvider = FakeFcmTokenProvider(token = "fcm-B"),
                )

            vm.confirmLogout()
            vm.loggedOut.first { it }

            assertNull(store.read(), "offline logout must still wipe locally")
        }

    @Test
    fun confirmLogout_withNoWiredApiDegradesToTheClientSideWipe() =
        runTest {
            val store = InMemoryTokenStore(TokenPair("at", "rt-4", FUTURE_EPOCH))
            val vm = SettingsViewModel(tokenStore = store)

            vm.confirmLogout()
            vm.loggedOut.first { it }

            assertNull(store.read())
        }
}
