package id.nearyou.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cafe.adriel.voyager.navigator.Navigator
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.theme.NearYouTheme

@Composable
@Preview
fun App() {
    NearYouTheme {
        Navigator(HomeScreen())
    }
}
