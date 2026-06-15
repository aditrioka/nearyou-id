package id.nearyou.app.screens.paywall

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.PurchaseResult
import id.nearyou.app.screens.routing.PaywallEntry
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "NearYouID Premium"
private const val SUBHEAD_LIKE_CAP = "Like, balas & posting tanpa batas"
private const val SUBHEAD_SEARCH = "Cari postingan di seluruh Indonesia"
private const val BENEFIT_UNLIMITED = "Post, balasan & like tanpa batas"
private const val BENEFIT_RADIUS = "Radius Sekitar 10/20/50/100 km"
private const val CTA = "Aktifkan Premium"
private const val DISCLOSURE = "Fitur Premium dapat berubah atau ditambahkan seiring waktu."
private const val UNAVAILABLE_TITLE = "Premium belum tersedia"
private const val PRICE_WEEKLY = "Rp9.900"
private const val PRICE_MONTHLY = "Rp29.000"
private const val PRICE_YEARLY = "Rp249.000"

/**
 * Render + behavior coverage of `PaywallScreen` (frame 17) via the Robolectric-backed CMP runner, driven
 * through a [FakePurchaseController] (the pure offerings→pricing projection is covered by
 * `PaywallPricingTest`; the VM transitions by `PaywallViewModelTest`). Covers the Content surface
 * (hero / benefits / 3 cards with SDK-localized prices / CTA / disclosure), the fail-soft Unconfigured
 * state, the close affordance, the entry-context hero tailoring, and the purchase-success → return.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class PaywallScreenTest {
    private lateinit var fake: FakePurchaseController

    private fun installKoin(
        offerings: OfferingsResult = loadedOfferings(),
        purchaseResult: PurchaseResult = PurchaseResult.Success(entitlementActive = true),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake = FakePurchaseController(offerings = offerings, purchaseResult = purchaseResult)
        startKoin { modules(module { single<PurchaseController> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun content_rendersHeroBenefitsCardsCtaAndDisclosure() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}) } } }
            onNodeWithTag(PAYWALL_SCREEN_TAG).assertExists()
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(BENEFIT_UNLIMITED).assertExists()
            onNodeWithText(BENEFIT_RADIUS).assertExists()
            // The three cards render their SDK-localized prices (never hardcoded).
            onNodeWithText(PRICE_WEEKLY).assertExists()
            onNodeWithText(PRICE_MONTHLY).assertExists()
            onNodeWithText(PRICE_YEARLY).assertExists()
            onNodeWithTag(PAYWALL_CTA_TAG).assertExists()
            onNodeWithText(DISCLOSURE).assertExists()
        }
    }

    @Test
    fun unavailableOfferings_renderTheUnconfiguredState() {
        installKoin(offerings = OfferingsResult.Unavailable)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}) } } }
            onNodeWithTag(PAYWALL_UNCONFIGURED_TAG).assertExists()
            onNodeWithText(UNAVAILABLE_TITLE).assertExists()
            // No purchasable price card / CTA in the fail-soft state.
            onAllNodesWithText(PRICE_MONTHLY).assertCountEquals(0)
        }
    }

    @Test
    fun closeAffordance_invokesOnClose() {
        installKoin()
        runComposeUiTest {
            var closed = 0
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = { closed++ }) } } }
            onNodeWithTag(PAYWALL_CLOSE_TAG).performClick()
            waitForIdle()
            assertEquals(1, closed, "the close affordance invokes the hoisted onClose")
        }
    }

    @Test
    fun heroSubheadline_tailoredToLikeCapEntry() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}) } } }
            onNodeWithText(SUBHEAD_LIKE_CAP).assertExists()
            onAllNodesWithText(SUBHEAD_SEARCH).assertCountEquals(0)
        }
    }

    @Test
    fun heroSubheadline_tailoredToSearchGateEntry() {
        installKoin()
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.SEARCH_GATE, onClose = {}) } } }
            onNodeWithText(SUBHEAD_SEARCH).assertExists()
            onAllNodesWithText(SUBHEAD_LIKE_CAP).assertCountEquals(0)
        }
    }

    @Test
    fun subscribeCta_drivesThePurchaseOnTheController() {
        installKoin(purchaseResult = PurchaseResult.Success(entitlementActive = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}) } } }
            // The CTA is below the scroll fold; scroll it into view before clicking. The purchase runs on
            // viewModelScope (Main); waitUntil pumps the looper until the controller records the call. (The
            // success → onPurchaseComplete return signal is covered by PaywallViewModelTest.)
            onNodeWithTag(PAYWALL_CTA_TAG).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { fake.purchaseInvocations == 1 }
            assertEquals(1, fake.purchaseInvocations, "the subscribe CTA drives PurchaseController.purchase exactly once")
        }
    }

    private fun loadedOfferings(): OfferingsResult.Loaded =
        OfferingsResult.Loaded(
            listOf(
                pkg(PaywallPeriod.WEEKLY, PRICE_WEEKLY, 9_900_000_000L),
                pkg(PaywallPeriod.MONTHLY, PRICE_MONTHLY, 29_000_000_000L),
                pkg(PaywallPeriod.YEARLY, PRICE_YEARLY, 249_000_000_000L),
            ),
        )

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
