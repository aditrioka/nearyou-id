package id.nearyou.app.screens.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.data.block.BlockedUser
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersOutcome
import id.nearyou.app.data.block.FakeBlockedUsersFlow
import id.nearyou.app.data.block.UnblockOutcome
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

private const val NAME = "Raka Pratama"
private const val HANDLE = "@raka.jkt"
private const val UUID = "11111111-1111-1111-1111-111111111111"
private const val UNBLOCK = "Buka blokir"
private const val EMPTY = "Belum ada pengguna yang diblokir."

/**
 * Render coverage of `BlockedUsersScreen` (the block-list management surface). The DTO/outcome mapping is
 * covered by `BlockedUsersRepositoryTest`, the projection by `BlockedUsersUiStateTest`, the unblock logic
 * by `BlockedUsersViewModelTest`; this suite verifies the composable renders identity (NOT the UUID),
 * the empty state, and routes to sign-in on a terminal 401.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class BlockedUsersScreenTest {
    private fun installKoin(flow: BlockedUsersFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single { flow } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private val raka = BlockedUser(userId = UUID, username = "raka.jkt", displayName = NAME, isPremium = false)

    @Test
    fun rendersDisplayNameAndHandle_butNotTheUuid() {
        installKoin(FakeBlockedUsersFlow(fetchOutcome = BlockedUsersOutcome.Loaded(listOf(raka), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(NAME).assertExists()
            onNodeWithText(HANDLE).assertExists()
            onAllNodesWithText(UUID, substring = true).fetchSemanticsNodes().let {
                assertEquals(0, it.size, "the blocked user's UUID must never be rendered")
            }
        }
    }

    @Test
    fun emptyList_showsTheEmptyStateCopy() {
        installKoin(FakeBlockedUsersFlow(fetchOutcome = BlockedUsersOutcome.Loaded(emptyList(), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(EMPTY).assertExists()
        }
    }

    @Test
    fun successfulUnblock_removesTheRow() {
        installKoin(
            FakeBlockedUsersFlow(
                fetchOutcome = BlockedUsersOutcome.Loaded(listOf(raka), nextCursor = null),
                unblockOutcome = UnblockOutcome.Success,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(NAME).assertExists()
            onNodeWithText(UNBLOCK).performClick()
            waitUntil { onAllNodesWithText(NAME).fetchSemanticsNodes().isEmpty() }
            onAllNodesWithText(NAME).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        }
    }

    @Test
    fun terminal401_routesToSignIn() {
        installKoin(FakeBlockedUsersFlow(fetchOutcome = BlockedUsersOutcome.TokenInvalid))
        var redirected = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = { redirected++ }) } } }
            waitUntil { redirected == 1 }
            assertEquals(1, redirected, "a terminal 401 routes to sign-in")
        }
    }
}
