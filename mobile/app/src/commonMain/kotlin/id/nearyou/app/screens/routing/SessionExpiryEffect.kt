package id.nearyou.app.screens.routing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.screens.auth.SignInScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

/**
 * Observes [SessionInvalidator.sessionExpired] for the lifetime of the [navigator] and
 * `replaceAll`s to `SignInScreen` on a terminal 401 (bearer refresh failed → store cleared),
 * regardless of which screen is foreground. Hosted in `screens/routing/` (a carved-out
 * auth-flow path) so `App.kt` stays free of auth-flow identifiers.
 */
@Composable
fun SessionExpiryEffect(navigator: Navigator) {
    val sessionInvalidator = koinInject<SessionInvalidator>()
    LaunchedEffect(Unit) {
        sessionInvalidator.sessionExpired.collectLatest {
            navigator.replaceAll(SignInScreen())
        }
    }
}
