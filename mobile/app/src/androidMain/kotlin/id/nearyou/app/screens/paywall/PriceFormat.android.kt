package id.nearyou.app.screens.paywall

import java.text.NumberFormat
import java.util.Currency

internal actual fun formatPriceMicros(
    amountMicros: Long,
    currencyCode: String,
): String {
    val amount = amountMicros.toDouble() / MICROS_PER_UNIT
    val format = NumberFormat.getCurrencyInstance()
    runCatching { format.currency = Currency.getInstance(currencyCode) }
    // Whole major-unit amounts (e.g. Rupiah) render without trailing decimals.
    if (amountMicros % MICROS_PER_UNIT == 0L) {
        format.maximumFractionDigits = 0
    }
    return format.format(amount)
}
