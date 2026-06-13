package id.nearyou.app.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeChatRealtimeSubscriber
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.notifications.FakeNotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.screens.routing.ChatThreadRoute
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

private const val CONV = "11111111-1111-1111-1111-111111111111"
private const val VIEWER = "22222222-2222-2222-2222-222222222222"
private const val OTHER = "33333333-3333-3333-3333-333333333333"
private const val REDACTED = "Pesan ini telah dihapus"
private const val BLOCKED = "Tidak dapat mengirim pesan ke user ini"
private const val DELETED = "Akun Dihapus"

private fun dto(
    id: String,
    sender: String = OTHER,
    content: String? = "c",
    createdAt: String = "2026-06-01T10:00:00Z",
    redactedAt: String? = null,
) = ChatMessageDto(id = id, conversationId = CONV, senderId = sender, content = content, createdAt = createdAt, redactedAt = redactedAt)

/**
 * Render coverage of `ChatThreadScreen` via the Robolectric CMP runner (task 12.4). The merge/projection
 * is covered purely by `ChatThreadUiStateTest` + `ChatThreadViewModelTest`; this suite verifies history
 * renders, the redacted placeholder bubble, the input bar, the send-blocked banner, the 2000-char guard
 * disables send, and the deleted-partner top-bar placeholder.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ChatThreadScreenTest {
    private lateinit var flow: FakeChatFlow

    private fun installKoin(
        history: ChatThreadOutcome = ChatThreadOutcome.Loaded(emptyList(), null),
        sendOutcome: SendOutcome = SendOutcome.Sent(dto("server-1", sender = VIEWER, content = "hi")),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        flow = FakeChatFlow(historyOutcomes = listOf(history), sendOutcome = sendOutcome)
        startKoin {
            modules(
                module {
                    single<ChatFlow> { flow }
                    single<ChatRealtimeSubscriber> { FakeChatRealtimeSubscriber() }
                    single<ViewerIdProvider> { ViewerIdProvider { VIEWER } }
                    single<NotificationPermissionController> { FakeNotificationPermissionController() }
                    single { NotificationPromptOneShot() }
                },
            )
        }
    }

    private fun route(partnerDisplayName: String = "Dewi Lestari") =
        ChatThreadRoute(conversationId = CONV, partnerUsername = "dewi.kuliner", partnerDisplayName = partnerDisplayName)

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun history_rendersMessagesAndPartnerIdentity() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", sender = OTHER, content = "HALO_THREAD")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("HALO_THREAD").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("HALO_THREAD").assertExists()
            onNodeWithText("Dewi Lestari").assertExists()
        }
    }

    @Test
    fun redactedMessage_rendersPlaceholderNotEmptyBubble() {
        installKoin(
            ChatThreadOutcome.Loaded(listOf(dto("m1", content = null, redactedAt = "2026-06-02T00:00:00Z")), null),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_REDACTED_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(REDACTED).assertExists()
        }
    }

    @Test
    fun inputBar_rendersAndSendIsDisabledWhenEmpty() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_SEND_TAG).assertIsNotEnabled()
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("hi")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).assertIsEnabled()
        }
    }

    @Test
    fun overLimitInput_disablesSend() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("a".repeat(MAX_CHAT_CONTENT_CODE_POINTS + 1))
            onNodeWithTag(CHAT_THREAD_SEND_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun blockedSend_showsBlockedBanner() {
        installKoin(sendOutcome = SendOutcome.Blocked)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("hi")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_BLOCKED_BANNER_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(BLOCKED).assertExists()
        }
    }

    @Test
    fun deletedPartner_topBarRendersAkunDihapus() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(partnerDisplayName = ""), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(DELETED).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(DELETED).assertExists()
        }
    }
}
