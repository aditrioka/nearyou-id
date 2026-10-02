package id.nearyou.app.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.nearyou.app.billing.PremiumEntitlementSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

private val neverConfirmed: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The [PremiumEntitlementSession.purchaseConfirmed] signal for a screen to hand its ViewModel, resolved
 * fail-safe (`getOrNull`, the `rememberTimelineAds` idiom): a host that does not bind the session (a screen
 * test's own Koin module, a DI gap) gets a never-confirmed flow and behaves exactly as before — no resolution
 * crash. premium-entitlement-lifecycle.
 */
@Composable
fun rememberPremiumConfirmed(): StateFlow<Boolean> {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<PremiumEntitlementSession>()?.purchaseConfirmed ?: neverConfirmed }
}
