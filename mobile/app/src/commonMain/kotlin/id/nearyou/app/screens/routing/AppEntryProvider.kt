package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import id.nearyou.app.screens.auth.AgeGateScreen
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.consent.ConsentScreen
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.screens.post.PostCreationScreen
import id.nearyou.app.screens.post.PostDetailScreen

/**
 * Maps each [NavKey] route to its screen composable, wiring every screen's narrow navigation lambdas
 * (design Decision 6) to back-stack operations on [backStack]. Auth-boundary transitions use
 * [replaceAll] (clear-and-set — no back-navigation across the boundary); in-auth transitions use
 * `add()` (push) / `removeLastOrNull()` (pop):
 *
 *  - [RootRoute] → [RootRouterScreen] — token-presence routing → `replaceAll(HomeRoute/SignInRoute)`.
 *  - [SignInRoute] → [SignInScreen] — success → `replaceAll(HomeRoute)`; 404 no-account → `add(AgeGateRoute)`.
 *  - [HomeRoute] → [HomeScreen] — FAB → `add(PostCreationRoute)`; card tap → `add(PostDetailRoute(...))`.
 *  - [AgeGateRoute] → [AgeGateScreen] — success → `replaceAll(ConsentRoute)`; account-exists /
 *    absent identity → `replaceAll(SignInRoute)`.
 *  - [ConsentRoute] → [ConsentScreen] — done (Success or post-failure skip) → `replaceAll(HomeRoute)`.
 *  - [PostCreationRoute] → [PostCreationScreen] — success → `removeLastOrNull()`.
 *  - [PostDetailRoute] → [PostDetailScreen] — back → `removeLastOrNull()` (pop off the root stack).
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
            HomeScreen(
                onOpenComposer = { backStack.add(PostCreationRoute) },
                // The post-detail root-stack push lives HERE at the call site (HomeScreen holds no
                // back-stack reference, mirroring the composer FAB). HomeScreen forwards the tapped
                // card's non-PII display fields as a [PostDetailTarget]; this builds the PostDetailRoute
                // (NO latitude/longitude) and appends it above HomeRoute so the detail overlays the tab
                // bar (no per-tab NavDisplay introduced).
                onOpenPost = { target ->
                    backStack.add(
                        PostDetailRoute(
                            postId = target.postId,
                            content = target.content,
                            cityName = target.cityName,
                            distanceM = target.distanceM,
                            createdAtIso = target.createdAtIso,
                            likedByViewer = target.likedByViewer,
                            replyCount = target.replyCount,
                        ),
                    )
                },
            )
        }
        entry<AgeGateRoute> {
            AgeGateScreen(
                onSignedUp = { backStack.replaceAll(ConsentRoute) },
                onExitToSignIn = { backStack.replaceAll(SignInRoute) },
            )
        }
        entry<ConsentRoute> {
            // ConsentRoute REPLACES AgeGateRoute (the signup-success transition uses `replaceAll`,
            // not `add`), so the back stack holds [ConsentRoute] with no AgeGateRoute beneath —
            // back-press cannot re-enter the age gate. onDone (Success or the post-failure skip) →
            // HomeRoute (`mobile-analytics-consent` capability).
            ConsentScreen(onDone = { backStack.replaceAll(HomeRoute) })
        }
        entry<PostCreationRoute> {
            // `removeLastOrNull()` is size-safe by construction: PostCreationRoute is only ever appended
            // ATOP HomeRoute (the home-surface FAB), so popping it leaves HomeRoute — never an empty stack
            // (which NavDisplay would reject). No defensive size guard is added, so a future misuse that
            // makes PostCreationRoute the sole entry fails loudly rather than silently no-op'ing.
            PostCreationScreen(onPostCreated = { backStack.removeLastOrNull() })
        }
        entry<PostDetailRoute> { route ->
            // `removeLastOrNull()` is size-safe: PostDetailRoute is only ever appended ATOP HomeRoute
            // (the feed card tap), so popping it leaves HomeRoute — never an empty stack.
            PostDetailScreen(route = route, onBack = { backStack.removeLastOrNull() })
        }
    }
