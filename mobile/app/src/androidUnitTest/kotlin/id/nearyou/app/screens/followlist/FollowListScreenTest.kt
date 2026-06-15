package id.nearyou.app.screens.followlist

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.followlist.FakeFollowListFlow
import id.nearyou.app.followlist.FollowListFlow
import id.nearyou.app.followlist.FollowListOutcome
import id.nearyou.app.followlist.FollowListPage
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.followlist.FollowListUser
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

private const val ROW_UUID = "11111111-1111-1111-1111-111111111111"

/**
 * Robolectric render coverage of the follow-list surface (`mobile-follow-lists`). Covers both tabs
 * present, the two empty states (per tab), a row tap firing the profile navigation, and the
 * no-UUID-in-tree guard. Uses the commonTest [FakeFollowListFlow]; Release-excluded (the `*ScreenTest`
 * host-activity convention).
 *
 * `@Suppress("DEPRECATION")`: keeps the v1 `runComposeUiTest` API the sibling screen tests use.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class FollowListScreenTest {
    private fun installKoin(fake: FakeFollowListFlow): FakeFollowListFlow {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single<FollowListFlow> { fake } }) }
        return fake
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun row(
        userId: String,
        displayName: String,
        username: String,
    ): FollowListOutcome =
        FollowListOutcome.Loaded(
            FollowListPage(listOf(FollowListUser(userId, username, displayName, isPremium = false)), null),
        )

    @Test
    fun bothTabs_arePresent() =
        runComposeUiTest {
            installKoin(FakeFollowListFlow().apply { responder = { _, _ -> FollowListOutcome.Loaded(FollowListPage(emptyList(), null)) } })
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(
                            userId = "p1",
                            initialTab = FollowListTab.Followers,
                            onBack = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Pengikut").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Pengikut").assertExists()
            onNodeWithText("Mengikuti").assertExists()
        }

    @Test
    fun followersTab_emptyState_showsTheNoFollowersCopy() =
        runComposeUiTest {
            installKoin(FakeFollowListFlow().apply { responder = { _, _ -> FollowListOutcome.Loaded(FollowListPage(emptyList(), null)) } })
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(
                            userId = "p1",
                            initialTab = FollowListTab.Followers,
                            onBack = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Belum ada pengikut").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Belum ada pengikut").assertExists()
        }

    @Test
    fun followingTab_emptyState_showsTheNotFollowingCopy() =
        runComposeUiTest {
            // Open directly on the Following tab so its empty copy renders without a swipe.
            installKoin(FakeFollowListFlow().apply { responder = { _, _ -> FollowListOutcome.Loaded(FollowListPage(emptyList(), null)) } })
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(
                            userId = "p1",
                            initialTab = FollowListTab.Following,
                            onBack = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Belum mengikuti siapa pun").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Belum mengikuti siapa pun").assertExists()
        }

    @Test
    fun rowTap_firesOpenProfileWithTheRowUserId() =
        runComposeUiTest {
            var opened: String? = null
            installKoin(FakeFollowListFlow().apply { responder = { _, _ -> row(ROW_UUID, "Raka Pratama", "raka.jkt") } })
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(
                            userId = "p1",
                            initialTab = FollowListTab.Followers,
                            onBack = {},
                            onOpenProfile = { opened = it },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Raka Pratama").performClick()
            waitUntil(timeoutMillis = 5_000) { opened != null }
            assertEquals(ROW_UUID, opened)
        }

    @Test
    fun rowUserIdUuid_isNotInTheRenderedTree() =
        runComposeUiTest {
            installKoin(FakeFollowListFlow().apply { responder = { _, _ -> row(ROW_UUID, "Raka Pratama", "raka.jkt") } })
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(
                            userId = ROW_UUID,
                            initialTab = FollowListTab.Followers,
                            onBack = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("@raka.jkt").assertExists()
            onAllNodesWithText("11111111", substring = true).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "the row userId UUID must not be rendered")
            }
        }

    @Test
    fun premiumRow_showsTheBadgeContentDescription() =
        runComposeUiTest {
            installKoin(
                FakeFollowListFlow().apply {
                    responder = { _, _ ->
                        FollowListOutcome.Loaded(
                            FollowListPage(listOf(FollowListUser("u-p", "sari.bdg", "Sari Lestari", isPremium = true)), null),
                        )
                    }
                },
            )
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(userId = "p1", initialTab = FollowListTab.Followers, onBack = {}, onOpenProfile = {})
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Sari Lestari").fetchSemanticsNodes().isNotEmpty() }
            // The Premium badge is an M3 icon carrying the "Akun Premium" content description (not color-only).
            onNodeWithContentDescription("Akun Premium").assertExists()
        }

    @Test
    fun tappingMengikutiTab_switchesToTheFollowingContent() =
        runComposeUiTest {
            // Followers empty; Following has a distinct row — tapping the Mengikuti tab reveals it (the
            // tab → pager sync), confirming the tab tap drives the pager.
            installKoin(
                FakeFollowListFlow().apply {
                    responder = { tab, _ ->
                        if (tab == FollowListTab.Following) {
                            FollowListOutcome.Loaded(
                                FollowListPage(listOf(FollowListUser("u-f", "sari.bdg", "Sari Following", isPremium = false)), null),
                            )
                        } else {
                            FollowListOutcome.Loaded(FollowListPage(emptyList(), null))
                        }
                    }
                },
            )
            setContent {
                KoinContext {
                    NearYouTheme {
                        FollowListScreen(userId = "p1", initialTab = FollowListTab.Followers, onBack = {}, onOpenProfile = {})
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Belum ada pengikut").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Mengikuti").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Sari Following").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Sari Following").assertExists()
        }
}
