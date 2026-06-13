package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostDetailRepository
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
 * 1.4 — Koin-wiring check for `mobile-inline-post-actions`' extracted like seam (mirrors
 * `GlobalTimelineKoinResolutionTest`): the real [mobileModule] resolves [LikeFlow] AND
 * [PostDetailFlow] to the SAME [PostDetailRepository] singleton — the spec's "no second like
 * client/repository" guarantee (`mobile-post-detail` § "Repository and ApiClient wired as Koin
 * singletons behind the PostDetailFlow seam"). Lives in androidUnitTest (the `HttpClient` actuals
 * construct on the JVM without Robolectric); not a `*ScreenTest`, so it also guards the wiring in
 * the Release variant.
 */
class LikeFlowKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_bindsLikeFlowAndPostDetailFlow_toTheSamePostDetailRepositorySingleton() {
        // Only the leaf deps the post-detail subtree needs are stubbed (TokenStore for the shared
        // HttpClient); Koin resolves lazily, so the platform Google / activity bindings are never
        // touched. Location stubs for parity with the sibling resolution tests.
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val likeFlow = koin.get<LikeFlow>()
        assertTrue(likeFlow is PostDetailRepository, "LikeFlow must bind to the concrete PostDetailRepository")
        assertSame(
            koin.get<PostDetailRepository>(),
            likeFlow,
            "the like seam returns the same PostDetailRepository single",
        )
        assertSame(
            koin.get<PostDetailFlow>(),
            likeFlow,
            "PostDetailFlow and LikeFlow share ONE singleton — no second like client/repository",
        )
    }
}
