package id.nearyou.app.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeChatRealtimeSubscriber
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.notifications.FakeNotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

private const val CONV = "11111111-1111-1111-1111-111111111111"
private const val VIEWER = "22222222-2222-2222-2222-222222222222"
private const val OTHER = "33333333-3333-3333-3333-333333333333"
private const val REPORT_SUCCESS = "Laporan terkirim. Tim moderasi akan meninjau."
private const val SUBMIT = "Kirim laporan"

/**
 * iOS counterpart to the Robolectric `ChatThreadScreenTest` chat-message-report coverage
 * (`mobile-chat-message-report`, task 4.6) — the long-press "Laporkan" → shared report dialog → submit →
 * success flow run natively on the iOS simulator. Modeled on `PostDetailFlowIosTest` (there is no
 * pre-existing chat iOS flow test to copy); reuses the commonTest fakes. Kotlin/Native test-name
 * restrictions apply (no `(`, `)`, `,`, `#`), so the function names are plain camelCase.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ChatThreadReportFlowIosTest {
    private fun dto(
        id: String,
        sender: String = OTHER,
        content: String? = "c",
        redactedAt: String? = null,
    ) = ChatMessageDto(
        id = id,
        conversationId = CONV,
        senderId = sender,
        content = content,
        createdAt = "2026-06-01T10:00:00Z",
        redactedAt = redactedAt,
    )

    private fun installKoin(history: ChatThreadOutcome) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<ChatFlow> { FakeChatFlow(historyOutcomes = listOf(history)) }
                    single<ChatRealtimeSubscriber> { FakeChatRealtimeSubscriber() }
                    single<ViewerIdProvider> { ViewerIdProvider { VIEWER } }
                    single<ReportSubmitter> { FakeReportSubmitter(ReportOutcome.Submitted) }
                    single<NotificationPermissionController> { FakeNotificationPermissionController() }
                    single { NotificationPromptOneShot() }
                },
            )
        }
    }

    private fun route() = ChatThreadRoute(conversationId = CONV, partnerUsername = "dewi.kuliner", partnerDisplayName = "Dewi Lestari")

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun longPressReceivedMessageOpensReportDialogAndSubmitShowsSuccess() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", sender = OTHER, content = "REPORT_ME")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REPORT_ME").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("REPORT_ME").performTouchInput { longClick() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_REPORT_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(SUBMIT).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(REPORT_SUCCESS).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(REPORT_SUCCESS).assertExists()
        }
    }

    @Test
    fun ownMessageShowsNoReportAffordance() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", sender = VIEWER, content = "MY_OWN_MSG")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MY_OWN_MSG").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("MY_OWN_MSG").performTouchInput { longClick() }
            onNodeWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).assertDoesNotExist()
        }
    }
}
