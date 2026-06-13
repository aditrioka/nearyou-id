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
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val NAME = "Raka Pratama"
private const val HANDLE = "@raka.jkt"
private const val UUID = "11111111-1111-1111-1111-111111111111"
private const val UNBLOCK = "Buka blokir"
private const val EMPTY = "Belum ada pengguna yang diblokir."

/**
 * iOS counterpart to the Robolectric `BlockedUsersScreenTest` — the `mobile-settings` block-list surface
 * run natively on the iOS simulator. Covers the identity render (NOT the UUID), the empty state, and the
 * unblock-removes-row flow, reusing the commonTest `FakeBlockedUsersFlow`. See `SignInFlowIosTest` for the
 * iosTest-placement + Kotlin/Native test-name rationale (no `(`,`)`,`,`,`#`).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class BlockedUsersFlowIosTest {
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
    fun rendersIdentityButNotTheUuid() {
        installKoin(FakeBlockedUsersFlow(fetchOutcome = BlockedUsersOutcome.Loaded(listOf(raka), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(NAME).assertExists()
            onNodeWithText(HANDLE).assertExists()
            assertEquals(0, onAllNodesWithText(UUID, substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun emptyListShowsEmptyCopy() {
        installKoin(FakeBlockedUsersFlow(fetchOutcome = BlockedUsersOutcome.Loaded(emptyList(), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(EMPTY).assertExists()
        }
    }

    @Test
    fun successfulUnblockRemovesTheRow() {
        installKoin(
            FakeBlockedUsersFlow(
                fetchOutcome = BlockedUsersOutcome.Loaded(listOf(raka), nextCursor = null),
                unblockOutcome = UnblockOutcome.Success,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { BlockedUsersScreen(onBack = {}, onTokenInvalid = {}) } } }
            onNodeWithText(UNBLOCK).performClick()
            waitUntil { onAllNodesWithText(NAME).fetchSemanticsNodes().isEmpty() }
            assertEquals(0, onAllNodesWithText(NAME).fetchSemanticsNodes().size)
        }
    }
}
