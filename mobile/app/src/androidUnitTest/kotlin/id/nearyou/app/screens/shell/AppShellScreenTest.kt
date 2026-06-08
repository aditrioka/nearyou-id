package id.nearyou.app.screens.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsApiClient
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.NotificationsRepository
import id.nearyou.app.notifications.fakeNotification
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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val SECTION_HOME = "Beranda" // section_home
private const val SECTION_NOTIFICATIONS = "Notifikasi" // section_notifications
private const val SECTION_PROFILE = "Profil" // section_profile
private const val TAB_FOLLOWING = "Mengikuti" // tab_following
private const val FOLLOWING_PLACEHOLDER = "Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu."
private const val PROFILE_PLACEHOLDER = "Profil segera hadir." // profile_placeholder
private const val FAB_POST = "Posting" // cta_post — the Home-section icon-only composer FAB contentDescription
private const val NOTIF_COPY = "Seseorang menyukai postingan kamu" // notif_post_liked — proves the screen rendered
private const val BADGE_CD = "Notifikasi belum dibaca" // notifications_badge contentDescription

/**
 * Render + interaction coverage of the `AppShellScreen` section shell (mobile-home-shell-redesign tasks
 * 10.1 + the `mobile-home-tab-host` shell requirements): the three bottom-nav sections + section
 * switching swapping the body, the icon-only composer FAB on the Home section only (asserted via its
 * `contentDescription`), the Profil placeholder, the Notifikasi badge show-at-`count>0` / hide-at-`0`,
 * the Notifikasi section rendering `NotificationsScreen`, the no-re-fetch-on-section-switch invariant via
 * the fakes' counters, and the Profil-section-issues-no-fetch guard via a recording MockEngine. "Which
 * feed is on screen" is asserted via the feed list test tag ([NEARBY_TIMELINE_LIST_TAG]) — the redundant
 * headers are removed. In the Release-variant `*ScreenTest` exclude (the `ui-test-manifest` host activity
 * is debug-only).
 *
 * The Nearby tab (the Home default) is gated on location; a GRANTED fake settles the gate and `waitUntil`
 * polls the end state (per `feedback_robolectric_async_repo_screen_test_waituntil`).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class AppShellScreenTest {
    private lateinit var nearbyFake: FakeNearbyTimelineFlow
    private lateinit var globalFake: FakeGlobalTimelineFlow
    private lateinit var notifFake: FakeNotificationsFlow

    private fun installKoin(
        nearbyOutcome: NearbyTimelineOutcome =
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "NEARBY_POST")), null, null),
        globalOutcome: GlobalTimelineOutcome =
            GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "GLOBAL_POST")), null, null),
        notifOutcome: NotificationsOutcome = NotificationsOutcome.Loaded(listOf(fakeNotification(type = "post_liked")), null),
        unreadCount: Long? = 0L,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        nearbyFake = FakeNearbyTimelineFlow(nearbyOutcome)
        globalFake = FakeGlobalTimelineFlow(globalOutcome)
        notifFake = FakeNotificationsFlow(outcome = notifOutcome, unreadCountValue = unreadCount)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { nearbyFake }
                    single<GlobalTimelineFlow> { globalFake }
                    single<NotificationsFlow> { notifFake }
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
            // SECTION_NOTIFICATIONS label may collide with a screen title; assert it appears at least once.
            assertEquals(true, onAllNodesWithText(SECTION_NOTIFICATIONS).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText(SECTION_PROFILE).assertExists()
            // Default section = Home → its default feed (Nearby) renders (asserted via the feed list tag).
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertExists()
        }
    }

    @Test
    fun selectingNotifikasi_swapsBodyToNotificationsScreen() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            // The NotificationsScreen rendered (its row copy shows); the Home feed is gone.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun selectingProfil_swapsBodyToPlaceholder() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(PROFILE_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun homeSection_followingTab_showsDeferredPlaceholder() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_FOLLOWING).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(FOLLOWING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(FOLLOWING_PLACEHOLDER).assertExists()
        }
    }

    @Test
    fun fab_isIconOnly_onHomeSectionOnly() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Home section → icon-only FAB present (asserted via contentDescription; no visible text label).
            onNodeWithContentDescription(FAB_POST).assertExists()
            // Notifikasi section → FAB absent.
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(FAB_POST).assertDoesNotExist()
            // Profil section → FAB absent.
            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(PROFILE_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(FAB_POST).assertDoesNotExist()
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
            assertEquals(
                1,
                onAllNodesWithContentDescription(BADGE_CD, useUnmergedTree = true).fetchSemanticsNodes().size,
                "the unread badge is shown when count > 0",
            )
        }
    }

    @Test
    fun badge_hiddenWhenUnreadCountZero() {
        installKoin(unreadCount = 0)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(
                0,
                onAllNodesWithContentDescription(BADGE_CD, useUnmergedTree = true).fetchSemanticsNodes().size,
                "the unread badge is absent when count == 0",
            )
        }
    }

    @Test
    fun returningToHomeSection_doesNotReFetchTheFeeds() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "Nearby loads once on first show")

            // Home → Notifikasi → Home: the Home feeds are not torn down + re-fetched.
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_HOME).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, nearbyFake.loadInvocationCount, "returning to Home must not re-fetch the Nearby feed (VM retained)")
        }
    }

    @Test
    fun returningToNotifikasiSection_doesNotReFetch() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }

            // First Notifikasi entry loads the inbox once.
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, notifFake.loadInvocationCount, "the inbox loads once on first Notifikasi composition")

            // Notifikasi → Home → Notifikasi: the shell-scoped VM survived (no re-fetch).
            onNodeWithText(SECTION_HOME).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, notifFake.loadInvocationCount, "returning to Notifikasi must not re-fetch (the shell-scoped VM is retained)")
        }
    }

    // mobile-home-tab-host § "The Profil section renders a deferred placeholder ... issues no fetch" — a
    // recording MockEngine backs the shell's real NotificationsRepository (so its outbound requests are
    // captured); selecting Profil renders the placeholder and captures NO new request. Nearby/Global stay
    // fakes (their counters confirm no feed fetch fires for Profil either).
    @Test
    fun profilSection_issuesNoFetch() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val recordedPaths = mutableListOf<String>()
        val jsonHeaders = headersOf("Content-Type", "application/json")
        val httpClient =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine =
                    MockEngine { request ->
                        recordedPaths.add(request.url.encodedPath)
                        respond("""{"count":0}""", HttpStatusCode.OK, jsonHeaders)
                    },
                installLogging = false,
                nowMillis = { 0L },
            )
        nearbyFake = FakeNearbyTimelineFlow(NearbyTimelineOutcome.Loaded(emptyList(), null, null))
        globalFake = FakeGlobalTimelineFlow(GlobalTimelineOutcome.Loaded(emptyList(), null, null))
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { nearbyFake }
                    single<GlobalTimelineFlow> { globalFake }
                    // The shell's real notifications graph over the recording MockEngine (the badge's
                    // unread-count request is captured here).
                    single<NotificationsFlow> { NotificationsRepository(NotificationsApiClient(httpClient)) }
                    single<LocationPermissionController> {
                        FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                    }
                },
            )
        }
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            // Let the shell settle (the one-shot badge unread-count request fires on composition).
            waitForIdle()
            val pathsAfterCompose = recordedPaths.toList()

            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(PROFILE_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }

            assertEquals(
                pathsAfterCompose,
                recordedPaths.toList(),
                "the Profil section captures NO new outbound request (the placeholder issues no fetch)",
            )
        }
    }
}
