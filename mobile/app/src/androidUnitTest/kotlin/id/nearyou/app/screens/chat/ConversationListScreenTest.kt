package id.nearyou.app.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.chat.ConversationDto
import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.chat.FakeConversationsFlow
import id.nearyou.app.chat.PartnerProfileDto
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

private const val TITLE = "Pesan"
private const val EMPTY = "Belum ada percakapan. Mulai dari profil seseorang."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…"
private const val DELETED = "Akun Dihapus"
private const val RETRY = "Coba lagi"
private const val PARTNER_UUID = "11111111-1111-1111-1111-111111111111"

private fun item(
    id: String,
    displayName: String = "Dewi Lestari",
    lastMessageAt: String? = "2026-06-02T11:00:00Z",
) = ConversationListItemDto(
    conversation = ConversationDto(id = id, createdAt = "2026-06-01T10:00:00Z", lastMessageAt = lastMessageAt),
    partner = PartnerProfileDto(id = PARTNER_UUID, username = "dewi.kuliner", displayName = displayName, isPremium = false),
)

/**
 * Render coverage of `ConversationListScreen` via the Robolectric CMP runner (task 12.4). The
 * outcome→state projection is covered purely by `ConversationListUiStateTest`; this suite verifies each
 * state renders, a row tap pushes the thread route with the partner display fields, the deleted-partner
 * placeholder, exactly one loading indicator, and no partner UUID in the tree.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ConversationListScreenTest {
    private lateinit var fake: FakeConversationsFlow

    private fun installKoin(
        outcome: ConversationListOutcome = ConversationListOutcome.Loaded(emptyList(), null),
        suspendForever: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakeConversationsFlow(outcome = outcome, suspendForever = suspendForever)
        startKoin { modules(module { single<ConversationsFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun contentState_rendersRows_andTitle() {
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText("Dewi Lestari").assertExists()
        }
    }

    @Test
    fun emptyState_rendersEmptyCopy() {
        installKoin(ConversationListOutcome.Loaded(emptyList(), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(EMPTY).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(EMPTY).assertExists()
        }
    }

    @Test
    fun errorState_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(ConversationListOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(ERROR_NETWORK).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitUntil(timeoutMillis = 5_000) { fake.loadInvocationCount == 2 }
        }
    }

    @Test
    fun sessionExpired_rendersRedirectNotice_noRetry() {
        installKoin(ConversationListOutcome.SessionExpired)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(SESSION_REDIRECT).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(RETRY).assertDoesNotExist()
        }
    }

    @Test
    fun rowTap_pushesThreadRouteWithDisplayFields_andLeaksNoPartnerUuid() {
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1")), null))
        var tapped: ConversationRow? = null
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = { tapped = it }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            // No partner UUID anywhere in the rendered tree.
            onNodeWithText(PARTNER_UUID, substring = true).assertDoesNotExist()
            onNodeWithTag(CONVERSATION_ROW_TAG).performClick()
            waitForIdle()
            assertEquals("c1", tapped?.conversationId)
            assertEquals("Dewi Lestari", tapped?.partnerDisplayName)
            assertEquals("dewi.kuliner", tapped?.partnerUsername)
        }
    }

    @Test
    fun deletedPartner_rendersAkunDihapusPlaceholder() {
        // A blank display name (a stale/edge row) renders the chat_account_deleted placeholder.
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1", displayName = "")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(DELETED).assertExists()
        }
    }

    @Test
    fun initialLoad_showsExactlyOneLoadingIndicator() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Exactly one loading list/skeleton is mounted (no double indicator).
            assertEquals(1, onAllNodesWithTag(CONVERSATION_LIST_TAG).fetchSemanticsNodes().size)
        }
    }
}
