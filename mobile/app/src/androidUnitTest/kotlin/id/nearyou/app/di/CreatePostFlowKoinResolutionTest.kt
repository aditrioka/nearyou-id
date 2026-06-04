package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.StubLocationProvider
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Focused Koin-wiring check for mobile-post-creation-screen (task 6.2): the real [mobileModule]
 * resolves [CreatePostFlow] (→ the concrete [CreatePostRepository]) when the platform-side leaf
 * dependencies it REUSES — `TokenStore`, `LocationProvider`, `LocationPermissionController` — are
 * supplied as test doubles (production binds them in each `platformModule`). This confirms the three
 * new singles are wired and that the repository consumes the EXISTING location/permission bindings
 * (no new binding introduced).
 *
 * Lives in androidUnitTest (not commonTest) because resolving the graph instantiates the shared
 * `HttpClient` via the platform `httpClientEngine()` / `apiBaseUrl` actuals — the Android (OkHttp +
 * `BuildConfig`) actuals construct cleanly on the JVM without Robolectric, whereas the iOS actuals
 * would need a simulator. Not a `*ScreenTest`, so it also runs (and guards the wiring) in the Release
 * variant.
 */
class CreatePostFlowKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        // Sibling test classes in this JVM target may have started Koin; reset to a clean slate.
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesCreatePostFlow_behindTheRepository() {
        // Only the leaf deps the create-post subtree needs are stubbed; the platform Google /
        // activity bindings are NOT loaded (Koin resolves lazily, so they are never touched).
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider> { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val flow = koin.get<CreatePostFlow>()
        assertTrue(flow is CreatePostRepository, "CreatePostFlow must bind to the concrete CreatePostRepository")
        // The flow seam and the concrete resolve to the SAME singleton (single<CreatePostFlow> { get<…>() }).
        assertSame(koin.get<CreatePostRepository>(), flow, "the seam returns the same CreatePostRepository single")
    }
}
