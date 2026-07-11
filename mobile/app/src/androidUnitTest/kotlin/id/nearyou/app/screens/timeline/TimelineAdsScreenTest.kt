package id.nearyou.app.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.ads.AdFeedController
import id.nearyou.app.ads.fakeAdFeedController
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeNearbyPost
import id.nearyou.app.ui.components.NATIVE_AD_CARD_TAG
import id.nearyou.app.ui.components.NATIVE_AD_LABEL_TAG
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// FakeSelfUserId comes from the commonTest helpers (NearbyTimelineViewModelTest) — same package.

/**
 * Robolectric render coverage of the timeline native-ad placement (task 5.3): with ads enabled + a
 * ≥`frequency` feed, the "Bersponsor" slot renders, bound to the injected `AdProvider`'s `NativeAdContent`
 * (the positive anchor for "the app consumes ads only via the interface"); with ads disabled, no slot
 * appears; and the ad seam is referenced ONLY by the three timeline screens (the negative guard for "no ad
 * on profile / conversation-list / chat-thread / post-detail").
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class TimelineAdsScreenTest {
    private fun installKoin(adController: AdFeedController) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val posts = (1..6).map { fakeNearbyPost(id = "p$it", content = "POST_$it") }
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> {
                        FakeNearbyTimelineFlow(outcome = NearbyTimelineOutcome.Loaded(posts, null, null))
                    }
                    single<LikeFlow> { FakeLikeFlow() }
                    // timeline-card-report-kebab: the report seam (self id already registered above/below).
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                    single<ProfileFlow> {
                        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isPremium = false)))
                    }
                    single<SelfUserIdProvider> { FakeSelfUserId("self") }
                    single { adController }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun adsEnabled_rendersBersponsorSlot_boundToProviderContent() {
        // frequency 2 so the first slot (after 2 posts) lands within the viewport — a trailing slot after
        // 6 posts would be below the fold and never composed by LazyColumn.
        installKoin(fakeAdFeedController(enabled = true, frequency = 2))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(NATIVE_AD_LABEL_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(NATIVE_AD_CARD_TAG).assertExists()
            onNodeWithTag(NATIVE_AD_LABEL_TAG).assertExists() // the localized "Bersponsor" label
            // Bound to the injected provider's NativeAdContent (not just a labelled slot).
            onNodeWithText("Iklan Uji").assertExists()
        }
    }

    @Test
    fun adsDisabled_rendersNoAdSlot() {
        installKoin(fakeAdFeedController(enabled = false))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NearbyTimelineScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("POST_1").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(NATIVE_AD_CARD_TAG).assertDoesNotExist()
            onNodeWithTag(NATIVE_AD_LABEL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun adSeamIsReferencedOnlyByTheThreeTimelineScreens() {
        // Structural negative guard (task 5.3): no ad node can render on profile / conversation-list /
        // chat-thread / post-detail because only the three timeline screens wire the ad seam.
        var root = File(System.getProperty("user.dir")).absoluteFile
        while (!File(root, "settings.gradle.kts").exists() && root.parentFile != null) {
            root = root.parentFile
        }
        val screens = File(root, "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens")
        assertTrue(screens.exists(), "screens dir resolved at ${screens.path}")
        val referencing =
            screens.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("rememberTimelineAds") }
                .map { it.name }
                .toSortedSet()
        assertEquals(
            sortedSetOf("FollowingTimelineScreen.kt", "GlobalTimelineScreen.kt", "NearbyTimelineScreen.kt"),
            referencing,
        )
    }
}
