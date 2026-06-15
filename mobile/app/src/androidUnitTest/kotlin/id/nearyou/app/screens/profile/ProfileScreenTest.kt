package id.nearyou.app.screens.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.theme.NearYouTheme
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

private const val OTHER_UUID = "11111111-1111-1111-1111-111111111111"

private class FakeSelfUserId(private val id: String? = "self-1") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

/**
 * Robolectric render coverage of the profile surface (`mobile-profile`). Covers self-vs-other rendering
 * (the follow toggle + kebab appear only for an other-user read), the null-bio omission, the Premium
 * badge, the tappable counts (→ follow-list navigation), the block confirmation modal (Batal → no call; confirm → navigate
 * back), the six-category report picker, the not-found state, and the no-UUID-in-tree guard. Uses the
 * commonTest [FakeProfileFlow]; Release-excluded (the `*ScreenTest` host-activity convention).
 *
 * `@Suppress("DEPRECATION")`: keeps the v1 `runComposeUiTest` API the sibling screen tests use.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ProfileScreenTest {
    private fun installKoin(
        outcome: ProfileOutcome,
        selfId: String? = "self-1",
    ): FakeProfileFlow {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val fake = FakeProfileFlow(profileOutcome = outcome)
        startKoin {
            modules(
                module {
                    single<ProfileFlow> { fake }
                    single<SelfUserIdProvider> { FakeSelfUserId(selfId) }
                },
            )
        }
        return fake
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Composable
    private fun OtherProfile(onBack: () -> Unit = {}) {
        KoinContext { NearYouTheme { ProfileScreen(targetUserId = "u1", onBack = onBack) } }
    }

    @Test
    fun otherUserProfile_showsIdentityFollowToggleAndKebab() =
        runComposeUiTest {
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1", followedByViewer = false)))
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Raka Pratama").assertExists()
            onNodeWithText("@raka.jkt").assertExists()
            onNodeWithTag(PROFILE_FOLLOW_TOGGLE_TAG).assertExists()
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).assertExists()
            // The settings gear is self-section-only — it must NOT appear on the other-user overlay.
            onNodeWithTag(PROFILE_SETTINGS_TAG).assertDoesNotExist()
        }

    @Test
    fun selfProfile_showsNoFollowToggleOrKebab() =
        runComposeUiTest {
            installKoin(
                ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("self-1", isSelf = true)),
                selfId = "self-1",
            )
            setContent { KoinContext { NearYouTheme { ProfileScreen(targetUserId = null, onBack = null) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_FOLLOW_TOGGLE_TAG).assertDoesNotExist()
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).assertDoesNotExist()
        }

    @Test
    fun selfProfile_showsSettingsGear_thatInvokesOnSettings() =
        runComposeUiTest {
            var settingsOpened = 0
            installKoin(
                ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("self-1", isSelf = true)),
                selfId = "self-1",
            )
            setContent {
                KoinContext {
                    NearYouTheme {
                        ProfileScreen(targetUserId = null, onBack = null, onSettings = { settingsOpened++ })
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_SETTINGS_TAG).fetchSemanticsNodes().isNotEmpty() }
            // The self section carries the settings gear (the Settings surface's entry point, #288); tapping
            // it invokes onSettings (the shell wires this to a SettingsRoute push).
            onNodeWithTag(PROFILE_SETTINGS_TAG).assertExists()
            onNodeWithTag(PROFILE_SETTINGS_TAG).performClick()
            assertEquals(1, settingsOpened, "tapping the self-profile gear invokes onSettings")
        }

    @Test
    fun nullBio_rendersNoBioRow() =
        runComposeUiTest {
            val profile = FakeProfileFlow.sampleProfile("u1").copy(bio = null)
            val fake = installKoin(ProfileOutcome.Loaded(profile))
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            // The default sample bio "halo" must be absent when bio is null.
            onAllNodesWithText("halo").assertCountEquals(0)
        }

    @Test
    fun premiumBadge_reflectsIsPremium() =
        runComposeUiTest {
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1").copy(isPremium = true)))
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Premium").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Premium").assertExists()
        }

    @Test
    fun counts_areTappable_navigateToFollowListAtTheMatchingTab() =
        runComposeUiTest {
            var navTarget: Pair<String, FollowListTab>? = null
            installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            setContent {
                KoinContext {
                    NearYouTheme {
                        ProfileScreen(
                            targetUserId = "u1",
                            onBack = {},
                            onOpenFollowList = { id, tab -> navTarget = id to tab },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Both counts are clickable nodes now (the lists are reachable — the inverted mobile-profile
            // requirement), each carrying a content description.
            onNodeWithTag(PROFILE_FOLLOWERS_TAG).assert(hasClickAction())
            onNodeWithTag(PROFILE_FOLLOWING_TAG).assert(hasClickAction())
            // The follower count → the Followers tab for this profile's id; the following count → Following.
            onNodeWithTag(PROFILE_FOLLOWERS_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { navTarget != null }
            assertEquals("u1" to FollowListTab.Followers, navTarget)
            onNodeWithTag(PROFILE_FOLLOWING_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { navTarget == "u1" to FollowListTab.Following }
            assertEquals("u1" to FollowListTab.Following, navTarget)
        }

    @Test
    fun blockKebab_opensConfirmDialog_andConfirmNavigatesBack() =
        runComposeUiTest {
            var backCount = 0
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            setContent { OtherProfile(onBack = { backCount++ }) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_ACTIONS_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).performClick()
            onNodeWithText("Blokir @raka.jkt").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Confirm "Blokir" → block call fires + the screen navigates back (the one-shot).
            onNodeWithText("Blokir").performClick()
            waitUntil(timeoutMillis = 5_000) { backCount == 1 }
            assertEquals("u1", fake.blockedUserId)
            assertEquals(1, backCount)
        }

    @Test
    fun blockDialog_cancel_makesNoCallAndDoesNotNavigate() =
        runComposeUiTest {
            var backCount = 0
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            setContent { OtherProfile(onBack = { backCount++ }) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_ACTIONS_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).performClick()
            onNodeWithText("Blokir @raka.jkt").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Batal").performClick()
            waitForIdle()
            assertNull(fake.blockedUserId, "Batal issues no block call")
            assertEquals(0, backCount)
        }

    @Test
    fun reportKebab_opensPickerWithExactlyTheSixCategories() =
        runComposeUiTest {
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_ACTIONS_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).performClick()
            onNodeWithText("Laporkan").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_REPORT_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Spam").assertExists()
            onNodeWithText("Ujaran kebencian (SARA)").assertExists()
            onNodeWithText("Pelecehan").assertExists()
            onNodeWithText("Konten dewasa").assertExists()
            onNodeWithText("Misinformasi").assertExists()
            onNodeWithText("Lainnya").assertExists()
            // The internal-only classifications are never user-pickable.
            onAllNodesWithText("self_harm", substring = true).assertCountEquals(0)
            onAllNodesWithText("csam", substring = true).assertCountEquals(0)
        }

    @Test
    fun notFound_rendersTheNotFoundState() =
        runComposeUiTest {
            val fake = installKoin(ProfileOutcome.NotFound)
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_NOT_FOUND_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_NOT_FOUND_TAG).assertExists()
        }

    @Test
    fun noUserIdUuid_appearsInTheRenderedTree() =
        runComposeUiTest {
            val fake = installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile(OTHER_UUID)))
            setContent { OtherProfile() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("11111111", substring = true).assertCountEquals(0)
        }
}
