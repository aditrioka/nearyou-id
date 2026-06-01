package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.location.LocationUnavailableException
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.location_consent_allow
import id.nearyou.resources.generated.resources.location_consent_title
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.nearby_location_denied
import org.jetbrains.compose.resources.stringResource
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml) — pins the
// location-gate copy alongside the render assertions.
private const val NEARBY_TITLE = "Post dari lokasi ini"
private const val DENIAL = "Aktifkan lokasi untuk lihat postingan sekitar"
private const val OPEN_SETTINGS = "Buka Pengaturan"
private const val CONSENT_TITLE = "Aktifkan lokasi kamu"
private const val CONSENT_ALLOW = "Izinkan lokasi"
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"

/**
 * Render coverage of the Nearby location gate (`mobile-location-permission-flow`, task 8.3) via the
 * Robolectric-backed CMP UI runner. The pure gate decision logic is covered by `LocationGateTest` /
 * `LocationGateUiStateTest`; this suite verifies the actual `NearbyTimelineScreen` composable renders
 * the denial fallback, gates the fetch, surfaces the consent modal, maps granted-but-no-fix to the
 * existing retryable error state, and that the "Buka Pengaturan" CTA invokes `openAppSettings()`.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class NearbyLocationGateScreenTest {
    private lateinit var fakeFlow: FakeNearbyTimelineFlow
    private lateinit var fakeController: FakeLocationPermissionController

    private fun installKoin(
        permission: LocationPermissionStatus,
        outcome: NearbyTimelineOutcome =
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "GATED_POST")), null, null),
        failWith: Throwable? = null,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fakeFlow = FakeNearbyTimelineFlow(outcome = outcome, failWith = failWith)
        fakeController = FakeLocationPermissionController(current = permission)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { fakeFlow }
                    single<LocationPermissionController> { fakeController }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // 8.3 — denied permission renders the denial fallback + "Buka Pengaturan" and issues NO fetch.
    @Test
    fun denied_showsDenialFallback_andIssuesNoFetch() {
        installKoin(LocationPermissionStatus.DENIED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            waitForIdle()
            onNodeWithText(DENIAL).assertExists()
            onNodeWithText(OPEN_SETTINGS).assertExists()
            onNodeWithText(NEARBY_TITLE).assertDoesNotExist()
            assertEquals(0, fakeFlow.loadInvocationCount, "denied permission must not fetch")
        }
    }

    // 8.3 — granted permission drives the existing fetch path (no denial copy).
    @Test
    fun granted_runsTheFetchPath_andShowsNoDenialCopy() {
        installKoin(LocationPermissionStatus.GRANTED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            waitForIdle()
            onNodeWithText(NEARBY_TITLE).assertExists()
            onNodeWithText("GATED_POST").assertExists()
            onNodeWithText(DENIAL).assertDoesNotExist()
            assertEquals(1, fakeFlow.loadInvocationCount, "granted permission runs the fetch exactly once")
        }
    }

    // 8.3 — granted but no coordinate obtainable (provider throws) maps to the EXISTING retryable
    // error state (network copy + retry), with no new NearbyTimelineOutcome member.
    @Test
    fun grantedButNoFix_showsExistingRetryableErrorState() {
        installKoin(LocationPermissionStatus.GRANTED, failWith = LocationUnavailableException())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            waitForIdle()
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
        }
    }

    // 8.3 — the "Buka Pengaturan" CTA invokes LocationPermissionController.openAppSettings().
    @Test
    fun bukaPengaturanCta_invokesOpenAppSettings() {
        installKoin(LocationPermissionStatus.DENIED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            waitForIdle()
            assertEquals(0, fakeController.openAppSettingsCount)
            onNodeWithText(OPEN_SETTINGS).performClick()
            waitForIdle()
            assertEquals(1, fakeController.openAppSettingsCount, "Buka Pengaturan deep-links to settings")
        }
    }

    // 8.3 — NOT_DETERMINED surfaces the UU-PDP consent rationale modal (and fetches nothing yet).
    @Test
    fun notDetermined_surfacesTheConsentModal_andDoesNotFetch() {
        installKoin(LocationPermissionStatus.NOT_DETERMINED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            waitForIdle()
            onNodeWithText(CONSENT_TITLE).assertExists()
            onNodeWithText(CONSENT_ALLOW).assertExists()
            assertEquals(0, fakeFlow.loadInvocationCount, "the rationale gate must not fetch")
        }
    }

    // The location strings carry their exact canonical Bahasa Indonesia copy (captured via the same
    // stringResource runtime path the render assertions rely on). The denial copy + settings CTA are
    // byte-identical to the change spec.
    @Test
    fun locationStrings_haveExactCanonicalText() {
        var denial = ""
        var openSettings = ""
        var consentTitle = ""
        var consentAllow = ""
        runComposeUiTest {
            setContent {
                denial = stringResource(Res.string.nearby_location_denied)
                openSettings = stringResource(Res.string.location_open_settings)
                consentTitle = stringResource(Res.string.location_consent_title)
                consentAllow = stringResource(Res.string.location_consent_allow)
            }
            waitForIdle()
        }
        assertEquals(DENIAL, denial)
        assertEquals(OPEN_SETTINGS, openSettings)
        assertEquals(CONSENT_TITLE, consentTitle)
        assertEquals(CONSENT_ALLOW, consentAllow)
    }
}
