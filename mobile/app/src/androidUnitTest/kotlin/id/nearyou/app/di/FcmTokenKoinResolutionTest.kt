package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.push.FakeFcmTokenProvider
import id.nearyou.app.push.FcmTokenApiClient
import id.nearyou.app.push.FcmTokenProvider
import id.nearyou.app.push.FcmTokenRegistrar
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * §5.5 — Koin-wiring check for `mobile-fcm-token-registration` (mirrors `NotificationsKoinResolutionTest`):
 * the real [mobileModule] resolves [FcmTokenRegistrar] (with its [FcmTokenApiClient] + the platform-bound
 * [FcmTokenProvider] dependencies satisfied) when the platform leaf deps it REUSES (`TokenStore` for the
 * shared `HttpClient`) + the platform [FcmTokenProvider] are supplied as test doubles (production binds the
 * provider in each `platformModule`). Lives in androidUnitTest (the `HttpClient` actuals + `BuildConfig`-backed
 * config seam construct on the JVM without Robolectric); guards the wiring in the Release variant too.
 */
class FcmTokenKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesFcmTokenRegistrar_withDependenciesBound() {
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<FcmTokenProvider> { FakeFcmTokenProvider() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val registrar = koin.get<FcmTokenRegistrar>()
        assertNotNull(registrar, "FcmTokenRegistrar must resolve from the Koin graph")
        // The api client (a separate single) also resolves — its HttpClient + platform/app_version deps are bound.
        assertNotNull(koin.get<FcmTokenApiClient>(), "FcmTokenApiClient must resolve with its dependencies bound")
    }
}
