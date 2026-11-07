package id.nearyou.app.screens.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ConversationDto
import id.nearyou.app.chat.ConversationListItemDto
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.chat.FakeChatFlow
import id.nearyou.app.chat.FakeChatRealtimeSubscriber
import id.nearyou.app.chat.FakeConversationsFlow
import id.nearyou.app.chat.PartnerProfileDto
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.notifications.FakeNotificationPermissionController
import id.nearyou.app.notifications.FakeNotificationPromptOneShot
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionStatus
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.screens.routing.ConversationListRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.localDateLabel
import id.nearyou.app.ui.components.localTimeLabel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private const val TITLE = "Pesan"
private const val EMPTY = "Belum ada percakapan. Mulai dari profil seseorang."
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val SESSION_REDIRECT = "Mengalihkan ke halaman masuk…"
private const val DELETED = "Akun Dihapus"
private const val RETRY = "Coba lagi"
private const val PARTNER_UUID = "11111111-1111-1111-1111-111111111111"

private const val PREMIUM_BADGE = "Akun Premium"
private const val CTA_BACK = "Kembali"
private const val CTA_CLOSE = "Tutup"

private fun item(
    id: String,
    displayName: String = "Dewi Lestari",
    lastMessageAt: String? = "2026-06-02T11:00:00Z",
    username: String = "dewi.kuliner",
    isPremium: Boolean = false,
) = ConversationListItemDto(
    conversation = ConversationDto(id = id, createdAt = "2026-06-01T10:00:00Z", lastMessageAt = lastMessageAt),
    partner = PartnerProfileDto(id = PARTNER_UUID, username = username, displayName = displayName, isPremium = isPremium),
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
        startKoin {
            modules(
                module {
                    single<ConversationsFlow> { fake }
                    // The host test lands on the chat thread after a row tap.
                    single<ChatFlow> { FakeChatFlow() }
                    single<ChatRealtimeSubscriber> { FakeChatRealtimeSubscriber() }
                    single<ViewerIdProvider> { ViewerIdProvider { "viewer" } }
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<PostEditFlow> { FakePostEditFlow() }
                    single<NotificationPermissionController> {
                        FakeNotificationPermissionController(NotificationPermissionStatus.GRANTED)
                    }
                    single<NotificationPromptOneShot> { FakeNotificationPromptOneShot(claimed = true) }
                },
            )
        }
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

    // Under the REAL appEntryProvider a row tap pushes the thread with the row's display identity AND its Premium flag
    // (the thread header badge, #286).
    @Test
    fun rowTap_underHost_pushesTheThreadRouteWithThePartnerPremiumFlag() {
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1", isPremium = true)), null))
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(ConversationListRoute, onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CONVERSATION_ROW_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is ChatThreadRoute }
            assertEquals(ChatThreadRoute("c1", "dewi.kuliner", "Dewi Lestari", partnerIsPremium = true), backStack.last())
        }
    }

    @Test
    fun deletedPartner_rendersAkunDihapusPlaceholder() {
        // A blank display name (a stale/edge row) renders the chat_account_deleted placeholder, with no handle
        // or Premium badge beside it.
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1", displayName = "", isPremium = true)), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(DELETED).assertExists()
            onNodeWithText("@dewi.kuliner").assertDoesNotExist()
            onNodeWithContentDescription(PREMIUM_BADGE).assertDoesNotExist()
        }
    }

    // mobile-chat § "Deleted/shadow-banned partner renders the placeholder": the BACKEND mask shape
    // (displayName "Akun Dihapus", username "akun_dihapus") is a masked row — no "@akun_dihapus" handle, no badge.
    @Test
    fun backendMaskedPartner_rendersThePlaceholder_withoutTheMaskHandleOrBadge() {
        installKoin(
            ConversationListOutcome.Loaded(
                listOf(item("c1", displayName = "Akun Dihapus", username = "akun_dihapus", isPremium = true)),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(DELETED).assertExists()
            onNodeWithText("@akun_dihapus").assertDoesNotExist()
            onNodeWithContentDescription(PREMIUM_BADGE).assertDoesNotExist()
        }
    }

    // mobile-chat § rows render "the relative last_message_at" (frame 2: "09.12" today, "Kemarin", the short
    // weekday for the rest of the week, then the date). The screen's clock is pinned, so the labels cannot flip if
    // the run crosses local midnight.
    @Test
    fun rowTime_isRelative_clockTimeToday_kemarin_weekday_thenTheDate() {
        val now = Instant.parse("2026-06-03T05:00:00Z")
        val clock =
            object : Clock {
                override fun now() = now
            }
        val today = now.toString()
        val yesterday = (now - 1.days).toString()
        val thisWeek = (now - 3.days).toString()
        val older = (now - 30.days).toString()
        installKoin(
            ConversationListOutcome.Loaded(
                listOf(
                    item("c1", lastMessageAt = today),
                    item("c2", lastMessageAt = yesterday),
                    item("c3", lastMessageAt = thisWeek),
                    item("c4", lastMessageAt = older),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}, clock = clock) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().size == 4 }
            val weekday =
                listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")[
                    Instant.parse(thisWeek).toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek.isoDayNumber - 1,
                ]
            assertEquals(
                listOf(localTimeLabel(today), "Kemarin", weekday, localDateLabel(older)),
                onAllNodesWithTag(CONVERSATION_ROW_TIME_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                    node.config[SemanticsProperties.Text].joinToString("") { it.text }
                },
            )
        }
    }

    // mobile-chat § "Conversation rows render partner display identity": the row shows the username and the
    // isPremium badge, not only the display name (#286).
    @Test
    fun contentRow_rendersUsernameAndThePremiumBadgeOnlyForPremiumPartners() {
        installKoin(
            ConversationListOutcome.Loaded(
                listOf(
                    item("c1", isPremium = true),
                    item("c2", displayName = "Raka Pratama", username = "raka.jkt", isPremium = false),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = {}, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().size == 2 }
            onNodeWithText("@dewi.kuliner").assertExists()
            onNodeWithText("@raka.jkt").assertExists()
            onAllNodesWithContentDescription(PREMIUM_BADGE).assertCountEquals(1)
        }
    }

    // Frame 2 (#286): the top bar leads with a back ARROW (described "Kembali"), not a "Tutup" text button.
    @Test
    fun backArrow_invokesOnBack() {
        installKoin(ConversationListOutcome.Loaded(listOf(item("c1")), null))
        var backCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ConversationListScreen(onBack = { backCount++ }, onOpenThread = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CONVERSATION_LIST_BACK_TAG).assertContentDescriptionEquals(CTA_BACK)
            onNodeWithText(CTA_CLOSE).assertDoesNotExist()
            onNodeWithTag(CONVERSATION_LIST_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(1, backCount)
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
