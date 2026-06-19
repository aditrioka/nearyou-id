package id.nearyou.app.screens.paywall

import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the pure frame-17 price-derivation ([derivePaywallPricing]): anchors (Monthly = 4× Weekly,
 * Yearly = 12× Monthly), rounded savings %, Weekly per-day, default-selected Monthly, and the
 * degenerate-input guards. Micros use the Rupiah scale (price × 1,000,000) matching the docs/01 target
 * figures, so the 27% / 28% expectations mirror the mockup.
 */
class PaywallPricingTest {
    private val weekly = pkg(PaywallPeriod.WEEKLY, 9_900_000_000L, "Rp9.900")
    private val monthly = pkg(PaywallPeriod.MONTHLY, 29_000_000_000L, "Rp29.000")
    private val yearly = pkg(PaywallPeriod.YEARLY, 249_000_000_000L, "Rp249.000")

    @Test
    fun weeklyCarriesPerDayBaselineAndNoAnchor() {
        val w = derivePaywallPricing(listOf(weekly, monthly, yearly)).single { it.period == PaywallPeriod.WEEKLY }
        assertEquals(9_900_000_000L / 7, w.perDayMicros)
        assertNull(w.anchorMicros)
        assertNull(w.savingsPercent)
    }

    @Test
    fun monthlyAnchorIsFourWeeksWithRoundedSavings() {
        val m = derivePaywallPricing(listOf(weekly, monthly, yearly)).single { it.period == PaywallPeriod.MONTHLY }
        assertEquals(9_900_000_000L * 4, m.anchorMicros)
        assertEquals(27, m.savingsPercent)
        assertNull(m.perDayMicros)
    }

    @Test
    fun yearlyAnchorIsTwelveMonthsWithRoundedSavings() {
        val y = derivePaywallPricing(listOf(weekly, monthly, yearly)).single { it.period == PaywallPeriod.YEARLY }
        assertEquals(29_000_000_000L * 12, y.anchorMicros)
        assertEquals(28, y.savingsPercent)
    }

    @Test
    fun defaultSelectedPeriodIsMonthly() {
        assertEquals(PaywallPeriod.MONTHLY, DEFAULT_SELECTED_PERIOD)
    }

    @Test
    fun missingWeeklyYieldsNullMonthlyAnchorNotCrash() {
        val cards = derivePaywallPricing(listOf(monthly, yearly))
        val m = cards.single { it.period == PaywallPeriod.MONTHLY }
        assertNull(m.anchorMicros)
        assertNull(m.savingsPercent)
        // Yearly still anchors off Monthly — the missing Weekly only drops Monthly's anchor.
        assertEquals(29_000_000_000L * 12, cards.single { it.period == PaywallPeriod.YEARLY }.anchorMicros)
    }

    @Test
    fun invertedOrZeroPriceYieldsNullSavingsNotNegative() {
        // Monthly priced ABOVE 4× Weekly (degenerate) → no savings, never a negative percent.
        val pricey = pkg(PaywallPeriod.MONTHLY, 50_000_000_000L, "Rp50.000")
        val m = derivePaywallPricing(listOf(weekly, pricey)).single { it.period == PaywallPeriod.MONTHLY }
        assertNull(m.savingsPercent)
    }

    @Test
    fun cardOrderFollowsInputOrder() {
        val cards = derivePaywallPricing(listOf(weekly, monthly, yearly))
        assertEquals(
            listOf(PaywallPeriod.WEEKLY, PaywallPeriod.MONTHLY, PaywallPeriod.YEARLY),
            cards.map { it.period },
        )
    }

    private fun pkg(
        period: PaywallPeriod,
        micros: Long,
        price: String,
    ): PaywallPackage =
        PaywallPackage(
            period = period,
            localizedPriceString = price,
            priceAmountMicros = micros,
            currencyCode = "IDR",
            productId = "nearyou_premium_${period.name.lowercase()}",
        )
}
