package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * App-root preemptive-refresh hook (mobile-session-expiry-and-proactive-refresh, design D3): on each
 * `Lifecycle.Event.ON_RESUME` (the cold-start first resume AND every foreground return), it invokes
 * [ProactiveTokenRefreshTrigger.onResume] — which refreshes only when the access token is within the
 * 5-min window of expiry. Implemented in commonMain via `lifecycle-runtime-compose`'s
 * [LifecycleEventEffect] (the CMP common `LifecycleOwner` is wired to the Android activity/process
 * lifecycle and the iOS UIViewController lifetime — no expect/actual, no new dependency).
 *
 * The refresh launches on the app-root [rememberCoroutineScope] (**lifecycle-bound**, cancels with
 * the host — NOT `GlobalScope`) and is fire-and-forget / non-blocking: it never blocks composition or
 * the first frame. `runCatching` guards the launch so a transport failure during the proactive refresh
 * cannot crash the scope; a rejected refresh token funnels through `SessionInvalidator` → the reliable
 * re-route (handled inside [ProactiveTokenRefreshTrigger]), not surfaced as an exception here.
 *
 * Hosted next to `SessionExpiryEffect` at the app root so `App.kt` names no auth-flow identifier.
 */
@Composable
fun ProactiveRefreshEffect() {
    val trigger = koinInject<ProactiveTokenRefreshTrigger>()
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { runCatching { trigger.onResume() } }
    }
}
