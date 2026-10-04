package id.nearyou.app.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nearyou.app.billing.PremiumEntitlementSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

private val neverConfirmed: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The [PremiumEntitlementSession.purchaseConfirmed] signal — handed to a ViewModel by the gated screens, and
 * read by [rememberPremiumActivating] for the upsell surfaces — resolved fail-safe (`getOrNull`, the
 * `rememberTimelineAds` idiom): a host that does not bind the session (a screen test's own Koin module, a DI
 * gap) gets a never-confirmed flow and behaves exactly as before — no resolution crash.
 * premium-entitlement-lifecycle.
 */
@Composable
fun rememberPremiumConfirmed(): StateFlow<Boolean> {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<PremiumEntitlementSession>()?.purchaseConfirmed ?: neverConfirmed }
}

/**
 * Whether an upsell should show the "Premium sedang diaktifkan" notice instead of its upgrade pitch: true
 * once a purchase is confirmed on this device. An upsell only renders because the server answered as Free,
 * so a confirmed purchase means the server tier still lags the RevenueCat webhook. Read by the shared upsell
 * components (as their defaulted `premiumActivating` parameter) and the screen-private gates, never by a
 * ViewModel: `mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the
 * webhook-lag window" (#517). Fail-safe like [rememberPremiumConfirmed], but it still needs a Koin context:
 * a test composing a shared component with no Koin started passes `premiumActivating` explicitly.
 */
@Composable
fun rememberPremiumActivating(): Boolean = rememberPremiumConfirmed().collectAsStateWithLifecycle().value
