package id.nearyou.app.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.infra.revenuecat.PurchaseController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.compose.getKoin

/**
 * The client Premium-entitlement lifecycle (`mobile-premium-entitlement`, issue #490). A Koin single.
 *
 * [syncIdentity] keeps the RevenueCat app-user id equal to the signed-in `users.id` (the token's `sub`,
 * via [SelfUserIdProvider]) — `logIn` when signed in, `logOut` when signed out — so purchases reach the
 * `subscription-billing-webhook` under a resolvable id. It is idempotent and derived from the token store,
 * so every session boundary (sign-in, logout, invalidation, app-root resume, pre-purchase) just calls it;
 * a missed or failed call is healed by the next.
 *
 * [purchaseConfirmed] is the client entitlement published by the paywall on a confirmed purchase — the
 * signal the Premium-gated surfaces re-evaluate from while the server's webhook-driven tier catches up.
 * State, not an event (docs/11 §2.2): a surface created after the purchase reads it immediately. It is
 * reset whenever the bound account changes, so it never outlives the account that bought.
 */
class PremiumEntitlementSession(
    private val selfUserIdProvider: SelfUserIdProvider,
    private val purchaseController: PurchaseController,
) {
    private val mutex = Mutex()
    private var boundUserId: String? = null

    private val confirmed = MutableStateFlow(false)
    val purchaseConfirmed: StateFlow<Boolean> = confirmed.asStateFlow()

    /** Align RevenueCat with the session; true iff identified as the signed-in user. Never throws. */
    suspend fun syncIdentity(): Boolean =
        mutex.withLock {
            val userId = selfUserIdProvider.selfUserId()
            if (userId != boundUserId) {
                confirmed.value = false
                boundUserId = userId
            }
            if (userId == null) {
                purchaseController.logOut()
                false
            } else {
                purchaseController.logIn(userId)
            }
        }

    fun onPurchaseConfirmed() {
        confirmed.value = true
    }
}

private val neverConfirmed: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * The [PremiumEntitlementSession.purchaseConfirmed] signal for a screen to hand its ViewModel, resolved
 * fail-safe (`getOrNull`, the `TimelineAds` idiom): a host that does not bind the session (a screen test's own
 * Koin module, a DI gap) gets a never-confirmed flow and behaves exactly as before — no resolution crash.
 */
@Composable
fun rememberPremiumConfirmed(): StateFlow<Boolean> {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<PremiumEntitlementSession>()?.purchaseConfirmed ?: neverConfirmed }
}
