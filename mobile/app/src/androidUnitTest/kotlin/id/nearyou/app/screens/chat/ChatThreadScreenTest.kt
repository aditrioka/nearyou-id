package id.nearyou.app.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ChatMessageDto
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeChatRealtimeSubscriber
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.notifications.FakeNotificationPermissionController
import id.nearyou.app.notifications.FakeNotificationPromptOneShot
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionStatus
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.screens.paywall.FakePurchaseController
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PaywallRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import id.nearyou.resources.theme.NearYouColorScheme
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
import kotlin.test.assertNotEquals

private const val CONV = "11111111-1111-1111-1111-111111111111"
private const val VIEWER = "22222222-2222-2222-2222-222222222222"
private const val OTHER = "33333333-3333-3333-3333-333333333333"
private const val REDACTED = "Pesan ini telah dihapus"
private const val BLOCKED = "Tidak dapat mengirim pesan ke user ini"
private const val DELETED = "Akun Dihapus"
private const val NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val TOO_LONG = "Pesan maksimal 2000 karakter."
private const val RATIONALE = "Aktifkan notifikasi agar kamu tahu saat ada pesan baru."
private const val RATIONALE_CONFIRM = "Izinkan notifikasi"

// cap-upsell-parity: chat_cap_upsell with the cap dialog's 1140s countdown ("19 mnt").
private const val CHAT_CAP_19M =
    "Kamu sudah mengirim 50 pesan hari ini. Upgrade ke Premium untuk chat tanpa batas, atau tunggu reset dalam 19 mnt."

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
 * renders, the redacted placeholder bubble, the input bar, the send-blocked / network-retry / too-long
 * send states, pull-to-refresh, the once-per-install first-send rationale, the deleted-partner top-bar
 * placeholder, and the themed top-bar title in light + dark.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ChatThreadScreenTest {
    private lateinit var flow: FakeChatFlow
    private lateinit var reportSubmitter: FakeReportSubmitter
    private lateinit var notificationController: FakeNotificationPermissionController
    private lateinit var promptOneShot: FakeNotificationPromptOneShot

    private fun installKoin(
        history: ChatThreadOutcome = ChatThreadOutcome.Loaded(emptyList(), null),
        sendOutcome: SendOutcome = SendOutcome.Sent(dto("server-1", sender = VIEWER, content = "hi")),
        reportOutcome: ReportOutcome = ReportOutcome.Submitted,
        historyOutcomes: List<ChatThreadOutcome> = listOf(history),
        notificationStatus: NotificationPermissionStatus = NotificationPermissionStatus.GRANTED,
        rationaleAlreadyShown: Boolean = false,
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        flow = FakeChatFlow(historyOutcomes = historyOutcomes, sendOutcome = sendOutcome)
        reportSubmitter = FakeReportSubmitter(reportOutcome)
        notificationController = FakeNotificationPermissionController(notificationStatus)
        promptOneShot = FakeNotificationPromptOneShot(claimed = rationaleAlreadyShown)
        startKoin {
            modules(
                module {
                    single<ChatFlow> { flow }
                    single<ChatRealtimeSubscriber> { FakeChatRealtimeSubscriber() }
                    single<ViewerIdProvider> { ViewerIdProvider { VIEWER } }
                    single<ReportSubmitter> { reportSubmitter }
                    single<NotificationPermissionController> { notificationController }
                    single<NotificationPromptOneShot> { promptOneShot }
                    // The host-push test navigates onward to PaywallRoute (Unconfigured fail-soft state).
                    single<PurchaseController> { FakePurchaseController(OfferingsResult.Unavailable) }
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

    // mobile-chat § "Over-length content blocked client-side": no request AND the bar shows the too-long state.
    @Test
    fun overLimitInput_disablesSend_andShowsTheTooLongState() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_TOO_LONG_TAG).assertDoesNotExist()
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("a".repeat(MAX_CHAT_CONTENT_CODE_POINTS + 1))
            onNodeWithTag(CHAT_THREAD_SEND_TAG).assertIsNotEnabled()
            onNodeWithTag(CHAT_THREAD_TOO_LONG_TAG).assertExists()
            onNodeWithText(TOO_LONG).assertExists()
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitForIdle()
            assertEquals(0, flow.sendCount, "an over-limit input issues no request")
        }
    }

    // #494: a failed send (transport / 5xx) renders the network-retry state instead of silently dropping the
    // bubble; the typed input is kept and the retry re-sends it.
    @Test
    fun networkErrorSend_showsRetryBanner_keepsTheInput_andRetryResends() {
        installKoin(sendOutcome = SendOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("halo")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_RETRY_BANNER_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(NETWORK).assertExists()
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).assertTextEquals("halo", includeEditableText = true)
            flow.sendOutcome = SendOutcome.Sent(dto("server-2", sender = VIEWER, content = "halo"))
            onNodeWithTag(CHAT_THREAD_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_RETRY_BANNER_TAG).fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf<String?>("halo", "halo"), flow.sentContents, "the retry re-sent the kept input")
            onNodeWithText("halo").assertExists()
        }
    }

    // #494 / mobile-chat § "Reconnect resyncs via REST…": pull-to-refresh over the retained thread resyncs.
    @Test
    fun pullToRefresh_resyncsHistory_andKeepsTheThreadMounted() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", content = "STAYS")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("STAYS").fetchSemanticsNodes().isNotEmpty() }
            val before = flow.loadHistoryCount
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == before + 1 }
            onNodeWithText("STAYS").assertExists()
        }
    }

    // mobile-design-system § "Pull-to-refresh is available from a non-Content state": the error state is
    // scrollable, so a pull reloads; the reload then lands the thread.
    @Test
    fun pullToRefresh_worksFromTheErrorState() {
        installKoin(
            historyOutcomes =
                listOf(ChatThreadOutcome.NetworkError, ChatThreadOutcome.Loaded(listOf(dto("m1", content = "BACK")), null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(NETWORK).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("BACK").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    // mobile-chat § "First-send notification-permission prompt" + #487: the rationale confirms with the
    // notification copy (not "Kirim pesan"), launches the OS request, and a later send does not re-prompt.
    @Test
    fun firstSend_showsRationaleOnce_confirmLabelIsTheNotificationCopy() {
        installKoin(notificationStatus = NotificationPermissionStatus.NOT_DETERMINED)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("satu")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(RATIONALE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(RATIONALE_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) { notificationController.requestCount == 1 }
            onNodeWithText(RATIONALE).assertDoesNotExist()
            flow.sendOutcome = SendOutcome.Sent(dto("server-2", sender = VIEWER, content = "dua"))
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("dua")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { flow.sendCount == 2 }
            waitForIdle()
            onNodeWithText(RATIONALE).assertDoesNotExist()
            assertEquals(1, notificationController.requestCount, "a subsequent send does not re-prompt")
        }
    }

    // #494: the one-shot is per INSTALL — a relaunch (fresh composition + VM) after the rationale was shown
    // on this install does not show it again.
    @Test
    fun firstSendAfterRelaunch_doesNotReshowTheRationale() {
        installKoin(notificationStatus = NotificationPermissionStatus.NOT_DETERMINED, rationaleAlreadyShown = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("hi")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { flow.sendCount == 1 }
            waitForIdle()
            onNodeWithText(RATIONALE).assertDoesNotExist()
            assertEquals(0, notificationController.requestCount)
        }
    }

    // #488: the thread sits in a themed Scaffold, so the top-bar title resolves the theme's on-color in dark
    // mode (it was LocalContentColor's default black on the black window).
    @Test
    fun darkTheme_topBarTitleUsesTheThemeOnColor_notBlack() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme(darkTheme = true) { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dewi Lestari").fetchSemanticsNodes().isNotEmpty() }
            val color = onNodeWithText("Dewi Lestari").textColor()
            assertNotEquals(Color.Black, color)
            assertEquals(NearYouColorScheme.dark.onBackground, color)
        }
    }

    @Test
    fun lightTheme_topBarTitleUsesTheThemeOnColor() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme(darkTheme = false) { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Dewi Lestari").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(NearYouColorScheme.light.onBackground, onNodeWithText("Dewi Lestari").textColor())
        }
    }

    /** The resolved text color the node was laid out with (M3 `Text` merges LocalContentColor into the style). */
    private fun SemanticsNodeInteraction.textColor(): Color {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.color
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

    // --- cap-upsell-parity: the Free 50/day chat cap (429) → the shared frame-18 cap dialog ---

    @Test
    fun rateLimitedSend_showsChatCapDialog_dropsTheBubble_andKeepsTheInput() {
        installKoin(sendOutcome = SendOutcome.RateLimited(retryAfterSeconds = 1_140))
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        ChatThreadScreen(
                            route = route(),
                            onBack = {},
                            onActivatePremium = { activated++ },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("halo")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(CHAT_CAP_19M).assertExists()
            // "halo" now matches ONLY the input field — the optimistic bubble was dropped, the text kept.
            onAllNodesWithText("halo").assertCountEquals(1)
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).assertTextEquals("halo", includeEditableText = true)
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitForIdle()
            assertEquals(1, activated, "the CTA opens the paywall via the host")
            onNodeWithTag(DAILY_CAP_DIALOG_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun chatCapDialog_tutupClearsWithoutOpeningThePaywall() {
        installKoin(sendOutcome = SendOutcome.RateLimited(retryAfterSeconds = 1_140))
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        ChatThreadScreen(
                            route = route(),
                            onBack = {},
                            onActivatePremium = { activated++ },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("halo")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DAILY_CAP_DIALOG_CLOSE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(DAILY_CAP_DIALOG_TAG).assertDoesNotExist()
            assertEquals(0, activated, "Tutup only dismisses")
        }
    }

    // The host-push half under the REAL appEntryProvider: the chat cap CTA pushes PaywallRoute(CHAT_CAP).
    @Test
    fun chatCapCta_underHost_pushesPaywallRouteChatCap() {
        installKoin(sendOutcome = SendOutcome.RateLimited(retryAfterSeconds = 1_140))
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(route(), onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("halo")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is PaywallRoute }
            assertEquals(PaywallRoute(PaywallEntry.CHAT_CAP), backStack.last())
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

    // --- mobile-chat-message-report ---

    @Test
    fun longPressReceivedMessage_opensReportDialog_andSubmitShowsSuccess() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", sender = OTHER, content = "REPORT_ME")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REPORT_ME").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("REPORT_ME").performTouchInput { longClick() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).performClick()
            // The shared report dialog opens.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_REPORT_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            // Submit with the default-selected reason → success snackbar (FakeReportSubmitter → Submitted).
            onNodeWithText("Kirim laporan").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Laporan terkirim. Tim moderasi akan meninjau.").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Laporan terkirim. Tim moderasi akan meninjau.").assertExists()
        }
    }

    @Test
    fun longPressOwnMessage_showsNoReportAffordance() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", sender = VIEWER, content = "MY_OWN_MSG")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MY_OWN_MSG").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("MY_OWN_MSG").performTouchInput { longClick() }
            // No report affordance: an own message is not reportable (gate `!isOwn`).
            onNodeWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun longPressRedactedMessage_showsNoReportAffordance() {
        installKoin(
            ChatThreadOutcome.Loaded(
                listOf(dto("m1", sender = OTHER, content = null, redactedAt = "2026-06-02T00:00:00Z")),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_REDACTED_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(REDACTED).performTouchInput { longClick() }
            // No report affordance: an already-redacted message is not reportable (gate `!isRedacted`).
            onNodeWithTag(CHAT_MESSAGE_REPORT_ITEM_TAG).assertDoesNotExist()
        }
    }
}
