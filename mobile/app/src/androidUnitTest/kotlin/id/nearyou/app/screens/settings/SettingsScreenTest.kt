package id.nearyou.app.screens.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.hidedistance.HideDistanceState
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TITLE = "Pengaturan"
private const val PRIVACY_DATA = "Privasi & data"
private const val BLOCKED = "Pengguna diblokir"
private const val PRIVATE_PROFILE = "Profil privat"
private const val HIDE_DISTANCE = "Sembunyikan jarak"
private const val HIDE_DISTANCE_UPSELL = "Fitur Premium. Aktifkan Premium untuk menyembunyikan jarak."
private const val HIDE_DISTANCE_ERROR = "Gagal memperbarui pengaturan. Coba lagi."
private const val COMING_SOON = "Segera hadir"

/** A configurable [HideDistanceRepository] fake: seeds the toggle state and records write calls. */
private class FakeHideDistanceRepository(
    private val initial: HideDistanceState? = null,
    private val writeSucceeds: Boolean = true,
) : HideDistanceRepository {
    val setCalls = mutableListOf<Boolean>()

    override suspend fun loadState(): HideDistanceState? = initial

    override suspend fun setHideDistance(value: Boolean): Boolean {
        setCalls += value
        return writeSucceeds
    }
}

private const val LOGOUT_ROW = "Keluar"
private const val LOGOUT_DIALOG_TITLE = "Keluar dari akun?"
private const val LOGOUT_CONFIRM = "Ya, keluar"
private const val LOGOUT_CANCEL = "Batal"

/**
 * Render coverage of `SettingsScreen` (mockup frame 16). Verifies the grouped section headers + app bar,
 * the backed rows navigate, a deferred row shows a non-trapping "Segera hadir" (no write/navigation), the
 * out-of-scope surfaces (account deletion / data export / suspension) are absent, and logout confirms →
 * wipes the token + routes (cancel does neither).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private lateinit var tokenStore: InMemoryTokenStore

    private fun installKoin(hideDistance: HideDistanceRepository = FakeHideDistanceRepository()) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        tokenStore = InMemoryTokenStore()
        startKoin {
            modules(
                module {
                    single<TokenStore> { tokenStore }
                    single<HideDistanceRepository> { hideDistance }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun rendersAppBarAndFourSectionHeaders() {
        installKoin()
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("AKUN").assertExists()
            onNodeWithText("PREMIUM").assertExists()
            onNodeWithText("PRIVASI").assertExists()
            onNodeWithText("LAINNYA").assertExists()
        }
    }

    @Test
    fun backedRows_navigateToTheirDestinations() {
        installKoin()
        var blocked = 0
        var consent = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(onBack = {}, onOpenBlocked = { blocked++ }, onOpenConsent = { consent++ }, onLoggedOut = {})
                    }
                }
            }
            onNodeWithText(BLOCKED).performScrollTo().performClick()
            onNodeWithText(PRIVACY_DATA).performScrollTo().performClick()
            assertEquals(1, blocked)
            assertEquals(1, consent)
        }
    }

    @Test
    fun deferredRow_showsComingSoon_andDoesNotNavigate() {
        installKoin()
        var blocked = 0
        var consent = 0
        var loggedOut = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(
                            onBack = {},
                            onOpenBlocked = { blocked++ },
                            onOpenConsent = { consent++ },
                            onLoggedOut = { loggedOut++ },
                        )
                    }
                }
            }
            onNodeWithText(PRIVATE_PROFILE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(COMING_SOON).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(COMING_SOON).assertExists()
            // A deferred row performs NO navigation and NO logout (and structurally no backend write).
            assertEquals(0, blocked)
            assertEquals(0, consent)
            assertEquals(0, loggedOut)
        }
    }

    @Test
    fun outOfScopeSurfaces_areAbsent() {
        installKoin()
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            onAllNodesWithText("Hapus Akun", substring = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
            onAllNodesWithText("Unduh Data", substring = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
            onAllNodesWithText("suspensi", substring = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        }
    }

    @Test
    fun logout_confirm_wipesTokenAndRoutes() {
        installKoin()
        runBlocking { tokenStore.write(TokenPair("at", "rt", 1L)) }
        var loggedOut = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(
                            onBack = {},
                            onOpenBlocked = {},
                            onOpenConsent = {},
                            onLoggedOut = { loggedOut++ },
                        )
                    }
                }
            }
            onNodeWithText(LOGOUT_ROW).performScrollTo().performClick()
            onNodeWithText(LOGOUT_DIALOG_TITLE).assertExists()
            onNodeWithText(LOGOUT_CONFIRM).performClick()
            waitUntil { loggedOut == 1 }
            assertEquals(1, loggedOut)
            assertNull(runBlocking { tokenStore.read() }, "logout clears the token store")
        }
    }

    @Test
    fun logout_cancel_keepsSessionIntact() {
        installKoin()
        runBlocking { tokenStore.write(TokenPair("at", "rt", 1L)) }
        var loggedOut = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(
                            onBack = {},
                            onOpenBlocked = {},
                            onOpenConsent = {},
                            onLoggedOut = { loggedOut++ },
                        )
                    }
                }
            }
            onNodeWithText(LOGOUT_ROW).performScrollTo().performClick()
            onNodeWithText(LOGOUT_CANCEL).performClick()
            assertEquals(0, loggedOut)
            assertEquals("at", runBlocking { tokenStore.read() }?.accessToken, "cancel leaves the session intact")
        }
    }

    @Test
    fun hideDistance_premiumToggle_issuesSingleWrite() {
        val fake = FakeHideDistanceRepository(initial = HideDistanceState(hideDistance = false, premium = true))
        installKoin(fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the init GET (loadState) settle so premium=true before the tap
            onNodeWithText(HIDE_DISTANCE).performScrollTo().performClick()
            waitUntil { fake.setCalls.isNotEmpty() }
            assertEquals(listOf(true), fake.setCalls)
        }
    }

    @Test
    fun hideDistance_freeToggle_showsUpsell_andWritesNothing() {
        val fake = FakeHideDistanceRepository(initial = HideDistanceState(hideDistance = false, premium = false))
        installKoin(fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(HIDE_DISTANCE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(HIDE_DISTANCE_UPSELL).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(HIDE_DISTANCE_UPSELL).assertExists()
            assertEquals(emptyList(), fake.setCalls) // a Free caller issues NO write
        }
    }

    @Test
    fun hideDistance_failedWrite_surfacesErrorAndAttemptedOnce() {
        val fake =
            FakeHideDistanceRepository(
                initial = HideDistanceState(hideDistance = false, premium = true),
                writeSucceeds = false,
            )
        installKoin(fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(HIDE_DISTANCE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(HIDE_DISTANCE_ERROR).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(HIDE_DISTANCE_ERROR).assertExists() // the toggle reverts; the error is the observable signal
            assertEquals(listOf(true), fake.setCalls)
        }
    }
}
