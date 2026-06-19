package id.nearyou.app.screens.paywall

import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import kotlin.math.roundToInt

/**
 * The single, mutually-exclusive UI state the `PaywallScreen` renders (docs/11 §2.2 — sealed interface
 * for exclusive states). [Loading] while offerings fetch; [Unconfigured] when no purchasable offering is
 * available (the fail-soft "Premium belum tersedia" surface — pre-provisioning / fetch failure);
 * [Content] once packages load, carrying the derived price cards plus the independently-varying purchase
 * sub-state (in-progress / retryable-error / one-shot success) as fields (docs/11 §2.2 — data class when
 * fields vary independently; one-shot success is a nullable-style flag consumed via `onReturnConsumed`).
 */
sealed interface PaywallUiState {
    data object Loading : PaywallUiState

    data object Unconfigured : PaywallUiState

    data class Content(
        val cards: List<PaywallCardPricing>,
        val selectedPeriod: PaywallPeriod,
        val purchaseInProgress: Boolean = false,
        val purchaseError: Boolean = false,
        val purchaseSucceeded: Boolean = false,
    ) : PaywallUiState
}

/**
 * Derived display pricing for one paywall card, computed by [derivePaywallPricing] from the RevenueCat
 * Offering packages — NEVER hardcoded (docs/11 §2.8: prices are SDK data; the mockup governs layout
 * only). [localizedPrice] is the store-localized string straight from the package. The discount cards
 * (Monthly / Yearly) carry a derived strike-through [anchorMicros] (Monthly vs 4× Weekly; Yearly vs 12×
 * Monthly) + [savingsPercent]; the Weekly card carries a [perDayMicros] baseline (price ÷ 7) instead.
 * Raw micros are carried here (pure + testable); the screen formats them via the platform price formatter.
 */
data class PaywallCardPricing(
    val period: PaywallPeriod,
    val localizedPrice: String,
    val currencyCode: String,
    val anchorMicros: Long?,
    val savingsPercent: Int?,
    val perDayMicros: Long?,
)

/** Frame 17: the Monthly card is pre-selected when the offering is displayed. */
val DEFAULT_SELECTED_PERIOD: PaywallPeriod = PaywallPeriod.MONTHLY

/**
 * Pure, deterministic derivation of the frame-17 price treatment from the fetched packages. Computes the
 * strike-anchor (Monthly = 4× Weekly; Yearly = 12× Monthly), the rounded integer savings percent
 * ((anchor − price) / anchor × 100), and the Weekly per-day (price ÷ 7). A card whose inputs are missing
 * (e.g. no Weekly package to anchor Monthly against) carries `null` for that derived field — never a
 * crash, never a fabricated value; a non-positive / inverted anchor yields a `null` savings rather than a
 * negative percent. No wall-clock / platform dependency. Cards preserve the input order.
 */
fun derivePaywallPricing(packages: List<PaywallPackage>): List<PaywallCardPricing> {
    val byPeriod = packages.associateBy { it.period }
    val weeklyMicros = byPeriod[PaywallPeriod.WEEKLY]?.priceAmountMicros
    val monthlyMicros = byPeriod[PaywallPeriod.MONTHLY]?.priceAmountMicros
    return packages.map { pkg ->
        when (pkg.period) {
            PaywallPeriod.WEEKLY ->
                pkg.toCard(
                    anchorMicros = null,
                    perDayMicros = pkg.priceAmountMicros / DAYS_PER_WEEK,
                )

            PaywallPeriod.MONTHLY ->
                pkg.toCard(anchorMicros = weeklyMicros?.let { it * WEEKS_PER_MONTH })

            PaywallPeriod.YEARLY ->
                pkg.toCard(anchorMicros = monthlyMicros?.let { it * MONTHS_PER_YEAR })
        }
    }
}

private fun PaywallPackage.toCard(
    anchorMicros: Long?,
    perDayMicros: Long? = null,
): PaywallCardPricing =
    PaywallCardPricing(
        period = period,
        localizedPrice = localizedPriceString,
        currencyCode = currencyCode,
        anchorMicros = anchorMicros,
        savingsPercent = anchorMicros?.let { savingsPercent(it, priceAmountMicros) },
        perDayMicros = perDayMicros,
    )

private fun savingsPercent(
    anchorMicros: Long,
    priceMicros: Long,
): Int? {
    if (anchorMicros <= 0L || priceMicros >= anchorMicros) return null
    return (((anchorMicros - priceMicros) * 100.0) / anchorMicros).roundToInt()
}

private const val DAYS_PER_WEEK = 7
private const val WEEKS_PER_MONTH = 4
private const val MONTHS_PER_YEAR = 12
