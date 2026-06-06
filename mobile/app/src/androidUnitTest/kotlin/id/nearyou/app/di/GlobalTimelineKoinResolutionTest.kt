package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.SessionIdProvider
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
 * 7.2 — Koin-wiring check for `mobile-global-timeline` (mirrors `CreatePostFlowKoinResolutionTest`):
 * the real [mobileModule] resolves [GlobalTimelineFlow] (→ the concrete [GlobalTimelineRepository])
 * when the platform-side leaf dep it REUSES (`TokenStore`, for the shared `HttpClient`) is supplied as
 * a test double (production binds it in each `platformModule`). Also asserts the seam returns the same
 * singleton and that [SessionIdProvider] is a shared single (one registration — the same instance the
 * Nearby graph uses, NOT a second). Lives in androidUnitTest (the `HttpClient` actuals construct on the
 * JVM without Robolectric); not a `*ScreenTest`, so it also guards the wiring in the Release variant.
 */
class GlobalTimelineKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesGlobalTimelineFlow_behindTheRepository_reusingSessionIdProvider() {
        // Only the leaf deps the Global subtree needs are stubbed (TokenStore for the shared HttpClient);
        // Koin resolves lazily, so the platform Google / activity bindings are never touched. The device
        // LocationProvider + permission controller are stubbed for parity with the sibling test (the
        // Global graph itself needs no LocationProvider — Global has no spatial filter).
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val flow = koin.get<GlobalTimelineFlow>()
        assertTrue(flow is GlobalTimelineRepository, "GlobalTimelineFlow must bind to the concrete GlobalTimelineRepository")
        // The flow seam and the concrete resolve to the SAME singleton (single<GlobalTimelineFlow> { get<…>() }).
        assertSame(koin.get<GlobalTimelineRepository>(), flow, "the seam returns the same GlobalTimelineRepository single")
        // SessionIdProvider is a single shared across feeds (one registration) — stable instance, NOT a
        // second provider for Global (which would open a separate per-session soft-cap bucket).
        assertSame(
            koin.get<SessionIdProvider>(),
            koin.get<SessionIdProvider>(),
            "SessionIdProvider must be a shared single (reused by Global, not a second instance)",
        )
    }
}
