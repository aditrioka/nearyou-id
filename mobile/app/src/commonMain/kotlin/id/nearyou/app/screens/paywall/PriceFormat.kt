package id.nearyou.app.screens.paywall

/**
 * Formats a RevenueCat amount-in-micros (price × 1,000,000 in the currency's major units) into a localized
 * currency string. Used ONLY for the COMPUTED prices the SDK does not pre-format — the strike-through
 * anchor (4× Weekly / 12× Monthly) and the Weekly per-day baseline. The actual package prices always use
 * the SDK's own `localizedPriceString`. Platform-backed (Android `NumberFormat` / iOS `NSNumberFormatter`)
 * so it respects the device locale + the store currency. Whole major-unit amounts (e.g. Rupiah) render
 * without trailing decimals.
 */
internal expect fun formatPriceMicros(
    amountMicros: Long,
    currencyCode: String,
): String

internal const val MICROS_PER_UNIT: Long = 1_000_000L
