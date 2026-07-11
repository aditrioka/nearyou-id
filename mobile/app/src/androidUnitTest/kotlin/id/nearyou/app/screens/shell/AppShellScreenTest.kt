package id.nearyou.app.screens.shell

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
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
import id.nearyou.app.post.SinglePostApiClient
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileApiClient
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.push.fakeFcmTokenRegistrar
import id.nearyou.app.screens.profile.PROFILE_FOLLOWERS_TAG
import id.nearyou.app.screens.profile.PROFILE_SETTINGS_TAG
import id.nearyou.app.screens.timeline.FOLLOWING_TIMELINE_LIST_TAG
import id.nearyou.app.screens.timeline.NEARBY_TIMELINE_LIST_TAG
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.FakeFollowingTimelineFlow
import id.nearyou.app.timeline.FakeGlobalTimelineFlow
import id.nearyou.app.timeline.FakeNearbyTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.timeline.fakeFollowingPost
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
import kotlin.math.pow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val SECTION_HOME = "Beranda" // section_home
private const val SECTION_NOTIFICATIONS = "Notifikasi" // section_notifications
private const val SECTION_PROFILE = "Profil" // section_profile
private const val TAB_FOLLOWING = "Mengikuti" // tab_following

/** Fake self-id provider so the Profil section's live self [id.nearyou.app.screens.profile.ProfileScreen]
 *  resolves a target id without a real token (mobile-profile). */
private class FakeSelfUserId(private val id: String? = "self-1") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

private const val FAB_POST = "Posting" // cta_post — the Home-section icon-only composer FAB contentDescription
private const val NOTIF_COPY = "Seseorang menyukai postingan kamu" // notif_post_liked — proves the screen rendered
private const val BADGE_CD = "Notifikasi belum dibaca" // notifications_badge contentDescription
private const val APP_NAME = "NearYouID" // app_name — the shell app-bar logo contentDescription
private const val TAB_SEKITAR = "Sekitar" // tab_nearby — the Home section's default feed tab label

/** WCAG 2.x AA contrast threshold for normal text (4.5:1) — the selected nav label must clear it. */
private const val MIN_LABEL_CONTRAST = 4.5

/** WCAG 2.x AA non-text/UI-component threshold (3:1) — the selected nav icon vs its pill must clear it. */
private const val MIN_ICON_CONTRAST = 3.0

/** WCAG relative-luminance contrast ratio between two sRGB [Color]s (1.0 = none … 21.0 = black/white). */
private fun wcagContrast(
    a: Color,
    b: Color,
): Double {
    fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Color): Double = 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

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
    private lateinit var followingFake: FakeFollowingTimelineFlow
    private lateinit var globalFake: FakeGlobalTimelineFlow
    private lateinit var notifFake: FakeNotificationsFlow

    private fun installKoin(
        nearbyOutcome: NearbyTimelineOutcome =
            NearbyTimelineOutcome.Loaded(listOf(fakeNearbyPost(content = "NEARBY_POST")), null, null),
        followingOutcome: FollowingTimelineOutcome =
            FollowingTimelineOutcome.Loaded(listOf(fakeFollowingPost(content = "FOLLOWING_POST")), null, null),
        globalOutcome: GlobalTimelineOutcome =
            GlobalTimelineOutcome.Loaded(listOf(fakeGlobalPost(content = "GLOBAL_POST")), null, null),
        notifOutcome: NotificationsOutcome = NotificationsOutcome.Loaded(listOf(fakeNotification(type = "post_liked")), null),
        unreadCount: Long? = 0L,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        nearbyFake = FakeNearbyTimelineFlow(nearbyOutcome)
        followingFake = FakeFollowingTimelineFlow(followingOutcome)
        globalFake = FakeGlobalTimelineFlow(globalOutcome)
        notifFake = FakeNotificationsFlow(outcome = notifOutcome, unreadCountValue = unreadCount)
        startKoin {
            modules(
                module {
                    single<NearbyTimelineFlow> { nearbyFake }
                    single<FollowingTimelineFlow> { followingFake }
                    single<GlobalTimelineFlow> { globalFake }
                    single<LikeFlow> { FakeLikeFlow() }
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<NotificationsFlow> { notifFake }
                    // mobile-profile: the Profil section renders the live self ProfileScreen, which
                    // injects ProfileFlow + SelfUserIdProvider. Faked here so the section renders the
                    // self profile (isSelf = true → no follow/block/report actions).
                    single<ProfileFlow> {
                        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isSelf = true)))
                    }
                    single<SelfUserIdProvider> { FakeSelfUserId() }
                    single { fakeFcmTokenRegistrar() }
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
    fun homeAppBar_rendersSearchAction_invokesOnOpenSearch_andIsHomeOnly() {
        installKoin()
        runComposeUiTest {
            var searches = 0
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}, onOpenSearch = { searches++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Home section (default): the brand app bar carries the search action; tapping fires onOpenSearch.
            onNodeWithTag(SHELL_SEARCH_ACTION_TAG).assertExists()
            onNodeWithTag(SHELL_SEARCH_ACTION_TAG).performClick()
            assertEquals(1, searches, "the Home app-bar search action invokes onOpenSearch")
            // Non-Home sections render no shell app bar → no search action.
            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SHELL_SEARCH_ACTION_TAG).assertDoesNotExist()
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
    fun selectingProfil_swapsBodyToSelfProfile() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_PROFILE).performClick()
            // The live self profile renders (mobile-profile) — its follower count node appears; the Home
            // feed is gone. (The old "Profil segera hadir." placeholder is removed.)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(NEARBY_TIMELINE_LIST_TAG).assertDoesNotExist()
        }
    }

    // mobile-settings (#288) — the self-profile section's settings gear is the Settings surface's entry
    // point: tapping it invokes the hoisted onOpenSettings (the appEntryProvider wires this to a
    // SettingsRoute push). Targets the gear by test tag (the "Profil" header title now collides with the
    // bottom-nav "Profil" label while the section is active, so text lookups would be ambiguous).
    @Test
    fun profilSection_settingsGear_invokesOnOpenSettings() {
        installKoin()
        runComposeUiTest {
            var settingsOpens = 0
            setContent {
                KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}, onOpenSettings = { settingsOpens++ }) } }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_SETTINGS_TAG).performClick()
            assertEquals(1, settingsOpens, "the Profil section's settings gear invokes onOpenSettings (→ SettingsRoute push)")
        }
    }

    // mobile-following-timeline-screen — the Home section's Following tab now renders the LIVE feed
    // (was a deferred placeholder) under the shell.
    @Test
    fun homeSection_followingTab_showsLiveFeed() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TAB_FOLLOWING).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(FOLLOWING_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(FOLLOWING_TIMELINE_LIST_TAG).assertExists()
            onNodeWithText("FOLLOWING_POST").assertExists()
            assertEquals(1, followingFake.loadInvocationCount, "the live Following feed fetches once on first show")
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
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
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

    // mobile-design-system § "Selected nav item label is readable (WCAG contrast)" — the LITERAL scenario,
    // asserted by CONTRAST, not mere inequality. `secondary` is now a readable accent, so the override is a
    // deliberate brand-identity choice (selected nav = primary cobalt family) rather than a readability
    // band-aid; the selected LABEL stays `onSurface`. This reads the ACTUAL rendered selected-label color
    // (LocalContentColor in the label slot) and asserts it clears WCAG AA contrast (4.5:1) against BOTH the
    // surface and the surfaceContainer (the nav's possible backgrounds). A near-white-on-white selected
    // label scores ~1.1:1 and would FAIL here — which an inequality-only check would wrongly pass. Pairs
    // with the ShellAndTimelineSourceGuardTest source guard.
    @Test
    fun selectedNavLabel_hasReadableContrastAgainstTheNavBackground() {
        var selectedLabelColor = Color.Unspecified
        var surface = Color.Unspecified
        var surfaceContainer = Color.Unspecified
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    surface = MaterialTheme.colorScheme.surface
                    surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
                    NavigationBar {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            colors = nearYouNavigationBarItemColors(),
                            icon = {},
                            label = { selectedLabelColor = LocalContentColor.current },
                        )
                    }
                }
            }
            waitForIdle()
            assertTrue(selectedLabelColor != Color.Unspecified, "the selected label resolves a concrete content color")
            val vsSurface = wcagContrast(selectedLabelColor, surface)
            val vsContainer = wcagContrast(selectedLabelColor, surfaceContainer)
            assertTrue(
                vsSurface >= MIN_LABEL_CONTRAST,
                "selected nav label must be readable on the surface (WCAG contrast $vsSurface < $MIN_LABEL_CONTRAST)",
            )
            assertTrue(
                vsContainer >= MIN_LABEL_CONTRAST,
                "selected nav label must be readable on the surfaceContainer (WCAG contrast $vsContainer < $MIN_LABEL_CONTRAST)",
            )
        }
    }

    // mobile-design-system § "Selected nav icon uses the brand cobalt primary" — the override's selected
    // icon is the brand `primary` (cobalt #1E4FD6), NOT `onPrimaryContainer`, and it must still clear the
    // M3 non-text 3:1 contrast against its indicator pill (`primaryContainer`). Reads the ACTUAL rendered
    // icon-slot content color (LocalContentColor) so a future token change can't silently regress it.
    @Test
    fun selectedNavIcon_isBrandPrimary_andContrastsWithThePill() {
        var selectedIconColor = Color.Unspecified
        var primary = Color.Unspecified
        var indicatorPill = Color.Unspecified
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    primary = MaterialTheme.colorScheme.primary
                    indicatorPill = MaterialTheme.colorScheme.primaryContainer
                    NavigationBar {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            colors = nearYouNavigationBarItemColors(),
                            icon = { selectedIconColor = LocalContentColor.current },
                            label = {},
                        )
                    }
                }
            }
            waitForIdle()
            assertEquals(primary, selectedIconColor, "the selected nav icon uses the brand cobalt primary")
            val vsPill = wcagContrast(selectedIconColor, indicatorPill)
            assertTrue(
                vsPill >= MIN_ICON_CONTRAST,
                "selected nav icon must contrast with the indicator pill (WCAG contrast $vsPill < $MIN_ICON_CONTRAST)",
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

    // mobile-profile: selecting Profil renders the live self profile via the ProfileFlow seam (faked
    // here) — its profile read does NOT touch the shared notifications HttpClient. A recording MockEngine
    // backs the shell's real NotificationsRepository; selecting Profil captures NO new request on it (the
    // section does not trigger a notifications fetch). Nearby/Global stay fakes (no feed fetch for Profil).
    @Test
    fun profilSection_issuesNoNotificationsFetch() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val recordedPaths = mutableListOf<String>()
        val jsonHeaders = headersOf("Content-Type", "application/json")
        val httpClient =
            HttpClientFactory.create(
                installTimeouts = false,
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
                    single<LikeFlow> { FakeLikeFlow() }
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    // The shell's real notifications graph over the recording MockEngine (the badge's
                    // unread-count request is captured here). The deep-link resolution clients reuse the
                    // recording client; they are not exercised by these shell tests.
                    single<NotificationsFlow> {
                        NotificationsRepository(
                            NotificationsApiClient(httpClient),
                            SinglePostApiClient(httpClient),
                            ProfileApiClient(httpClient),
                        )
                    }
                    // The Profil section's profile read goes through this fake ProfileFlow (NOT the
                    // recording HttpClient), so selecting Profil adds no path to recordedPaths.
                    single<ProfileFlow> {
                        FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(isSelf = true)))
                    }
                    single<SelfUserIdProvider> { FakeSelfUserId() }
                    single { fakeFcmTokenRegistrar() }
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
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }

            assertEquals(
                pathsAfterCompose,
                recordedPaths.toList(),
                "selecting Profil captures NO new request on the notifications HttpClient (its profile read uses the ProfileFlow seam)",
            )
        }
    }

    // ---- the Home-section centered brand-logo app bar (mobile-timeline-card-redesign, mockup 1/19) ----

    // mobile-home-tab-host § "Home section shows the centered brand-logo app bar" — the shell Scaffold's
    // topBar renders the logo with the app-name contentDescription (light scheme → light asset tag).
    @Test
    fun homeSection_showsTheCenteredBrandLogoAppBar() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(APP_NAME).assertExists()
            onNodeWithTag(SHELL_LOGO_LIGHT_TAG, useUnmergedTree = true).assertExists()
        }
    }

    // mobile-home-tab-host § "Logo asset follows the active scheme" — under the night configuration
    // (isSystemInDarkTheme() = true, the SignInScreen idiom NearYouTheme also defaults to) the dark
    // logo variant renders.
    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun homeSection_underTheDarkScheme_usesTheDarkLogoAsset() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SHELL_LOGO_DARK_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(SHELL_LOGO_LIGHT_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    // mobile-home-tab-host § "Non-Home sections render no shell top app bar" — Notifikasi/Profil keep
    // their in-body headers; the logo app bar leaves composition.
    @Test
    fun nonHomeSections_renderNoShellTopAppBar() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SECTION_NOTIFICATIONS).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NOTIF_COPY, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(APP_NAME).assertDoesNotExist()
            onNodeWithText(SECTION_PROFILE).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(APP_NAME).assertDoesNotExist()
        }
    }

    // mobile-design-system § "The feed surface applies the system-bar inset exactly once under the
    // shell app bar" — the tab row sits flush BELOW the app-bar logo (no nested Scaffold re-adds a
    // status-bar-height gap; the single shell Scaffold positions both).
    @Test
    fun feedTabRow_sitsBelowTheShellAppBar_insetsAppliedOnce() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { AppShellScreen(onOpenComposer = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(NEARBY_TIMELINE_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            val logoBottom =
                onNodeWithTag(SHELL_LOGO_LIGHT_TAG, useUnmergedTree = true)
                    .getUnclippedBoundsInRoot().bottom
            val tabTop = onNodeWithText(TAB_SEKITAR).getUnclippedBoundsInRoot().top
            assertTrue(
                tabTop >= logoBottom,
                "the feed tab row must sit below the shell app bar (tabTop=$tabTop, logoBottom=$logoBottom)",
            )
        }
    }
}
