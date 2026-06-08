package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.auth.SessionInvalidator
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject

/**
 * Observes [SessionInvalidator.sessionExpired] for the lifetime of the [backStack] and
 * `replaceAll`s to [SignInRoute] on a terminal 401 (bearer refresh failed → store cleared),
 * regardless of which screen is foreground. Hosted in `screens/routing/` (a carved-out
 * auth-flow path) so `App.kt` stays free of auth-flow identifiers.
 *
 * **Destination preservation (mobile-session-expiry-and-proactive-refresh, design D5):** BEFORE
 * re-routing, it captures the current top destination into the in-memory [PendingReturnDestination]
 * so a successful re-auth returns the user there (not `HomeRoute`). A captured **auth route** (or an
 * empty stack) is recorded as `null` → the re-auth falls back to `HomeRoute` (never restore into the
 * auth flow). The capture also marks the next `SignInScreen` entry as **involuntary** so it shows the
 * session-expired notice. The signal carrier is now a buffered consume-once `Channel` (D1), so an
 * `invalidate()` that fired before this collector subscribed is still delivered (not dropped).
 */
@Composable
fun SessionExpiryEffect(backStack: NavBackStack<NavKey>) {
    val sessionInvalidator = koinInject<SessionInvalidator>()
    val pendingReturnDestination = koinInject<PendingReturnDestination>()
    LaunchedEffect(Unit) {
        // `collect` (not `collectLatest`): the body is a synchronous capture + `replaceAll` with no
        // suspension, so there is no in-flight work to cancel — plain sequential collection is the fit.
        sessionInvalidator.sessionExpired.collect {
            // Capture the screen the user was on BEFORE clearing the stack. A non-auth top is restored
            // after re-auth; an auth top (or empty stack) records null → fall back to Home.
            val top = backStack.lastOrNull()
            pendingReturnDestination.capture(if (top == null || top.isAuthRoute()) null else top)
            backStack.replaceAll(SignInRoute)
        }
    }
}
