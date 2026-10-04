package id.nearyou.app.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.billing.confirmedPremiumSession
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TITLE = "Premium sedang diaktifkan" // premium_activating_title
private const val BODY = "Pembelianmu berhasil dan Premium sedang diaktifkan. Coba lagi sebentar lagi ya." // premium_activating_body
private const val RADIUS_TITLE = "Radius khusus Premium" // radius_upsell_title

/**
 * The webhook-lag activating notice (`mobile-premium-entitlement`, #517): the shared [PremiumActivatingDialog]
 * renders its copy and a single "Tutup" with no paywall CTA, and [RadiusPremiumUpsellDialog] swaps to it
 * while `premiumActivating`. Composed with no Koin started, so the radius dialog gets the flag explicitly —
 * except the default-path test, which binds a confirmed session in Koin and passes nothing.
 *
 * `@Suppress("DEPRECATION")`: the v1 `runComposeUiTest` API the sibling component tests use (docs/11 § 2.7).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class PremiumActivatingDialogTest {
    @Test
    fun rendersTheActivatingCopy_andASingleTutupThatDismissesOnce() =
        runComposeUiTest {
            var dismissed = 0
            setContent { NearYouTheme(darkTheme = false) { PremiumActivatingDialog(onDismiss = { dismissed++ }) } }
            onNodeWithTag(PREMIUM_ACTIVATING_DIALOG_TAG).assertExists()
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(BODY).assertExists()
            onNodeWithText("Aktifkan Premium").assertDoesNotExist()
            onNodeWithTag(PREMIUM_ACTIVATING_DIALOG_CLOSE_TAG).performClick()
            assertEquals(1, dismissed)
        }

    @Test
    fun rendersUnderTheDarkScheme() =
        runComposeUiTest {
            setContent { NearYouTheme(darkTheme = true) { PremiumActivatingDialog(onDismiss = {}) } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(BODY).assertExists()
        }

    @Test
    fun radiusUpsell_whileActivating_showsTheNotice_andNeverReachesThePaywall() =
        runComposeUiTest {
            var dismissed = 0
            var premium = 0
            setContent {
                NearYouTheme {
                    RadiusPremiumUpsellDialog(
                        onDismiss = { dismissed++ },
                        onActivatePremium = { premium++ },
                        premiumActivating = true,
                    )
                }
            }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(BODY).assertExists()
            onNodeWithText(RADIUS_TITLE).assertDoesNotExist()
            onNodeWithTag(RADIUS_UPSELL_DIALOG_PREMIUM_TAG).assertDoesNotExist()
            onNodeWithTag(PREMIUM_ACTIVATING_DIALOG_CLOSE_TAG).performClick()
            assertEquals(1, dismissed, "Tutup fires onDismiss exactly once")
            assertEquals(0, premium, "the activating notice never reaches the paywall callback")
        }

    @Test
    fun radiusUpsell_whenNotActivating_keepsTheUpsell() =
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    RadiusPremiumUpsellDialog(onDismiss = {}, onActivatePremium = {}, premiumActivating = false)
                }
            }
            onNodeWithText(RADIUS_TITLE).assertExists()
            onNodeWithTag(RADIUS_UPSELL_DIALOG_PREMIUM_TAG).assertExists()
            onNodeWithText(TITLE).assertDoesNotExist()
        }

    // The default path: no explicit premiumActivating — the dialog resolves a confirmed session from Koin
    // itself (rememberPremiumActivating), so the Nearby host needs no wiring (#517).
    @Test
    fun radiusUpsell_resolvesAConfirmedSessionByDefault() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { single { confirmedPremiumSession() } }) }
        try {
            runComposeUiTest {
                setContent { KoinContext { NearYouTheme { RadiusPremiumUpsellDialog(onDismiss = {}, onActivatePremium = {}) } } }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag(RADIUS_UPSELL_DIALOG_PREMIUM_TAG).assertDoesNotExist()
            }
        } finally {
            stopKoin()
        }
    }
}
