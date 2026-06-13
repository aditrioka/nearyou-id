package id.nearyou.app.screens.followlist

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
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
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS counterpart to the Robolectric [FollowListScreenTest] — the follow-list surface
 * (`mobile-follow-lists`) run natively on the iOS simulator. Covers both tabs present, a row tap firing
 * the profile navigation, and the empty state, reusing the commonTest [FakeFollowListFlow]. K/N-legal fn
 * names (no `,()#`); see `ProfileFlowIosTest` for the v1-API + iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class FollowListFlowIosTest {
    private fun installKoin(fake: FakeFollowListFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single<FollowListFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun bothTabsRenderOverThePager() =
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
    fun rowTapNavigatesToProfile() =
        runComposeUiTest {
            var opened: String? = null
            installKoin(
                FakeFollowListFlow().apply {
                    responder = { _, _ ->
                        FollowListOutcome.Loaded(
                            FollowListPage(listOf(FollowListUser("u-9", "raka.jkt", "Raka Pratama", isPremium = false)), null),
                        )
                    }
                },
            )
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
            assertEquals("u-9", opened)
        }

    @Test
    fun emptyFollowersTabRendersTheEmptyCopy() =
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
}
