package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import id.nearyou.app.data.dataexport.DataExportApiClient
import id.nearyou.app.data.dataexport.DataExportFlow
import id.nearyou.app.data.dataexport.DataExportRepository
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Koin-wiring check for `mobile-data-export-entry` (mirrors `SettingsKoinResolutionTest`): the real
 * [mobileModule] resolves [DataExportFlow] (→ the concrete [DataExportRepository]) with its
 * [DataExportApiClient] dependency satisfied, when the platform-side leaf deps the data-export subtree
 * REUSES (`TokenStore`, for the shared `HttpClient`) are supplied as test doubles. Lives in commonTest so
 * the resolution is validated on every target (mirrors `KoinInitTest`'s cross-platform graph check).
 */
class DataExportKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesTheDataExportSeam() {
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<ConsentSnapshotStore> { InMemoryConsentSnapshotStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val flow = koin.get<DataExportFlow>()
        assertTrue(flow is DataExportRepository, "DataExportFlow must bind to the concrete repository")
        assertSame(koin.get<DataExportRepository>(), flow, "the seam returns the same DataExportRepository single")
        // The ApiClient dependency resolves (the shared bearer-authed HttpClient is satisfied via the stub TokenStore).
        koin.get<DataExportApiClient>()
    }
}
