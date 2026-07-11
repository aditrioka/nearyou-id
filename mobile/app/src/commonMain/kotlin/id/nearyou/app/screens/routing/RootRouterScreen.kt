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
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.ui.components.NearYouLoader
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Start destination ([RootRoute]). On first composition reads the persisted token once (via
 * `AuthFlow.isAuthenticated()`) and invokes [onAuthenticated] (a `TokenPair` exists — the Ktor
 * `Auth` plugin refreshes a stale access token lazily on the first authenticated call) or
 * [onUnauthenticated] (no token). An authenticated user is additionally consent-gated
 * (`consent-rootrouter-regate`, resolving #199): a `null` [ConsentSnapshotStore] read means no
 * consent `PATCH 200` was ever acknowledged on this device (the snapshot is written only on a
 * `200` — snapshot presence IS the completion flag; no separate `consent_completed_at`), so
 * [onConsentPending] interposes `ConsentRoute` instead of Home. The [appEntryProvider] wires
 * these lambdas to `backStack.replaceAll(HomeRoute)` / `replaceAll(SignInRoute)` /
 * `replaceAll(ConsentRoute)`. Renders the branded
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
    onConsentPending: () -> Unit,
) {
    val authFlow = koinInject<AuthFlow>()
    val consentSnapshotStore = koinInject<ConsentSnapshotStore>()

    LaunchedEffect(Unit) {
        if (authFlow.isAuthenticated()) {
            // Consent re-gate runs only on the authenticated branch: null snapshot = consent
            // never completed on this device (force-quit at ConsentScreen, post-failure skip,
            // or reinstall) → re-interpose the consent surface.
            if (consentSnapshotStore.read() == null) {
                onConsentPending()
            } else {
                onAuthenticated()
            }
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
