package id.nearyou.app.screens.paywall

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.billing.MutableSelfUserId
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.PurchaseResult
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "NearYouID Premium"
private const val UNAVAILABLE_TITLE = "Premium belum tersedia"
private const val SUBHEAD_CHAT_CAP = "Kirim pesan tanpa batas" // cap-upsell-parity: paywall_subhead_chat_cap
private const val SUBHEAD_TIMELINE_CAP = "Baca timeline tanpa batas" // #516: paywall_subhead_timeline_cap
private const val PENDING = "Pembayaran sedang diproses. Premium aktif setelah pembayaran dikonfirmasi."

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
    private fun installKoin(
        offerings: OfferingsResult,
        purchaseResult: PurchaseResult = PurchaseResult.Success(entitlementActive = true),
        premiumActive: Boolean = false,
    ): PremiumEntitlementSession {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val fake = FakePurchaseController(offerings = offerings, purchaseResult = purchaseResult, premiumActive = premiumActive)
        val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
        startKoin {
            modules(
                module {
                    single<PurchaseController> { fake }
                    single { session }
                },
            )
        }
        return session
    }

    // premium-entitlement-lifecycle: an inactive entitlement that confirms on the recheck returns + publishes
    // the confirmed signal, on Kotlin/Native.
    @Test
    fun inactiveEntitlementConfirmedOnRecheck_returnsAndPublishesTheSignal() {
        val session =
            installKoin(loadedOfferings(), purchaseResult = PurchaseResult.Success(entitlementActive = false), premiumActive = true)
        runComposeUiTest {
            var completed = 0
            setContent {
                KoinContext {
                    NearYouTheme {
                        PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}, onPurchaseComplete = { completed++ })
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(PAYWALL_CTA_TAG).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { completed == 1 }
            assertTrue(session.purchaseConfirmed.value)
        }
    }

    @Test
    fun paymentPendingPurchase_showsThePendingCopyWithoutReturning() {
        val session = installKoin(loadedOfferings(), purchaseResult = PurchaseResult.Pending)
        runComposeUiTest {
            var completed = 0
            setContent {
                KoinContext {
                    NearYouTheme {
                        PaywallScreen(entry = PaywallEntry.LIKE_CAP, onClose = {}, onPurchaseComplete = { completed++ })
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(PAYWALL_CTA_TAG).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(PENDING).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PAYWALL_PENDING_TAG).assertExists()
            assertEquals(0, completed)
            assertFalse(session.purchaseConfirmed.value)
        }
    }

    private fun loadedOfferings(): OfferingsResult.Loaded =
        OfferingsResult.Loaded(
            listOf(
                pkg(PaywallPeriod.WEEKLY, "Rp9.900", 9_900_000_000L),
                pkg(PaywallPeriod.MONTHLY, "Rp29.000", 29_000_000_000L),
                pkg(PaywallPeriod.YEARLY, "Rp249.000", 249_000_000_000L),
            ),
        )

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

    // cap-upsell-parity: a new cap entry renders its own hero headline on Kotlin/Native too.
    @Test
    fun chatCapEntry_showsItsOwnHeadline() {
        installKoin(OfferingsResult.Loaded(listOf(pkg(PaywallPeriod.MONTHLY, "Rp29.000", 29_000_000_000L))))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.CHAT_CAP, onClose = {}) } } }
            waitForIdle()
            onNodeWithText(SUBHEAD_CHAT_CAP).assertExists()
        }
    }

    // #516: the read-cap entry renders its own hero headline on Kotlin/Native.
    @Test
    fun timelineCapEntry_showsItsOwnHeadline() {
        installKoin(OfferingsResult.Loaded(listOf(pkg(PaywallPeriod.MONTHLY, "Rp29.000", 29_000_000_000L))))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PaywallScreen(entry = PaywallEntry.TIMELINE_CAP, onClose = {}) } } }
            waitForIdle()
            onNodeWithText(SUBHEAD_TIMELINE_CAP).assertExists()
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
