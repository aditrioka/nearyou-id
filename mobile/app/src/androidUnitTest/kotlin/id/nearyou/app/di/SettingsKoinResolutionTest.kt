package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersRepository
import id.nearyou.app.data.consent.ConsentSnapshotStore
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
 * §7.1 — Koin-wiring check for `mobile-settings-screen` (mirrors `NotificationsKoinResolutionTest`): the
 * real [mobileModule] resolves [BlockedUsersFlow] (→ the concrete [BlockedUsersRepository]) + the
 * [ConsentSnapshotStore] when the platform-side leaf dep the block subtree REUSES (`TokenStore`, for the
 * shared `HttpClient`) is supplied as a test double. Confirms the settings view-model dependencies all
 * resolve. Lives in androidUnitTest so it also guards the wiring in the Release variant.
 */
class SettingsKoinResolutionTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun mobileModule_resolvesTheSettingsDependencies() {
        val stubPlatform =
            module {
                single<TokenStore> { InMemoryTokenStore() }
                single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                single<LocationPermissionController> { FakeLocationPermissionController() }
            }
        val koin = startKoin { modules(mobileModule, stubPlatform) }.koin

        val blockedFlow = koin.get<BlockedUsersFlow>()
        assertTrue(blockedFlow is BlockedUsersRepository, "BlockedUsersFlow must bind to the concrete repository")
        assertSame(koin.get<BlockedUsersRepository>(), blockedFlow, "the seam returns the same BlockedUsersRepository single")

        // The consent snapshot store + TokenStore (logout) resolve for the settings view models.
        koin.get<ConsentSnapshotStore>()
        koin.get<TokenStore>()
    }
}
