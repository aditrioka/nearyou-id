package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditOutcome
import id.nearyou.app.screens.paywall.FakePurchaseController
import id.nearyou.app.screens.routing.EditPostRoute
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PaywallRoute
import id.nearyou.app.screens.routing.TestNavHost
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

private const val CTA_CANCEL = "Batal" // cta_cancel

/**
 * Render + interaction coverage of `EditPostScreen` (task 7.3), driven by `FakePostEditFlow`: the editor
 * is prefilled with the current content + the Save action gates on non-empty content; a success invokes
 * `onPostEdited` with the updated content (the caller pops); a `403` raises the "Aktifkan Premium" upsell
 * (the reactive gate, NOT an error); a non-premium failure renders the inline error banner. The
 * outcome→UiState projection is covered purely by `EditPostViewModelTest`. In the Release-variant exclude.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class EditPostScreenTest {
    private fun installKoin(editFlow: PostEditFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        // PurchaseController: the host-push test navigates onward to PaywallRoute (Unconfigured fail-soft).
        startKoin {
            modules(
                module {
                    single { editFlow }
                    single<PurchaseController> { FakePurchaseController(OfferingsResult.Unavailable) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun route(initialContent: String = "isi awal") = EditPostRoute(postId = "p1", initialContent = initialContent)

    @Test
    fun editor_isPrefilled_andSaveEnabledForNonEmptyContent() {
        installKoin(FakePostEditFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_FIELD_TAG).assertTextEquals("isi awal")
            onNodeWithTag(EDIT_POST_SAVE_TAG).assertIsEnabled()
        }
    }

    @Test
    fun emptyInitialContent_disablesSave() {
        installKoin(FakePostEditFlow())
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        EditPostScreen(
                            route = route(initialContent = ""),
                            onBack = {},
                            onPostEdited = {},
                        )
                    }
                }
            }
            onNodeWithTag(EDIT_POST_SAVE_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun submit_success_invokesOnPostEditedWithUpdatedContent() {
        var edited: String? = null
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.Success("isi baru")))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = { edited = it }) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { edited != null }
            assertEquals("isi baru", edited)
        }
    }

    @Test
    fun submit_premiumRequired_showsActivatePremiumUpsell() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).assertExists()
        }
    }

    // cap-upsell-parity (mobile-post-editing § Premium gating): the upsell CTA dismisses AND invokes the
    // hoisted onActivatePremium exactly once — no longer a dismiss-only dead end.
    @Test
    fun premiumUpsellCta_invokesOnActivatePremiumOnce_andDismisses() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired))
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}, onActivatePremium = { activated++ }) }
                }
            }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).performClick()
            waitForIdle()
            assertEquals(1, activated, "the CTA opens the paywall via the host")
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun premiumUpsellDismiss_doesNotOpenThePaywall() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired))
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}, onActivatePremium = { activated++ }) }
                }
            }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(CTA_CANCEL).performClick()
            waitForIdle()
            assertEquals(0, activated, "dismiss only dismisses")
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).assertDoesNotExist()
        }
    }

    // The host-push half under the REAL appEntryProvider: the edit gate pushes PaywallRoute(EDIT_GATE).
    @Test
    fun premiumUpsellCta_underHost_pushesPaywallRouteEditGate() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired))
        lateinit var backStack: NavBackStack<NavKey>
        runComposeUiTest {
            setContent { KoinContext { TestNavHost(route(), onBackStack = { backStack = it }) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(EDIT_POST_SAVE_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(EDIT_POST_PREMIUM_CTA_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_PREMIUM_CTA_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { backStack.last() is PaywallRoute }
            assertEquals(PaywallRoute(PaywallEntry.EDIT_GATE), backStack.last())
        }
    }

    @Test
    fun submit_windowExpired_showsInlineError() {
        installKoin(FakePostEditFlow(editOutcome = PostEditOutcome.WindowExpired))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { EditPostScreen(route = route(), onBack = {}, onPostEdited = {}) } } }
            onNodeWithTag(EDIT_POST_SAVE_TAG).performClick()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_POST_ERROR_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_POST_ERROR_TAG).assertExists()
        }
    }
}
