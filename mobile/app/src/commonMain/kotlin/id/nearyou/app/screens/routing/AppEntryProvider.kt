package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import id.nearyou.app.screens.auth.AgeGateScreen
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.post.PostCreationScreen
import id.nearyou.app.screens.shell.AppShellScreen

/**
 * Maps each [NavKey] route to its screen composable, wiring every screen's narrow navigation lambdas
 * (design Decision 6) to back-stack operations on [backStack]. Auth-boundary transitions use
 * [replaceAll] (clear-and-set — no back-navigation across the boundary); in-auth transitions use
 * `add()` (push) / `removeLastOrNull()` (pop):
 *
 *  - [RootRoute] → [RootRouterScreen] — token-presence routing → `replaceAll(HomeRoute/SignInRoute)`.
 *  - [SignInRoute] → [SignInScreen] — success → `replaceAll(HomeRoute)`; 404 no-account → `add(AgeGateRoute)`.
 *  - [HomeRoute] → [AppShellScreen] — the bottom-nav section shell (Home / Notifikasi / Profil); the Home
 *    section hosts the feed tab host + composer FAB → `add(PostCreationRoute)` on the root stack.
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
            // The bottom-nav section shell is the authenticated root entry (mobile-home-tab-host §
            // "Bottom navigation is a top-level section shell"). The shell hosts HomeScreen for the Home
            // section; the composer-FAB root push stays here (the shell holds no back-stack reference,
            // matching HomeScreen's prior wiring). #159's onOpenPost root push absorbs at this same call
            // site when it lands (design D9 / tasks.md 14.5).
            AppShellScreen(onOpenComposer = { backStack.add(PostCreationRoute) })
        }
        entry<AgeGateRoute> {
            AgeGateScreen(
                onSignedUp = { backStack.replaceAll(HomeRoute) },
                onExitToSignIn = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<PostCreationRoute> {
            // `removeLastOrNull()` is size-safe by construction: PostCreationRoute is only ever appended
            // ATOP HomeRoute (the home-surface FAB), so popping it leaves HomeRoute — never an empty stack
            // (which NavDisplay would reject). No defensive size guard is added, so a future misuse that
            // makes PostCreationRoute the sole entry fails loudly rather than silently no-op'ing.
            PostCreationScreen(onPostCreated = { backStack.removeLastOrNull() })
        }
    }
