package id.nearyou.app.screens.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.profile.PROFILE_FOLLOWERS_TAG
import id.nearyou.app.screens.timeline.NEARBY_TIMELINE_LIST_TAG
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeGlobalPost
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
private const val SECTION_HOME = "Beranda"
private const val SECTION_NOTIFICATIONS = "Notifikasi"
private const val SECTION_PROFILE = "Profil"
private const val NOTIF_COPY = "Seseorang menyukai postingan kamu"
private const val BADGE_CD = "Notifikasi belum dibaca"

/** Fake self-id provider so the Profil section's live self profile resolves a target (mobile-profile). */
private class FakeSelfUserId(private val id: String? = "self-1") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

/**
 * iOS counterpart to the Robolectric `AppShellScreenTest` — the bottom-nav section shell run natively on
 * the iOS simulator (task 10.4). Covers the three sections + default Home, section switching swapping the
 * body, the Profil placeholder, and the Notifikasi badge, reusing the commonTest fakes. "Which feed is on
 * screen" is asserted via the feed list test tag (the redundant headers are removed). See
 * `id.nearyou.app.screens.auth.SignInFlowIosTest` for the v1-API + iosTest-placement rationale; K/N-legal
 * fn names (no `,()#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class AppShellFlowIosTest {
    private fun installKoin(unreadCount: Long? = 0L) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> {
                        FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "NEARBY_POST")), null, null))
                    }
                    single<GlobalTimelineFlow> {
                        FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "GLOBAL_POST")), null, null))
                    }
                    single<NotificationsFlow> {
                        FakeNotificationsFlow(
                            outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(type = "post_liked")), null),
                            unreadCountValue = unreadCount,
                        )
                    }
                    single<ProfileFlow> {
                        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isSelf = true)))
                    }
                    single<SelfUserIdProvider> { FakeSelfUserId() }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun shell_rendersThreeSections_andDefaultsToHome() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_HOME).assertExists()
            onNodeWithText(SECTION_PROFILE).assertExists()
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()
        }
    }

    @Test
    fun selectingNotifikasi_rendersNotificationsScreen() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun selectingProfil_showsSelfProfile() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_PROFILE).performClick()
            // The live self profile renders (mobile-profile) — its follower count node appears.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun badge_shownWhenUnreadCountPositive() {
        installKoin(unreadCount = 4)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithContentDescription(BADGE_CD, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, onAllNodesWithContentDescription(BADGE_CD, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }
}
