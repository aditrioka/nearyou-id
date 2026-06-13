package id.nearyou.app.push

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

/**
 * A test [FcmTokenRegistrar] whose provider yields NO token, so `registerCurrentToken()` short-circuits to
 * `NoTokenAvailable` and `observeTokenRefreshes()` collects a never-emitting flow — it issues NO HTTP. Used
 * by the shell-rendering Robolectric tests (`AppShellScreenTest`, `HomeScreenFabTest`, `HomeTabHostScreenTest`,
 * `RootRouterScreenTest`) to satisfy the shell's `koinInject<FcmTokenRegistrar>()` without any outbound
 * request, keeping their no-fetch / routing assertions intact.
 */
fun fakeFcmTokenRegistrar(): FcmTokenRegistrar {
    val client =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine { respond("", HttpStatusCode.NoContent) },
            installLogging = false,
            nowMillis = { 0L },
        )
    return FcmTokenRegistrar(FakeFcmTokenProvider(token = null), FcmTokenApiClient(client, "android", null), "android")
}
