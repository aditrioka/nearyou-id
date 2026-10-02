package id.nearyou.app.di

import id.nearyou.app.ads.AdFeedController
import id.nearyou.app.ads.FakeAdProvider
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.FakeGoogleSignInGateway
import id.nearyou.app.auth.GoogleSignInGateway
import id.nearyou.app.auth.GoogleSignInResult
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.StubLocationProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * premium-entitlement-lifecycle §6.11 — Koin-wiring check (mirrors `CrashReportingKoinResolutionTest`): the
 * real [mobileModule] resolves [PremiumEntitlementSession] as a single AND every consumer it was threaded into
 * — [SessionInvalidator] (itself a dependency of the shared [HttpClient]), [AuthRepository], and
 * [AdFeedController] (which depends on the HttpClient via ads-config). A Koin resolution cycle (design D2/D6:
 * SessionInvalidator → session must not reach back into the HttpClient) would stack-overflow here.
 */
class PremiumEntitlementKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesTheEntitlementGraphWithoutACycle() {
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<ConsentSnapshotStore> { InMemoryConsentSnapshotStore() }
                single<GoogleSignInGateway> { FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled) }
                single<AdProvider> { FakeAdProvider() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val session = koin.get<PremiumEntitlementSession>()
        assertSame(session, koin.get<PremiumEntitlementSession>(), "the session must be a single (one shared signal)")
        koin.get<SessionInvalidator>()
        koin.get<HttpClient>()
        koin.get<AuthRepository>()
        koin.get<AdFeedController>()
    }

    @Test
    fun mobileModule_wiresTheAdControllerToTheSameSessionSignal() =
        runTest {
            val provider = RecordingProvider()
            val stubPlatform =
                module {
                    single<TokenStore> { InMemoryTokenStore() }
                    single<ConsentSnapshotStore> { InMemoryConsentSnapshotStore() }
                    single<GoogleSignInGateway> { FakeGoogleSignInGateway(GoogleSignInResult.UserCancelled) }
                    single<AdProvider> { provider }
                    single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                    single<LocationPermissionController> { FakeLocationPermissionController() }
                }
            val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

            // A purchase confirmed on the session the paywall uses must reach the ads controller.
            koin.get<PremiumEntitlementSession>().onPurchaseConfirmed()
            koin.get<AdFeedController>().prepare()

            assertTrue(provider.initializeCount == 0, "a confirmed buyer never triggers ad SDK init / UMP")
        }

    private class RecordingProvider : AdProvider by FakeAdProvider() {
        var initializeCount = 0

        override suspend fun initialize() {
            initializeCount++
        }
    }
}
