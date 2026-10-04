package id.nearyou.app.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.billing.confirmedPremiumSession
import id.nearyou.app.screens.startFlowTestKoin
import id.nearyou.app.screens.stopFlowTestKoin
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * iOS counterpart of the activating-notice coverage (`mobile-premium-entitlement`, #517): on Kotlin/Native
 * the shared cap dialog resolves the confirmed session through its defaulted `premiumActivating` (the
 * Koin lookup in `rememberPremiumActivating`) and renders the activating notice instead of the upsell.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PremiumActivatingIosTest {
    @AfterTest
    fun tearDown() = stopFlowTestKoin()

    @Test
    fun capDialog_withAConfirmedSession_rendersTheActivatingNotice() {
        startFlowTestKoin(module { single { confirmedPremiumSession() } })
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        DailyCapUpsellDialog(
                            retryAfterSeconds = 1_140,
                            body = { it },
                            onDismiss = {},
                            onActivatePremium = {},
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Premium sedang diaktifkan").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Batas harian tercapai").assertDoesNotExist()
            onNodeWithText("Aktifkan Premium").assertDoesNotExist()
        }
    }
}
