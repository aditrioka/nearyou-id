package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
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
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * `mobile-chat-message-report` task 4.8 — Koin-wiring check for the chat-thread report path (mirrors
 * `LikeFlowKoinResolutionTest`). `ChatThreadScreen` now `koinInject`s a [ReportSubmitter] to pass into the
 * route-scoped `ChatThreadViewModel`; this test proves the real [mobileModule] resolves that dependency
 * (the SAME shared singleton the post-detail / profile report surfaces consume — the "one shared report
 * seam" guarantee) alongside the chat thread's own [ChatFlow] + [ViewerIdProvider]. Lives in
 * androidUnitTest (the `HttpClient` actuals construct on the JVM without Robolectric); not a `*ScreenTest`,
 * so it also guards the wiring in the Release variant. Koin resolves lazily, so the platform Google /
 * Supabase-realtime transport bindings are never touched.
 */
class ChatReportKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesChatThreadReportDependencies() {
        // Only the leaf deps the chat report subtree needs are stubbed (TokenStore for the shared
        // HttpClient behind ReportApiClient / ChatRepository / TokenViewerIdProvider); location stubs for
        // parity with the sibling resolution tests.
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        // The new dependency the chat screen injects resolves, and is a single shared instance (one seam).
        val reportSubmitter = koin.get<ReportSubmitter>()
        assertNotNull(reportSubmitter)
        assertSame(reportSubmitter, koin.get<ReportSubmitter>(), "ReportSubmitter is a shared singleton")

        // The chat thread's own data + identity deps resolve (the rest of the ChatThreadViewModel graph;
        // the realtime subscriber is intentionally not force-resolved — its transport config is platform).
        assertNotNull(koin.get<ChatFlow>())
        assertNotNull(koin.get<ViewerIdProvider>())
    }
}
