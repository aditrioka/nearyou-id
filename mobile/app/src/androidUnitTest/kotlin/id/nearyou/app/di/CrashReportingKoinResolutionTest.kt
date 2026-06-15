package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.diagnostics.CompositeDiagnosticSink
import id.nearyou.app.diagnostics.CrashReportingController
import id.nearyou.app.diagnostics.DiagnosticSink
import id.nearyou.app.infra.sentry.CrashReporter
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
import kotlin.test.assertTrue

/**
 * mobile-crash-reporting §7.5 — Koin-wiring check (mirrors `SettingsKoinResolutionTest`): the real
 * [mobileModule] resolves the [CrashReporter], the [CrashReportingController], and the [DiagnosticSink]
 * (which must bind to the [CompositeDiagnosticSink] fanning into the Sentry breadcrumb sink — proving the
 * sink → CrashReporter dependency wires). Lives in androidUnitTest so it also guards the Release variant.
 */
class CrashReportingKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesTheCrashReportingGraph() {
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        koin.get<CrashReporter>()
        koin.get<CrashReportingController>()
        val sink = koin.get<DiagnosticSink>()
        assertTrue(sink is CompositeDiagnosticSink, "DiagnosticSink must bind to the composite (console + Sentry breadcrumb)")
    }
}
