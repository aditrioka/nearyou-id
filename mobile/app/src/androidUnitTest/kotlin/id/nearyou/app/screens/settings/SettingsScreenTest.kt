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
import id.nearyou.app.data.accountdeletion.AccountDeletionFlow
import id.nearyou.app.data.accountdeletion.FakeAccountDeletionFlow
import id.nearyou.app.data.dataexport.DataExportFlow
import id.nearyou.app.data.dataexport.DataExportRequestOutcome
import id.nearyou.app.data.dataexport.DataExportStatusOutcome
import id.nearyou.app.data.dataexport.FakeDataExportFlow
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.hidedistance.HideDistanceState
import id.nearyou.app.privateprofile.PrivateProfileRepository
import id.nearyou.app.privateprofile.PrivateProfileState
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.CompletableDeferred
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
private const val PRIVATE_PROFILE_UPSELL = "Fitur Premium. Aktifkan Premium untuk profil privat."
private const val PRIVATE_PROFILE_ERROR = "Gagal memperbarui pengaturan. Coba lagi."
private const val HIDE_DISTANCE = "Sembunyikan jarak"
private const val HIDE_DISTANCE_UPSELL = "Fitur Premium. Aktifkan Premium untuk menyembunyikan jarak."
private const val HIDE_DISTANCE_ERROR = "Gagal memperbarui pengaturan. Coba lagi."
private const val MANAGE_SUBSCRIPTION = "Kelola langganan"
private const val GANTI_USERNAME = "Ganti username"
private const val COMING_SOON = "Segera hadir"
private const val INVITE_FRIENDS = "Undang teman"
private const val PAYWALL_CTA = "Aktifkan Premium" // cta_activate_premium — must NOT appear (no paywall divert)

/**
 * A configurable [HideDistanceRepository] fake: seeds the toggle state, records write calls, and
 * optionally [writeGate]s the write so a single-flight double-tap can be exercised.
 */
private class FakeHideDistanceRepository(
    private val initial: HideDistanceState? = null,
    private val writeSucceeds: Boolean = true,
    private val writeGate: CompletableDeferred<Unit>? = null,
) : HideDistanceRepository {
    val setCalls = mutableListOf<Boolean>()

    override suspend fun loadState(): HideDistanceState? = initial

    override suspend fun setHideDistance(value: Boolean): Boolean {
        setCalls += value
        writeGate?.await()
        return writeSucceeds
    }
}

/**
 * A configurable [PrivateProfileRepository] fake: seeds the toggle state, records write calls, and
 * optionally [writeGate]s the write so a single-flight double-tap can be exercised (the first write
 * stays in-flight while the second tap is issued).
 */
private class FakePrivateProfileRepository(
    private val initial: PrivateProfileState? = null,
    private val writeSucceeds: Boolean = true,
    private val writeGate: CompletableDeferred<Unit>? = null,
) : PrivateProfileRepository {
    val setCalls = mutableListOf<Boolean>()

    override suspend fun loadState(): PrivateProfileState? = initial

    override suspend fun setPrivateProfile(value: Boolean): Boolean {
        setCalls += value
        writeGate?.await()
        return writeSucceeds
    }
}

private const val LOGOUT_ROW = "Keluar"
private const val LOGOUT_DIALOG_TITLE = "Keluar dari akun?"
private const val LOGOUT_CONFIRM = "Ya, keluar"
private const val LOGOUT_CANCEL = "Batal"

// mobile-data-export-entry — canonical Bahasa copy (byte-identical to shared/resources strings.xml).
private const val DATA_EXPORT = "Unduh Data Saya"
private const val DATA_EXPORT_DIALOG_TITLE = "Unduh data kamu?"
private const val DATA_EXPORT_CONFIRM = "Ya, minta export"
private const val DATA_EXPORT_CANCEL = "Batal"
private const val DATA_EXPORT_IN_PROGRESS = "Sedang diproses"
private const val DATA_EXPORT_READY_OPEN = "Buka unduhan"
private const val DATA_EXPORT_EXPIRED = "Tautan kedaluwarsa. Minta ulang."

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
    private lateinit var dataExportFlow: FakeDataExportFlow

    private fun installKoin(
        hideDistance: HideDistanceRepository = FakeHideDistanceRepository(),
        privateProfile: PrivateProfileRepository = FakePrivateProfileRepository(),
        dataExport: FakeDataExportFlow = FakeDataExportFlow(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        tokenStore = InMemoryTokenStore()
        dataExportFlow = dataExport
        startKoin {
            modules(
                module {
                    single<TokenStore> { tokenStore }
                    // account-deletion-tombstone: SettingsScreen now resolves the deletion flow.
                    // Default fake → NotPending status (no banner), so the existing assertions hold.
                    single<AccountDeletionFlow> { FakeAccountDeletionFlow() }
                    // mobile-data-export-entry: SettingsScreen now resolves the data-export flow.
                    // Default fake → None status (no banner, requestable), so the existing assertions hold.
                    single<DataExportFlow> { dataExportFlow }
                    single<HideDistanceRepository> { hideDistance }
                    single<PrivateProfileRepository> { privateProfile }
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
    fun undangTemanRow_pushesReferralRoute_forAFreeUser_withNoPaywallDivert() {
        // mobile-referral 4.3 / design D3 — the "Undang teman" row is open to ALL tiers. The default fake
        // (premium=false) represents a Free user; tapping the row fires onOpenReferral (a ReferralRoute
        // push), shows NO "Segera hadir" (it is a backed navigation, not a deferred stub), and surfaces
        // NO paywall CTA (the Settings-layer no-paywall-divert path).
        installKoin()
        var referral = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(
                            onBack = {},
                            onOpenBlocked = {},
                            onOpenConsent = {},
                            onOpenReferral = { referral++ },
                            onLoggedOut = {},
                        )
                    }
                }
            }
            onNodeWithText(INVITE_FRIENDS).performScrollTo().performClick()
            assertEquals(1, referral, "the Undang teman row pushes ReferralRoute")
            onNodeWithText(COMING_SOON).assertDoesNotExist()
            onNodeWithText(PAYWALL_CTA).assertDoesNotExist()
        }
    }

    @Test
    fun gantiUsernameRow_pushesUsernameRoute_unconditionally() {
        installKoin()
        var opened = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(
                            onBack = {},
                            onOpenUsernameCustomization = { opened++ },
                            onOpenBlocked = {},
                            onOpenConsent = {},
                            onLoggedOut = {},
                        )
                    }
                }
            }
            // The "Ganti username" row is BACKED (mobile-premium-username): it pushes the route
            // unconditionally and shows no "Segera hadir" (the screen owns the Free/Premium gate).
            onNodeWithText(GANTI_USERNAME).performScrollTo().performClick()
            assertEquals(1, opened, "the Ganti username row pushes UsernameCustomizationRoute unconditionally")
            onNodeWithText(COMING_SOON).assertDoesNotExist()
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
            // "Profil privat" is now BACKED (private-profile capability) — the deferred-row contract is
            // asserted on a remaining deferred row, "Kelola langganan".
            onNodeWithText(MANAGE_SUBSCRIPTION).performScrollTo().performClick()
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
            // account-deletion-tombstone + mobile-data-export-entry: "Hapus akun" AND "Unduh Data Saya"
            // are now IN scope (each renders a real row); suspension stays out of scope (no dead control).
            onAllNodesWithText("Hapus akun", substring = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
            onAllNodesWithText("Unduh Data", substring = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
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

    @Test
    fun hideDistance_rapidDoubleTap_issuesAtMostOneInFlightWrite() {
        // Mirrors the private-profile double-tap test: the synchronous single-flight guard (set
        // before launch) was applied to BOTH privacy toggles — this covers the hide-distance sibling.
        val gate = CompletableDeferred<Unit>()
        val fake =
            FakeHideDistanceRepository(
                initial = HideDistanceState(hideDistance = false, premium = true),
                writeGate = gate,
            )
        installKoin(fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(HIDE_DISTANCE).performScrollTo().performClick()
            onNodeWithText(HIDE_DISTANCE).performClick() // rapid second tap while the first write is in-flight
            gate.complete(Unit)
            waitUntil { fake.setCalls.isNotEmpty() }
            assertEquals(listOf(true), fake.setCalls, "a rapid double-tap issues AT MOST one in-flight PATCH")
        }
    }

    @Test
    fun privateProfile_premiumToggle_issuesSingleWrite() {
        val fake = FakePrivateProfileRepository(initial = PrivateProfileState(privateProfile = false, premium = true))
        installKoin(privateProfile = fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the init GET (loadState) settle so premium=true before the tap
            onNodeWithText(PRIVATE_PROFILE).performScrollTo().performClick()
            waitUntil { fake.setCalls.isNotEmpty() }
            assertEquals(listOf(true), fake.setCalls)
        }
    }

    @Test
    fun privateProfile_freeToggle_showsUpsell_andWritesNothing() {
        val fake = FakePrivateProfileRepository(initial = PrivateProfileState(privateProfile = false, premium = false))
        installKoin(privateProfile = fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(PRIVATE_PROFILE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(PRIVATE_PROFILE_UPSELL).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(PRIVATE_PROFILE_UPSELL).assertExists()
            assertEquals(emptyList(), fake.setCalls) // a Free caller issues NO write
        }
    }

    @Test
    fun privateProfile_failedWrite_surfacesErrorAndAttemptedOnce() {
        val fake =
            FakePrivateProfileRepository(
                initial = PrivateProfileState(privateProfile = false, premium = true),
                writeSucceeds = false,
            )
        installKoin(privateProfile = fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(PRIVATE_PROFILE).performScrollTo().performClick()
            waitUntil { onAllNodesWithText(PRIVATE_PROFILE_ERROR).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(PRIVATE_PROFILE_ERROR).assertExists() // the toggle reverts; the error is the observable signal
            assertEquals(listOf(true), fake.setCalls)
        }
    }

    @Test
    fun privateProfile_rapidDoubleTap_issuesAtMostOneInFlightWrite() {
        // The write gate holds the first PATCH in-flight; the second tap must be swallowed by the
        // synchronous single-flight guard (mirrors the consent / hide-distance double-tap defense).
        val gate = CompletableDeferred<Unit>()
        val fake =
            FakePrivateProfileRepository(
                initial = PrivateProfileState(privateProfile = false, premium = true),
                writeGate = gate,
            )
        installKoin(privateProfile = fake)
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            onNodeWithText(PRIVATE_PROFILE).performScrollTo().performClick()
            onNodeWithText(PRIVATE_PROFILE).performClick() // rapid second tap while the first write is in-flight
            gate.complete(Unit)
            waitUntil { fake.setCalls.isNotEmpty() }
            assertEquals(listOf(true), fake.setCalls, "a rapid double-tap issues AT MOST one in-flight PATCH")
        }
    }

    @Test
    fun dataExport_rowOpensDialog_confirmRecordsPost() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.None))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the seed GET (fetchStatus → None) settle so the row is requestable
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertExists()
            onNodeWithText(DATA_EXPORT_CONFIRM).performClick()
            waitUntil { dataExportFlow.requestCount == 1 }
            assertEquals(1, dataExportFlow.requestCount, "confirming the dialog issues exactly one POST")
        }
    }

    @Test
    fun dataExport_none_offersRequestAffordanceAndNoBanner() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.None))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle()
            // The request affordance (the row) is offered AND no ready banner is shown for `none`.
            onNodeWithText(DATA_EXPORT).performScrollTo().assertExists()
            onNodeWithText(DATA_EXPORT_READY_OPEN).assertDoesNotExist()
        }
    }

    @Test
    fun dataExport_cancel_recordsNoPost() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.None))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the seed GET (fetchStatus → None) settle so the row is requestable
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertExists()
            onNodeWithText(DATA_EXPORT_CANCEL).performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertDoesNotExist()
            assertEquals(0, dataExportFlow.requestCount, "cancelling the dialog issues no POST")
        }
    }

    @Test
    fun dataExport_expired_allowsFreshRequest() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.Expired))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the seed GET (fetchStatus → Expired) settle
            // The expired note is shown AND the export is no longer single-active-locked: the row re-requests.
            onNodeWithText(DATA_EXPORT_EXPIRED).performScrollTo().assertExists()
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertExists()
        }
    }

    @Test
    fun dataExport_inProgress_showsSedangDiproses_andIsNotReIssuable() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.InProgress))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the init GET (fetchStatus → InProgress) settle
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            // The single-active row does NOT open the dialog and issues NO second POST.
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertDoesNotExist()
            onNodeWithText(DATA_EXPORT_IN_PROGRESS).assertExists()
            assertEquals(0, dataExportFlow.requestCount)
        }
    }

    @Test
    fun dataExport_ready_showsBannerWithDeadlineAndDownloadAffordance() {
        installKoin(
            dataExport =
                FakeDataExportFlow(
                    statusOutcome = DataExportStatusOutcome.Ready("2026-07-01T00:00:00Z", "https://r2.example/signed"),
                ),
        )
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitUntil { onAllNodesWithText("2026-07-01", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // The banner references the download deadline (date portion of downloadExpiresAt) ...
            onNodeWithText("2026-07-01", substring = true).assertExists()
            // ... and offers an in-app affordance to open the signed download.
            onNodeWithText(DATA_EXPORT_READY_OPEN).assertExists()
        }
    }

    @Test
    fun dataExport_failed_reRequestIssuesNewPost() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.Failed))
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = {}) } }
            }
            waitForIdle() // let the init GET (fetchStatus → Failed) settle
            // A failed export is not single-active-locked: the row re-opens the dialog and re-requests.
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_DIALOG_TITLE).assertExists()
            onNodeWithText(DATA_EXPORT_CONFIRM).performClick()
            waitUntil { dataExportFlow.requestCount == 1 }
            assertEquals(1, dataExportFlow.requestCount)
        }
    }

    @Test
    fun dataExport_statusRead401_routesToSignIn() {
        installKoin(dataExport = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.Terminal401))
        var loggedOut = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = { loggedOut++ })
                    }
                }
            }
            // A 401 on the seed GET routes to sign-in; no status banner is rendered.
            waitUntil { loggedOut == 1 }
            assertEquals(1, loggedOut)
            onNodeWithText(DATA_EXPORT_READY_OPEN).assertDoesNotExist()
        }
    }

    @Test
    fun dataExport_requestPost401_routesToSignIn() {
        installKoin(
            dataExport =
                FakeDataExportFlow(
                    statusOutcome = DataExportStatusOutcome.None,
                    requestOutcome = DataExportRequestOutcome.Terminal401,
                ),
        )
        var loggedOut = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        SettingsScreen(onBack = {}, onOpenBlocked = {}, onOpenConsent = {}, onLoggedOut = { loggedOut++ })
                    }
                }
            }
            waitForIdle() // let the seed GET (fetchStatus → None) settle so the row is requestable
            onNodeWithText(DATA_EXPORT).performScrollTo().performClick()
            onNodeWithText(DATA_EXPORT_CONFIRM).performClick()
            waitUntil { loggedOut == 1 }
            assertEquals(1, loggedOut)
        }
    }
}
