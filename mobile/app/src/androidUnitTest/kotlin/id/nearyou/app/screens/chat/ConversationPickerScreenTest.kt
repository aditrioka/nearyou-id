package id.nearyou.app.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
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
import id.nearyou.app.chat.FakeConversationsFlow
import id.nearyou.app.chat.PartnerProfileDto
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.screens.paywall.FakePurchaseController
import id.nearyou.app.screens.routing.ConversationPickerRoute
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PaywallRoute
import id.nearyou.app.screens.routing.TestNavHost
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_CLOSE_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
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

private const val POST_ID = "11111111-1111-1111-1111-777777777777"
private const val SHARE_FAILED = "Gagal membagikan. Coba lagi." // chat_share_failed

// chat_cap_upsell with the cap dialog's 3600s countdown ("1 j 0 mnt").
private const val CHAT_CAP_1H =
    "Kamu sudah mengirim 50 pesan hari ini. Upgrade ke Premium untuk chat tanpa batas, atau tunggu reset dalam 1 j 0 mnt."

/**
 * cap-upsell-parity (`mobile-chat-embedded-posts` § The conversation picker): a share is a chat send, so the
 * Free 50/day cap's `429` shows the shared frame-18 cap dialog — NOT the generic failed snackbar — and its
 * "Aktifkan Premium" opens the paywall as `CHAT_CAP`. The share → thread navigation + blocked/failed results
 * are covered by `ConversationPickerViewModelTest`.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ConversationPickerScreenTest {
    private fun installKoin(sendOutcome: SendOutcome) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val row =
            ConversationListItemDto(
                conversation = ConversationDto(id = "conv-1", createdAt = "2026-06-01T09:00:00Z", lastMessageAt = null),
                partner = PartnerProfileDto(id = "ignored", username = "raka", displayName = "Raka", isPremium = false),
            )
        startKoin {
            modules(
                module {
                    single<ConversationsFlow> { FakeConversationsFlow(outcome = ConversationListOutcome.Loaded(listOf(row), null)) }
                    single<ChatFlow> { FakeChatFlow(sendOutcome = sendOutcome) }
                    // The host-push test navigates onward to PaywallRoute (Unconfigured fail-soft state).
                    single<PurchaseController> { FakePurchaseController(OfferingsResult.Unavailable) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun rateLimitedShare_showsChatCapDialog_notTheFailedSnackbar() {
        installKoin(SendOutcome.RateLimited(retryAfterSeconds = 3_600))
        var activated = 0
        var navigated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        ConversationPickerScreen(
                            postId = POST_ID,
                            onBack = {},
                            onSelectConversation = { _, _, _ -> navigated++ },
                            onActivatePremium = { activated++ },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_PICKER_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CONVERSATION_PICKER_ROW_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(CHAT_CAP_1H).assertExists()
            onNodeWithText(SHARE_FAILED).assertDoesNotExist()
            assertEquals(0, navigated, "a capped share never opens the thread")
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitForIdle()
            assertEquals(1, activated, "the CTA opens the paywall via the host")
            onNodeWithTag(DAILY_CAP_DIALOG_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun chatCapDialog_tutupDismissesWithoutOpeningThePaywall() {
        installKoin(SendOutcome.RateLimited(retryAfterSeconds = 3_600))
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        ConversationPickerScreen(
                            postId = POST_ID,
                            onBack = {},
                            onSelectConversation = { _, _, _ -> },
                            onActivatePremium = { activated++ },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_PICKER_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CONVERSATION_PICKER_ROW_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DAILY_CAP_DIALOG_CLOSE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(DAILY_CAP_DIALOG_TAG).assertDoesNotExist()
            assertEquals(0, activated, "Tutup only dismisses")
        }
    }

    // The host-push half under the REAL appEntryProvider: the picker's cap CTA pushes PaywallRoute(CHAT_CAP).
    @Test
    fun chatCapCta_underHost_pushesPaywallRouteChatCap() {
        installKoin(SendOutcome.RateLimited(retryAfterSeconds = 3_600))
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(ConversationPickerRoute(POST_ID), onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CONVERSATION_PICKER_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(CONVERSATION_PICKER_ROW_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(DAILY_CAP_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is PaywallRoute }
            assertEquals(PaywallRoute(PaywallEntry.CHAT_CAP), backStack.last())
        }
    }
}
