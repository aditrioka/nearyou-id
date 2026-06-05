package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import id.nearyou.app.screens.auth.AgeGateScreen
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.screens.post.PostCreationScreen

/**
 * Maps each [NavKey] route to its screen composable, wiring every screen's narrow navigation lambdas
 * (design Decision 6) to back-stack operations on [backStack]. Auth-boundary transitions use
 * [replaceAll] (clear-and-set — no back-navigation across the boundary); in-auth transitions use
 * `add()` (push) / `removeLastOrNull()` (pop):
 *
 *  - [RootRoute] → [RootRouterScreen] — token-presence routing → `replaceAll(HomeRoute/SignInRoute)`.
 *  - [SignInRoute] → [SignInScreen] — success → `replaceAll(HomeRoute)`; 404 no-account → `add(AgeGateRoute)`.
 *  - [HomeRoute] → [HomeScreen] — FAB → `add(PostCreationRoute)`.
 *  - [AgeGateRoute] → [AgeGateScreen] — success → `replaceAll(HomeRoute)`; account-exists / absent
 *    identity → `replaceAll(SignInRoute)`.
 *  - [PostCreationRoute] → [PostCreationScreen] — success → `removeLastOrNull()`.
 *
 * Adding a new screen requires only declaring its `NavKey` (NavKeys.kt) + one `entry<…>` mapping
 * here (`mobile-app-scaffold` § "Typed navigation host with start destination").
 */
fun appEntryProvider(backStack: NavBackStack<NavKey>): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        entry<RootRoute> {
            RootRouterScreen(
                onAuthenticated = { backStack.replaceAll(HomeRoute) },
                onUnauthenticated = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<SignInRoute> {
            SignInScreen(
                onSignedIn = { backStack.replaceAll(HomeRoute) },
                onNoAccount = { backStack.add(AgeGateRoute) },
            )
        }
        entry<HomeRoute> {
            HomeScreen(onOpenComposer = { backStack.add(PostCreationRoute) })
        }
        entry<AgeGateRoute> {
            AgeGateScreen(
                onSignedUp = { backStack.replaceAll(HomeRoute) },
                onExitToSignIn = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<PostCreationRoute> {
            PostCreationScreen(onPostCreated = { backStack.removeLastOrNull() })
        }
    }
