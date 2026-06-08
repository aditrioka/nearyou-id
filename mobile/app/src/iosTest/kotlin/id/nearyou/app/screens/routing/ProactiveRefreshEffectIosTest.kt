package id.nearyou.app.screens.routing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_000_000L
private const val REFRESH_OK_BODY =
    """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}"""
private val JSON_CT = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * 9.9 — the iOS actual for the app-root `ON_RESUME` preemptive-refresh hook
 * (mobile-session-expiry-and-proactive-refresh, design D3). PROVES the hook fires on the iOS sim:
 * `ProactiveRefreshEffect` (the real app-root composable) is hosted in a test composable, and a
 * test-owned [LifecycleRegistry] is driven to `RESUMED` so the `LifecycleEventEffect(ON_RESUME)`
 * observer fires — independent of whatever default lifecycle state `runComposeUiTest` provides (the
 * design § "iOS CMP lifecycle parity" fallback: drive the `LifecycleOwner`/`LifecycleRegistry`
 * directly). The fake counter is a call-counting MockEngine: a near-expiry token + a fired `ON_RESUME`
 * ⇒ exactly one `POST /api/v1/auth/refresh`. The window/branch logic is covered in commonTest
 * (`ProactiveTokenRefreshTriggerTest`); this asserts the *lifecycle wiring* works natively on iOS.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ProactiveRefreshEffectIosTest {
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun onResume_firesTheProactiveRefreshOnIos() {
        var refreshCalls = 0
        val store = InMemoryTokenStore(TokenPair("at", "rt", NOW + 60L * 1000)) // 1 min → within window
        val invalidator = SessionInvalidator(store)
        val client =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = store,
                sessionInvalidator = invalidator,
                engine =
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/v1/auth/refresh") {
                            refreshCalls++
                            respond(REFRESH_OK_BODY, HttpStatusCode.OK, JSON_CT)
                        } else {
                            respond("[]", HttpStatusCode.OK, JSON_CT)
                        }
                    },
                installLogging = false,
                nowMillis = { NOW },
            )
        val trigger = ProactiveTokenRefreshTrigger(store, TokenRefresher(store, invalidator) { NOW }, client) { NOW }
        startKoin { modules(module { single { trigger } }) }

        runComposeUiTest {
            // Drive a test-owned lifecycle to RESUMED BEFORE composition: the LifecycleEventEffect's
            // observer, added to an already-RESUMED registry, replays up to ON_RESUME synchronously.
            val owner = TestLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    KoinContext { ProactiveRefreshEffect() }
                }
            }
            waitUntil(timeoutMillis = 5_000) { refreshCalls >= 1 }
            assertEquals(1, refreshCalls, "ON_RESUME on the iOS sim fires exactly one proactive refresh")
        }
    }
}
