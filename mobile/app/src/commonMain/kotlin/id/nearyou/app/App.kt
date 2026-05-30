package id.nearyou.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.screens.routing.RootRouterScreen
import id.nearyou.app.screens.routing.SessionExpiryEffect
import id.nearyou.app.theme.NearYouTheme

@Composable
@Preview
fun App() {
    NearYouTheme {
        Navigator(RootRouterScreen()) { navigator ->
            // A terminal 401 (bearer refresh failed → store cleared) re-routes to the
            // unauthenticated entry surface from any foreground screen. The effect outlives
            // the start-destination router (which replaces itself at launch).
            SessionExpiryEffect(navigator)
            CurrentScreen()
        }
    }
}
