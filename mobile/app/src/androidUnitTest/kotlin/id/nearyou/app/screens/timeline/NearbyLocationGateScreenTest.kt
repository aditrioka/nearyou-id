package id.nearyou.app.screens.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.location.LocationUnavailableException
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.location_consent_allow
import id.nearyou.resources.generated.resources.location_consent_body
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
// The redundant Nearby header ("Post dari lokasi ini") is removed — "the feed is on screen" is asserted
// via the feed list test tag (NEARBY_TIMELINE_LIST_TAG, declared in NearbyTimelineScreen.kt, same package).
private const val DENIAL = "Aktifkan lokasi untuk lihat postingan sekitar"
private const val OPEN_SETTINGS = "Buka Pengaturan"
private const val CONSENT_TITLE = "Aktifkan lokasi kamu"
private const val CONSENT_BODY =
    "NearYouID memakai lokasi perkiraan kamu untuk menampilkan postingan di sekitar. " +
        "Lokasi hanya diambil saat kamu membuka halaman ini dan tidak pernah dibagikan ke pengguna lain."
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
                    single<LikeFlow> { FakeLikeFlow() }
                    // mobile-nearby-radius-slider: NearbyTimelineScreen now resolves the self-profile read.
                    single<ProfileFlow> { FakeProfileFlow() }
                    single<SelfUserIdProvider> { FakeSelfUserId() }
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
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitForIdle()
            onNodeWithText(DENIAL).assertExists()
            onNodeWithText(OPEN_SETTINGS).assertExists()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist() // the feed surface is not shown when denied
            assertEquals(0, fakeFlow.loadInvocationCount, "denied permission must not fetch")
        }
    }

    // 8.3 — granted permission drives the existing fetch path (no denial copy).
    @Test
    fun granted_runsTheFetchPath_andShowsNoDenialCopy() {
        installKoin(LocationPermissionStatus.GRANTED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitForIdle()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()
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
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
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
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitForIdle()
            assertEquals(0, fakeController.openAppSettingsCount)
            onNodeWithText(OPEN_SETTINGS).performClick()
            waitForIdle()
            assertEquals(1, fakeController.openAppSettingsCount, "Buka Pengaturan deep-links to settings")
        }
    }

    // Regression (manual-verify finding): after the user enables location in the OS Settings and
    // returns to the app, the gate re-checks on ON_RESUME and reaches the feed WITHOUT a cold restart
    // (the bug was LaunchedEffect(Unit) refreshing only on first composition). Drives a real
    // LifecycleRegistry so the screen's LifecycleEventEffect(ON_RESUME) fires on re-entry.
    @Test
    fun returnFromSettingsAfterGrant_refreshesToFeed_withoutColdRestart() {
        installKoin(LocationPermissionStatus.DENIED)
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)

                override val lifecycle: Lifecycle get() = registry
            }
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    KoinContext { NearYouTheme { NearbyTimelineScreen() } }
                }
            }
            owner.registry.currentState = Lifecycle.State.RESUMED
            waitForIdle()
            // Initially denied → no fetch.
            onNodeWithText(DENIAL).assertExists()
            assertEquals(0, fakeFlow.loadInvocationCount)

            // User enables location in Settings (status changes outside the app), then returns:
            // ON_PAUSE/ON_STOP then ON_START/ON_RESUME — NO cold restart.
            fakeController.simulateExternalStatusChange(LocationPermissionStatus.GRANTED)
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
            waitForIdle()

            // The ON_RESUME re-check reached the feed (the feed surface renders — asserted via the list tag).
            onNodeWithText(DENIAL).assertDoesNotExist()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()
            assertEquals(1, fakeFlow.loadInvocationCount, "resume re-check reaches the fetch path")
        }
    }

    // 8.3 — NOT_DETERMINED surfaces the UU-PDP consent rationale modal (and fetches nothing yet).
    @Test
    fun notDetermined_surfacesTheConsentModal_andDoesNotFetch() {
        installKoin(LocationPermissionStatus.NOT_DETERMINED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
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
        var consentBody = ""
        var consentAllow = ""
        runComposeUiTest {
            setContent {
                denial = stringResource(Res.string.nearby_location_denied)
                openSettings = stringResource(Res.string.location_open_settings)
                consentTitle = stringResource(Res.string.location_consent_title)
                consentBody = stringResource(Res.string.location_consent_body)
                consentAllow = stringResource(Res.string.location_consent_allow)
            }
            waitForIdle()
        }
        assertEquals(DENIAL, denial)
        assertEquals(OPEN_SETTINGS, openSettings)
        assertEquals(CONSENT_TITLE, consentTitle)
        assertEquals(CONSENT_BODY, consentBody)
        assertEquals(CONSENT_ALLOW, consentAllow)
    }
}
