package id.nearyou.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.routing.RootRouterScreen
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

@Composable
@Preview
fun App() {
    NearYouTheme {
        Navigator(RootRouterScreen()) { navigator ->
            // A terminal 401 (bearer refresh failed → SessionInvalidator cleared the store)
            // re-routes to SignInScreen no matter which screen is foreground. This observer
            // outlives RootRouterScreen (which replaces itself with Home/SignIn at launch).
            val sessionInvalidator = koinInject<SessionInvalidator>()
            LaunchedEffect(Unit) {
                sessionInvalidator.sessionExpired.collectLatest {
                    navigator.replaceAll(SignInScreen())
                }
            }
            cafe.adriel.voyager.navigator.CurrentScreen()
        }
    }
}
