package id.nearyou.app.screens.paywall

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle

internal actual fun formatPriceMicros(
    amountMicros: Long,
    currencyCode: String,
): String {
    val amount = amountMicros.toDouble() / MICROS_PER_UNIT
    val formatter =
        NSNumberFormatter().apply {
            numberStyle = NSNumberFormatterCurrencyStyle
            this.currencyCode = currencyCode
            // Whole major-unit amounts (e.g. Rupiah) render without trailing decimals.
            if (amountMicros % MICROS_PER_UNIT == 0L) {
                maximumFractionDigits = 0u
            }
        }
    return formatter.stringFromNumber(NSNumber(double = amount)) ?: ""
}
