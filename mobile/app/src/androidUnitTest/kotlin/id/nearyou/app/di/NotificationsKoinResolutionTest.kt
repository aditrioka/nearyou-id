package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.StubLocationProvider
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
 * §11 — Koin-wiring check for `mobile-notifications-list` (mirrors `GlobalTimelineKoinResolutionTest`):
 * the real [mobileModule] resolves [NotificationsFlow] (→ the concrete [NotificationsRepository]) when the
 * platform-side leaf dep it REUSES (`TokenStore`, for the shared `HttpClient`) is supplied as a test double
 * (production binds it in each `platformModule`). Also asserts the seam returns the same singleton. Lives in
 * androidUnitTest (the `HttpClient` actuals construct on the JVM without Robolectric); not a `*ScreenTest`,
 * so it also guards the wiring in the Release variant.
 */
class NotificationsKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesNotificationsFlow_behindTheRepository() {
        // Only the leaf deps the notifications subtree needs are stubbed (TokenStore for the shared
        // HttpClient); Koin resolves lazily, so the platform Google / activity bindings are never touched.
        // The device LocationProvider + permission controller are stubbed for parity with the sibling test
        // (the notifications graph itself needs no LocationProvider).
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val flow = koin.get<NotificationsFlow>()
        assertTrue(flow is NotificationsRepository, "NotificationsFlow must bind to the concrete NotificationsRepository")
        // The flow seam and the concrete resolve to the SAME singleton (single<NotificationsFlow> { get<…>() }).
        assertSame(koin.get<NotificationsRepository>(), flow, "the seam returns the same NotificationsRepository single")
    }
}
