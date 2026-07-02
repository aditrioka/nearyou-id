package id.nearyou.app.screens.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.ui.components.NearYouLoader
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Start destination ([RootRoute]). On first composition reads the persisted token once (via
 * `AuthFlow.isAuthenticated()`) and invokes [onAuthenticated] (a `TokenPair` exists — the Ktor
 * `Auth` plugin refreshes a stale access token lazily on the first authenticated call) or
 * [onUnauthenticated] (no token). The [appEntryProvider] wires those lambdas to
 * `backStack.replaceAll(HomeRoute)` / `replaceAll(SignInRoute)`. Renders the branded
 * [NearYouLoader] (the mark itself is the activity indicator — no separate static logo or
 * spinner) while the read is in flight; no routing decision is made before the read completes.
 * The loader carries the `app_name` contentDescription so the splash stays discoverable to
 * accessibility services (and to the §6.8c in-flight test).
 *
 * Routing is expressed as injected lambdas (not a `LocalNavigator`) so the router is directly
 * testable with recording callbacks and stays free of host-specific imports (design Decision 2/6).
 */
@Composable
fun RootRouterScreen(
    onAuthenticated: () -> Unit,
    onUnauthenticated: () -> Unit,
) {
    val authFlow = koinInject<AuthFlow>()

    LaunchedEffect(Unit) {
        if (authFlow.isAuthenticated()) {
            onAuthenticated()
        } else {
            onUnauthenticated()
        }
    }

    val appName = stringResource(Res.string.app_name)

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NearYouLoader(
            modifier =
                Modifier
                    .size(120.dp)
                    .semantics { contentDescription = appName },
        )
    }
}
