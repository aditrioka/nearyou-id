package id.nearyou.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import id.nearyou.app.screens.routing.RootRoute
import id.nearyou.app.screens.routing.SessionExpiryEffect
import id.nearyou.app.screens.routing.appEntryProvider
import id.nearyou.app.screens.routing.navSavedStateConfiguration
import id.nearyou.app.theme.NearYouTheme

/**
 * The single Navigation 3 host. A developer-owned [rememberNavBackStack] (seeded with `RootRoute`
 * and carrying the polymorphic [navSavedStateConfiguration] so it is saveable on iOS) is rendered by
 * [NavDisplay] over [appEntryProvider]'s route→composable mapping. `entryDecorators` includes ONLY
 * `rememberSaveableStateHolderNavEntryDecorator()` — each entry gets its own `SaveableStateRegistry`
 * for per-screen `rememberSaveable` state; the per-entry ViewModel-store decorator is deferred
 * (design Decision 5, no screen scopes a ViewModel today).
 */
@Composable
@Preview
fun App() {
    NearYouTheme {
        val backStack = rememberNavBackStack(navSavedStateConfiguration, RootRoute)
        // A terminal 401 (bearer refresh failed → store cleared) re-routes to the unauthenticated
        // entry surface from any foreground screen. The effect outlives the start-destination router
        // (which replaces itself at launch). Hosted in screens/routing so App.kt names no auth-flow id.
        SessionExpiryEffect(backStack)
        // Build the entry map once per back-stack instance (not per recomposition): the back stack is
        // stable across recompositions, so the route→composable mapping never needs rebuilding.
        val entryProvider = remember(backStack) { appEntryProvider(backStack) }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider,
        )
    }
}
