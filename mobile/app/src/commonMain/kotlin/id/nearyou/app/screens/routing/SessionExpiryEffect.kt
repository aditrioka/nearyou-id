package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import id.nearyou.app.auth.SessionInvalidator
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

/**
 * Observes [SessionInvalidator.sessionExpired] for the lifetime of the [backStack] and
 * `replaceAll`s to [SignInRoute] on a terminal 401 (bearer refresh failed → store cleared),
 * regardless of which screen is foreground. Hosted in `screens/routing/` (a carved-out
 * auth-flow path) so `App.kt` stays free of auth-flow identifiers.
 */
@Composable
fun SessionExpiryEffect(backStack: NavBackStack<NavKey>) {
    val sessionInvalidator = koinInject<SessionInvalidator>()
    LaunchedEffect(Unit) {
        sessionInvalidator.sessionExpired.collectLatest {
            backStack.replaceAll(SignInRoute)
        }
    }
}
