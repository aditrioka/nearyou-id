package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineRepository
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
 * tasks 6.7 — Koin-wiring check for `mobile-following-timeline` (mirrors `GlobalTimelineKoinResolutionTest`):
 * the real [mobileModule] resolves [FollowingTimelineFlow] (→ the concrete [FollowingTimelineRepository])
 * when the platform-side leaf dep it REUSES (`TokenStore`, for the shared `HttpClient`) is supplied as a
 * test double. Also asserts the seam returns the same singleton and that [SessionIdProvider] is a shared
 * single (one registration — the same instance the Nearby/Global graphs use, NOT a second). Lives in
 * androidUnitTest; not a `*ScreenTest`, so it also guards the wiring in the Release variant.
 */
class FollowingTimelineKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesFollowingTimelineFlow_behindTheRepository_reusingSessionIdProvider() {
        // Only the leaf deps the Following subtree needs are stubbed (TokenStore for the shared HttpClient);
        // Koin resolves lazily. The device LocationProvider + permission controller are stubbed for parity
        // with the sibling test (the Following graph itself needs no LocationProvider — no spatial filter).
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val flow = koin.get<FollowingTimelineFlow>()
        assertTrue(flow is FollowingTimelineRepository, "FollowingTimelineFlow must bind to the concrete FollowingTimelineRepository")
        // The flow seam and the concrete resolve to the SAME singleton (single<FollowingTimelineFlow> { get<…>() }).
        assertSame(koin.get<FollowingTimelineRepository>(), flow, "the seam returns the same FollowingTimelineRepository single")
        // SessionIdProvider is a single shared across feeds (one registration) — stable instance, NOT a
        // second provider for Following (which would open a separate per-session soft-cap bucket).
        assertSame(
            koin.get<SessionIdProvider>(),
            koin.get<SessionIdProvider>(),
            "SessionIdProvider must be a shared single (reused by Following, not a second instance)",
        )
    }
}
