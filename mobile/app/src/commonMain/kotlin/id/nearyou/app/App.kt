package id.nearyou.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import id.nearyou.app.screens.routing.ProactiveRefreshEffect
import id.nearyou.app.screens.routing.PushTapNavigationEffect
import id.nearyou.app.screens.routing.RootRoute
import id.nearyou.app.screens.routing.SessionExpiryEffect
import id.nearyou.app.screens.routing.appEntryProvider
import id.nearyou.app.screens.routing.navSavedStateConfiguration
import id.nearyou.app.theme.NearYouTheme

/**
 * The single Navigation 3 host. A developer-owned [rememberNavBackStack] (seeded with `RootRoute`
 * and carrying the polymorphic [navSavedStateConfiguration] so it is saveable on iOS) is rendered by
 * [NavDisplay] over [appEntryProvider]'s route→composable mapping. `entryDecorators` wires (in the
 * canonical order) `rememberSaveableStateHolderNavEntryDecorator()` (per-entry `rememberSaveable`
 * state) THEN `rememberViewModelStoreNavEntryDecorator()` (per-entry `ViewModelStore`), so a screen's
 * `koinViewModel` is scoped to its `NavEntry` — it survives the entry going off-screen (e.g. Home
 * while the composer is on top) and is cleared only when the entry is popped (design Decision 5;
 * this is why returning from the composer does NOT re-fetch the Nearby feed).
 *
 * image-attached-posts (D5): the singleton Coil [ImageLoader] is configured once here with the
 * [KtorNetworkFetcherFactory] so network image URLs load (Coil 3.x does NOT auto-register a network
 * fetcher — unlike Coil 2 — so the fetcher MUST be added explicitly). `setSafe` is the documented
 * CMP entrypoint; it no-ops if a loader is already set, and the `remember {}` guard makes it a per-app
 * one-shot. A dedicated loader (NOT the auth `HttpClient`) is used: post images are public, so no Bearer
 * is attached (D6). Image bytes are never logged.
 */
@Composable
@Preview
fun App() {
    remember {
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(KtorNetworkFetcherFactory()) }
                .build()
        }
    }
    NearYouTheme {
        val backStack = rememberNavBackStack(navSavedStateConfiguration, RootRoute)
        // A terminal 401 (bearer refresh failed → store cleared) re-routes to the unauthenticated
        // entry surface from any foreground screen. The effect outlives the start-destination router
        // (which replaces itself at launch). Hosted in screens/routing so App.kt names no auth-flow id.
        SessionExpiryEffect(backStack)
        // Preemptive token refresh on every app-root ON_RESUME (cold-start + foreground return): refreshes
        // async/non-blocking only when the access token is within 5 min of expiry
        // (mobile-session-expiry-and-proactive-refresh D3).
        ProactiveRefreshEffect()
        // mobile-push-message-handling: consumes the push-tap nav signal (offered by the platform tap
        // handlers) once HomeRoute is on the stack, routing via the shared notifications resolver.
        PushTapNavigationEffect(backStack)
        // Build the entry map once per back-stack instance (not per recomposition): the back stack is
        // stable across recompositions, so the route→composable mapping never needs rebuilding.
        val entryProvider = remember(backStack) { appEntryProvider(backStack) }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = entryProvider,
        )
    }
}
