package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import id.nearyou.app.billing.PremiumEntitlementSession
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import kotlin.coroutines.cancellation.CancellationException

/**
 * App-root RevenueCat identity hook (premium-entitlement-lifecycle, design D2 — mirrors
 * [ProactiveRefreshEffect]): on each `Lifecycle.Event.ON_RESUME` (the cold-start first resume AND every
 * foreground return) it runs [PremiumEntitlementSession.syncIdentity], so a restored session is bound to its
 * `users.id` and a `logIn` that failed offline self-heals on the next foreground. Fire-and-forget on the
 * lifecycle-bound app-root scope; a failure never crashes it ([CancellationException] is re-thrown).
 *
 * Resolved fail-safe (`getOrNull`, the `TimelineAds` idiom) so a host that does not bind the session composes.
 */
@Composable
fun BillingIdentityEffect() {
    val koin = getKoin()
    val session = remember(koin) { koin.getOrNull<PremiumEntitlementSession>() } ?: return
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            try {
                session.syncIdentity()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Best-effort: the next resume / sign-in / pre-purchase sync retries.
            }
        }
    }
}
