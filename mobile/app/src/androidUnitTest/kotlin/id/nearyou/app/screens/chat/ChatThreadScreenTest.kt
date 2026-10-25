package id.nearyou.app.screens.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import id.nearyou.app.infra.supabaserealtime.ChatMessageInbound
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.notifications.FakeNotificationPermissionController
import id.nearyou.app.notifications.FakeNotificationPromptOneShot
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionStatus
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostRefreshOutcome
import id.nearyou.app.screens.paywall.FakePurchaseController
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PaywallRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import id.nearyou.app.ui.components.localDateLabel
import id.nearyou.app.ui.components.localTimeLabel
import id.nearyou.resources.theme.NearYouColorScheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
private const val CTA_BACK = "Kembali"
private const val CTA_CLOSE = "Tutup"
private const val PREMIUM_BADGE = "Akun Premium"
private const val MSG_NEW_ID = "66666666-6666-6666-6666-666666666666"

/** Every indeterminate progress indicator (the initial-load spinner and the refreshing PTR spinner). */
private val INDETERMINATE =
    SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

// cap-upsell-parity: chat_cap_upsell with the cap dialog's 1140s countdown ("19 mnt").
private const val CHAT_CAP_19M =
    "Kamu sudah mengirim 50 pesan hari ini. Upgrade ke Premium untuk chat tanpa batas, atau tunggu reset dalam 19 mnt."

private const val SHARED_EDITED_AT = "2026-06-01T09:00:00Z"
private const val EDITED_BANNER = "Diedit sejak dibagikan"

// An embed-only message sharing [postId], its snapshot captured at [SHARED_EDITED_AT] with [content].
private fun embedDto(
    id: String,
    postId: String,
    content: String,
    createdAt: String = "2026-06-01T10:00:00Z",
) = dto(id, content = null, createdAt = createdAt).copy(
    embeddedPostId = postId,
    embeddedPostSnapshot =
        EmbeddedPostSnapshot(
            authorUsername = "raka",
            authorDisplayName = "Raka",
            content = content,
            cityName = "Jakarta",
            createdAt = "2026-06-01T08:00:00Z",
            editedAt = SHARED_EDITED_AT,
        ),
)

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
@OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)
class ChatThreadScreenTest {
    private lateinit var flow: FakeChatFlow
    private lateinit var realtime: FakeChatRealtimeSubscriber
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
        postEdits: FakePostEditFlow = FakePostEditFlow(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        flow = FakeChatFlow(historyOutcomes = historyOutcomes, sendOutcome = sendOutcome)
        realtime = FakeChatRealtimeSubscriber()
        reportSubmitter = FakeReportSubmitter(reportOutcome)
        notificationController = FakeNotificationPermissionController(notificationStatus)
        promptOneShot = FakeNotificationPromptOneShot(claimed = rationaleAlreadyShown)
        startKoin {
            modules(
                module {
                    single<ChatFlow> { flow }
                    single<ChatRealtimeSubscriber> { realtime }
                    single<ViewerIdProvider> { ViewerIdProvider { VIEWER } }
                    single<ReportSubmitter> { reportSubmitter }
                    single<PostEditFlow> { postEdits }
                    single<NotificationPermissionController> { notificationController }
                    single<NotificationPromptOneShot> { promptOneShot }
                    // The host-push test navigates onward to PaywallRoute (Unconfigured fail-soft state).
                    single<PurchaseController> { FakePurchaseController(OfferingsResult.Unavailable) }
                },
            )
        }
    }

    private fun route(
        partnerDisplayName: String = "Dewi Lestari",
        partnerUsername: String = "dewi.kuliner",
        partnerIsPremium: Boolean = false,
    ) = ChatThreadRoute(
        conversationId = CONV,
        partnerUsername = partnerUsername,
        partnerDisplayName = partnerDisplayName,
        partnerIsPremium = partnerIsPremium,
    )

    /** The 20-message newest page (MSG_21..MSG_40, cursor to an older page) and that older page (MSG_1..MSG_20). */
    private fun newestPage() = (21..40).map { dto("m$it", content = "MSG_$it", createdAt = "2026-06-01T10:%02d:00Z".format(it)) }

    private fun olderPage() = (1..20).map { dto("m$it", content = "MSG_$it", createdAt = "2026-06-01T10:%02d:00Z".format(it)) }

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

    // mobile-chat § "Input bar renders and loading uses a single indicator": the input bar is present in
    // both the initial load and a refresh, and exactly one progress indicator shows at a time (the
    // initial-load spinner, then the pull-to-refresh spinner over the retained thread).
    @Test
    fun initialLoadAndRefresh_eachShowExactlyOneIndicator_withTheInputBar() {
        installKoin(ChatThreadOutcome.Loaded(listOf(dto("m1", content = "STAYS")), null))
        val initialGate = CompletableDeferred<Unit>()
        flow.historyGate = initialGate
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(INDETERMINATE).assertCountEquals(1)
            initialGate.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("STAYS").fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(INDETERMINATE).assertCountEquals(0)
            val refreshGate = CompletableDeferred<Unit>()
            flow.historyGate = refreshGate
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 2 }
            waitForIdle()
            onNodeWithText("STAYS").assertExists()
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).assertExists()
            onAllNodes(INDETERMINATE).assertCountEquals(1)
            refreshGate.complete(Unit)
        }
    }

    // #494 review: the persisted one-shot is claimed when the rationale opens, so a config change while it
    // is open must restore it (else the install's only notification-permission request is lost).
    @Test
    fun openRationale_survivesAConfigChange_andStillLaunchesTheRequest() {
        installKoin(notificationStatus = NotificationPermissionStatus.NOT_DETERMINED)
        runComposeUiTest {
            val restore = restorableChatThread()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("satu")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(RATIONALE).fetchSemanticsNodes().isNotEmpty() }
            restore()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(RATIONALE).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(RATIONALE_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) { notificationController.requestCount == 1 }
        }
    }

    // #494 review: the consumed Sent outcome is not replayed by a recreated composition, so a draft typed
    // after the last successful send survives a config change.
    @Test
    fun draftTypedAfterASend_survivesAConfigChange() {
        installKoin(rationaleAlreadyShown = true)
        runComposeUiTest {
            val restore = restorableChatThread()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("satu")
            onNodeWithTag(CHAT_THREAD_SEND_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { flow.sendCount == 1 }
            waitForIdle()
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).performTextInput("draf baru")
            restore()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_INPUT_TAG).assertTextEquals("draf baru", includeEditableText = true)
        }
    }

    /**
     * Composes the thread under a [SaveableStateRegistry] and returns a `restore()` that simulates a
     * config change: save, drop the branch, re-enter the SAME slot with the captured values (the
     * `PostDetailScreenTest` harness — the junit4 `StateRestorationTester` is not on this classpath). The
     * route-scoped ViewModel survives, as it does across a real recreation. With [processDeath] the restore
     * also clears the ViewModel store, so a NEW ViewModel reloads the thread (only the saved state survives).
     */
    private fun ComposeUiTest.restorableChatThread(processDeath: Boolean = false): () -> Unit {
        var savedState: Map<String, List<Any?>>? = null
        var registry: SaveableStateRegistry? = null
        var generation by mutableStateOf(0)
        var owner = TestViewModelStoreOwner()
        setContent {
            if (generation != -1) {
                val current = remember(generation) { SaveableStateRegistry(restoredValues = savedState, canBeSaved = { true }) }
                registry = current
                CompositionLocalProvider(LocalSaveableStateRegistry provides current, LocalViewModelStoreOwner provides owner) {
                    KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } }
                }
            }
        }
        return {
            runOnIdle { savedState = registry!!.performSave() }
            runOnIdle { generation = -1 }
            waitForIdle()
            if (processDeath) {
                owner.viewModelStore.clear()
                owner = TestViewModelStoreOwner()
            }
            runOnIdle { generation = 1 }
            waitForIdle()
        }
    }

    private class TestViewModelStoreOwner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
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
            // The mask hides the route's handle too (no stale identity beside the placeholder).
            onNodeWithText("@dewi.kuliner").assertDoesNotExist()
        }
    }

    // Frame 5 (#286): the partner header is a back ARROW (described "Kembali") + the name over the @handle.
    @Test
    fun topBar_showsBackArrowAndPartnerHandle_andTheArrowInvokesOnBack() {
        installKoin()
        var backCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = { backCount++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("@dewi.kuliner").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Dewi Lestari").assertExists()
            onNodeWithTag(CHAT_THREAD_BACK_TAG).assertContentDescriptionEquals(CTA_BACK)
            onNodeWithText(CTA_CLOSE).assertDoesNotExist()
            onNodeWithTag(CHAT_THREAD_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(1, backCount)
        }
    }

    // #565: on a two-page thread the thread opens at the newest message without paging older first; reaching
    // the top loads the older page while the reader KEEPS their place (no jump back to the newest message),
    // and a pull at the very top still resyncs.
    @Test
    fun loadOlder_keepsTheScrollPosition_andAPullAtTheTopStillResyncs() {
        installKoin(
            historyOutcomes =
                listOf(ChatThreadOutcome.Loaded(newestPage(), "older-cursor"), ChatThreadOutcome.Loaded(olderPage(), null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_40").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(1, flow.loadHistoryCount, "opening the thread does not page older before it is positioned")

            // Hold the older page so the reader's position can be read before and after it lands.
            val olderGate = CompletableDeferred<Unit>()
            flow.historyGate = olderGate
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 2 }
            val listTop = onNodeWithTag(CHAT_THREAD_LIST_TAG).fetchSemanticsNode().positionInRoot.y
            val before = onNodeWithText("MSG_21").fetchSemanticsNode().positionInRoot.y
            olderGate.complete(Unit)
            waitForIdle()

            // The older page is prepended above; MSG_21 stays where the reader left it, the newest is off screen.
            onAllNodesWithText("MSG_21").assertCountEquals(1)
            assertEquals(before, onNodeWithText("MSG_21").fetchSemanticsNode().positionInRoot.y, "the reader keeps their place")
            assertTrue(before >= listTop, "MSG_21 is at the top of the viewport")
            onAllNodesWithText("MSG_40").assertCountEquals(0)

            // Scroll through the prepended page to the real top; there the pull still resyncs.
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitForIdle()
            onNodeWithText("MSG_1").assertExists()
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 3 }
        }
    }

    // #584 review: when the older page ends on an EARLIER day, the first newest-page row now starts a day. Its chip is
    // its own list item inserted above the reader's anchored row, so that row does not move (a chip drawn inside the
    // row's item pushed its bubble down by the chip's height).
    @Test
    fun loadOlder_fromAnEarlierDay_keepsTheScrollPosition_andHeadsTheNewDayWithAChip() {
        val day2 = "2026-06-02T10:21:00Z"
        val newestOnDay2 = (21..40).map { dto("m$it", content = "MSG_$it", createdAt = "2026-06-02T10:%02d:00Z".format(it)) }
        installKoin(
            historyOutcomes =
                listOf(ChatThreadOutcome.Loaded(newestOnDay2, "older-cursor"), ChatThreadOutcome.Loaded(olderPage(), null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_40").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()

            val olderGate = CompletableDeferred<Unit>()
            flow.historyGate = olderGate
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 2 }
            onAllNodesWithTag(CHAT_THREAD_DAY_CHIP_TAG, useUnmergedTree = true).assertCountEquals(0)
            val before = onNodeWithText("MSG_21").fetchSemanticsNode().positionInRoot.y
            olderGate.complete(Unit)
            waitForIdle()

            assertEquals(before, onNodeWithText("MSG_21").fetchSemanticsNode().positionInRoot.y, "the reader keeps their place")
            // Just above the anchored row: the new day's chip (chip(m1), m1..m20, chip(m21), m21, …).
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(21)
            waitForIdle()
            onNodeWithText(localDateLabel(day2)).assertExists()
        }
    }

    // #565 / #584 review: the tail still follows a NEW newest row while the reader is scrolled up in a long
    // thread — a realtime inbound brings the newest message into view.
    @Test
    fun realtimeInbound_whileScrolledUpInALongThread_bringsTheNewestRowIntoView() {
        installKoin(
            historyOutcomes =
                listOf(ChatThreadOutcome.Loaded(newestPage(), "older-cursor"), ChatThreadOutcome.Loaded(olderPage(), null)),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_40").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 2 }
            waitForIdle()
            onAllNodesWithText("MSG_40").assertCountEquals(0)
            waitUntil(timeoutMillis = 5_000) { realtime.active }

            runBlocking { realtime.emit(inbound(MSG_NEW_ID, content = "MSG_NEW", createdAt = "2026-06-01T11:00:00Z")) }

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_NEW").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("MSG_NEW").assertIsDisplayed()
        }
    }

    // #584 review: after process death the new ViewModel reloads only the newest page. A reader who had paged
    // older is re-pinned to the newest message (the restored index would point at a different message), and
    // the restored top position does not page older before that re-pin.
    @Test
    fun processDeath_afterPagingOlder_rePinsToTheNewestMessage() {
        installKoin(
            historyOutcomes =
                listOf(
                    ChatThreadOutcome.Loaded(newestPage(), "older-cursor"),
                    ChatThreadOutcome.Loaded(olderPage(), null),
                    ChatThreadOutcome.Loaded(newestPage(), "older-cursor"),
                ),
        )
        runComposeUiTest {
            val restore = restorableChatThread(processDeath = true)
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_40").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitUntil(timeoutMillis = 5_000) { flow.loadHistoryCount == 2 }
            waitForIdle()
            onNodeWithTag(CHAT_THREAD_LIST_TAG).performScrollToIndex(0)
            waitForIdle()
            onNodeWithText("MSG_1").assertExists()

            restore()

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("MSG_40").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithText("MSG_40").assertIsDisplayed()
            assertEquals(3, flow.loadHistoryCount, "the restored top position does not page older before the re-pin")
        }
    }

    // Frame 5 (#286): a Premium partner's header carries the shared Premium badge; a non-Premium one does not.
    @Test
    fun topBar_showsThePremiumBadgeOnlyForAPremiumPartner() {
        installKoin()
        runComposeUiTest {
            var premium by mutableStateOf(true)
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(partnerIsPremium = premium), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CHAT_THREAD_PREMIUM_BADGE_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CHAT_THREAD_PREMIUM_BADGE_TAG).assertContentDescriptionEquals(PREMIUM_BADGE)
            premium = false
            waitForIdle()
            onNodeWithTag(CHAT_THREAD_PREMIUM_BADGE_TAG).assertDoesNotExist()
        }
    }

    // mobile-chat § "Shadow-banned/deleted partner top-bar is masked": the BACKEND mask shape (displayName
    // "Akun Dihapus", username "akun_dihapus") renders the placeholder with no "@akun_dihapus" handle or badge.
    @Test
    fun backendMaskedPartner_topBarRendersThePlaceholder_withoutTheMaskHandleOrBadge() {
        installKoin()
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        ChatThreadScreen(
                            route = route(partnerDisplayName = "Akun Dihapus", partnerUsername = "akun_dihapus", partnerIsPremium = true),
                            onBack = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(DELETED).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("@akun_dihapus").assertDoesNotExist()
            onNodeWithTag(CHAT_THREAD_PREMIUM_BADGE_TAG).assertDoesNotExist()
        }
    }

    // Frame 5 (#286): each bubble shows its send time ("09.12"), and a day chip heads the first row of each
    // day: the date for an older day, "Kemarin", then "Hari ini". A phone-height screen keeps all three rows composed.
    // The screen's clock is pinned, so the labels cannot flip if the run crosses local midnight.
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun bubblesShowTheirSendTime_andDayChipsHeadEachDay() {
        val now = Instant.parse("2026-06-03T05:00:00Z")
        val clock =
            object : Clock {
                override fun now() = now
            }
        val older = (now - 3.days).toString()
        val yesterday = (now - 1.days).toString()
        val today = now.toString()
        installKoin(
            ChatThreadOutcome.Loaded(
                listOf(
                    dto("m1", content = "LAMA", createdAt = older),
                    dto("m2", content = "KEMARIN", createdAt = yesterday),
                    dto("m3", sender = VIEWER, content = "BARU", createdAt = today),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}, clock = clock) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("BARU").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(
                listOf(localDateLabel(older), "Kemarin", "Hari ini"),
                onAllNodesWithTag(CHAT_THREAD_DAY_CHIP_TAG, useUnmergedTree = true).texts(),
            )
            assertEquals(
                listOf(older, yesterday, today).map { localTimeLabel(it) },
                onAllNodesWithTag(CHAT_THREAD_MESSAGE_TIME_TAG, useUnmergedTree = true).texts(),
            )
        }
    }

    private fun SemanticsNodeInteractionCollection.texts(): List<String> =
        fetchSemanticsNodes().map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun inbound(
        id: String,
        content: String,
        createdAt: String,
    ) = ChatMessageInbound(
        id = Uuid.parse(id),
        conversationId = Uuid.parse(CONV),
        senderId = Uuid.parse(OTHER),
        content = content,
        createdAt = Instant.parse(createdAt),
        redactedAt = null,
    )

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

    // chat-embed-live-edit-banner: the per-card live read drives the banner; the card still shows the snapshot.
    @Test
    fun sharedPostEditedAfterTheShare_showsTheBanner_anUnchangedOneDoesNot() {
        installKoin(
            ChatThreadOutcome.Loaded(
                listOf(
                    embedDto("m1", postId = "post-edited", content = "EDITED_POST"),
                    embedDto("m2", postId = "post-unchanged", content = "UNCHANGED_POST", createdAt = "2026-06-01T10:01:00Z"),
                ),
                null,
            ),
            postEdits =
                FakePostEditFlow(
                    refreshOutcomesByPost =
                        mapOf(
                            "post-edited" to PostRefreshOutcome.Loaded("EDITED_POST v2", "2026-06-02T09:00:00Z", isAuthor = false),
                            "post-unchanged" to PostRefreshOutcome.Loaded("UNCHANGED_POST", SHARED_EDITED_AT, isAuthor = false),
                        ),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ChatThreadScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(EDITED_BANNER).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(EMBEDDED_POST_EDITED_BANNER_TAG, useUnmergedTree = true).assertCountEquals(1)
            onNode(hasTestTag(EMBEDDED_POST_CARD_TAG) and hasText("EDITED_POST") and hasText(EDITED_BANNER)).assertExists()
            onNode(hasTestTag(EMBEDDED_POST_CARD_TAG) and hasText("UNCHANGED_POST")).assertExists()
            // The card renders the share-time snapshot, never the live content.
            onNodeWithText("EDITED_POST v2").assertDoesNotExist()
        }
    }
}
