package id.nearyou.app.screens.paywall

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "NearYouID Premium"
private const val UNAVAILABLE_TITLE = "Premium belum tersedia"

/**
 * iOS counterpart to the Robolectric [PaywallScreenTest] — the paywall run natively on the iOS simulator
 * (`:mobile:app:iosSimulatorArm64Test`), proving the `PaywallRoute` + the `:infra:revenuecat`
 * `PurchaseController` data seam compile + run on Kotlin/Native (the universal per-screen `*FlowIosTest`
 * convention). Focused on the two surfaces this change adds: the Content render (offerings loaded) and the
 * fail-soft Unconfigured state. Reuses the commonTest [FakePurchaseController]; the VM init load completes
 * via the default auto-advancing clock.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PaywallFlowIosTest {
    private fun installKoin(offerings: OfferingsResult) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val fake = FakePurchaseController(offerings = offerings)
        startKoin { modules(module { single<PurchaseController> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun content_showsHeroAndCta() {
        installKoin(
            OfferingsResult.Loaded(
                listOf(
                    pkg(PaywallPeriod.WEEKLY, "Rp9.900", 9_900_000_000L),
                    pkg(PaywallPeriod.MONTHLY, "Rp29.000", 29_000_000_000L),
                    pkg(PaywallPeriod.YEARLY, "Rp249.000", 249_000_000_000L),
                ),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}) } } }
            waitForIdle()
            onNodeWithText(TITLE).assertExists()
            onNodeWithTag(PAYWALL_CTA_TAG).assertExists()
        }
    }

    @Test
    fun unavailableOfferings_showUnconfigured() {
        installKoin(OfferingsResult.Unavailable)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.SEARCH_GATE, onClose = {}) } } }
            waitForIdle()
            onNodeWithTag(PAYWALL_UNCONFIGURED_TAG).assertExists()
            onNodeWithText(UNAVAILABLE_TITLE).assertExists()
        }
    }

    private fun pkg(
        period: PaywallPeriod,
        price: String,
        micros: Long,
    ): PaywallPackage =
        PaywallPackage(
            period = period,
            localizedPriceString = price,
            priceAmountMicros = micros,
            currencyCode = "IDR",
            productId = "p_${period.name}",
        )
}
