package id.nearyou.app.screens.routing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Voyager start destination. On first composition reads the persisted token once (via
 * `AuthFlow.isAuthenticated()`) and `replaceAll`s to `HomeScreen` (a `TokenPair` exists — the
 * Ktor `Auth` plugin refreshes a stale access token lazily on the first authenticated call)
 * or `SignInScreen` (no token). Renders a brand-logo + spinner splash while the read is
 * in flight; no routing decision is made before the read completes.
 *
 * Generalizes for Mobile #4 (interject `AgeGateScreen`) + Mobile #5 (`HomeScreen` becomes the
 * timeline) by adding one branch here, without touching the screens themselves.
 */
class RootRouterScreen : Screen {
    @Composable
    override fun Content() {
        val authFlow = koinInject<AuthFlow>()
        val navigator = LocalNavigator.currentOrThrow

        LaunchedEffect(Unit) {
            if (authFlow.isAuthenticated()) {
                navigator.replaceAll(HomeScreen())
            } else {
                navigator.replaceAll(SignInScreen())
            }
        }

        val logo =
            if (isSystemInDarkTheme()) Res.drawable.logo_brand_dark else Res.drawable.logo_brand_light

        Column(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(logo),
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(120.dp),
            )
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        }
    }
}
