package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.UpsellDto
import id.nearyou.app.timeline.fakeNearbyPost
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "Post dari lokasi ini"
private const val LOADING = "Sedang memuat postingan…"
private const val EMPTY = "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?"
private const val LIMIT_HARD = "Kamu sudah mencapai batas baca untuk jam ini. Coba lagi sebentar lagi ya."
private const val LIMIT_SOFT = "Kamu lagi aktif-aktifnya! Premium membuka akses baca tanpa batas."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val LOC_DENIED = "Aktifkan lokasi untuk lihat postingan sekitar"
private const val LOC_OPEN_SETTINGS = "Buka Pengaturan"
private const val LOC_ALLOW = "Izinkan lokasi"

/**
 * iOS counterpart to the Robolectric [NearbyTimelineScreenTest] — the Nearby timeline flow run
 * natively on the iOS simulator. Covers the location-permission gate (granted / denied / rationale)
 * plus the six fetch states (loading / content / empty / hard-limit / soft-limit / error+retry),
 * reusing the commonTest fakes. The pull-to-refresh swipe gesture is left to the Android suite to
 * avoid gesture-timing flakiness here. See [id.nearyou.app.screens.auth.SignInFlowIosTest] for the
 * v1-API + iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class NearbyTimelineFlowIosTest {
    private lateinit var fake: FakeNearbyTimelineFlow

    private fun installKoin(
        outcome: NearbyTimelineOutcome = NearbyTimelineOutcome.Loaded(emptyList(), null, null),
        suspendForever: Boolean = false,
        locationStatus: LocationPermissionStatus = LocationPermissionStatus.GRANTED,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeNearbyTimelineFlow(outcome = outcome, suspendForever = suspendForever)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { fake }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = locationStatus)
                    }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    // Granted + content: Nearby title + the loaded post; load fires once on entry.
    @Test
    fun granted_content_showsTitleAndPost() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "HALO_SEKITAR")), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("HALO_SEKITAR").assertExists()
            assertEquals(1, fake.loadInvocationCount, "load fires exactly once on entry")
        }
    }

    // Granted + a fetch that never returns keeps the screen in-flight → loading copy shows.
    @Test
    fun granted_loading_showsLoadingCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    // Granted + empty (no upsell) shows the sparse-area copy, NOT the hard-limit copy.
    @Test
    fun granted_empty_showsSparseCopyNotHardLimit() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(EMPTY).assertExists()
            onNodeWithText(LIMIT_HARD).assertDoesNotExist()
        }
    }

    // Granted + hard cap (empty + upsell.hard) shows the limit copy, NOT the sparse copy.
    @Test
    fun granted_hardLimit_showsLimitCopyNotEmpty() {
        installKoin(NearbyTimelineOutcome.Loaded(emptyList(), null, UpsellDto(hard = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(LIMIT_HARD).assertExists()
            onNodeWithText(EMPTY).assertDoesNotExist()
        }
    }

    // Granted + soft cap (non-empty + upsell.soft) shows the posts AND the non-blocking banner.
    @Test
    fun granted_softLimit_showsPostsAndBanner() {
        installKoin(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "SOFT_POST")), null, UpsellDto(soft = true)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText("SOFT_POST").assertExists()
            onNodeWithText(LIMIT_SOFT).assertExists()
        }
    }

    // Granted + error shows the network copy + a retry that re-invokes the fetch.
    @Test
    fun granted_error_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(NearbyTimelineOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    // Denied permission → the denial fallback (copy + "Buka Pengaturan"), no fetch.
    @Test
    fun denied_showsDenialFallbackWithOpenSettings() {
        installKoin(locationStatus = LocationPermissionStatus.DENIED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(LOC_DENIED).assertExists()
            onNodeWithText(LOC_OPEN_SETTINGS).assertExists()
        }
    }

    // Not-determined → the UU-PDP consent rationale modal (its "Izinkan lokasi" CTA renders).
    @Test
    fun notDetermined_showsConsentRationale() {
        installKoin(locationStatus = LocationPermissionStatus.NOT_DETERMINED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen().Content() } } }
            onNodeWithText(LOC_ALLOW).assertExists()
        }
    }
}
