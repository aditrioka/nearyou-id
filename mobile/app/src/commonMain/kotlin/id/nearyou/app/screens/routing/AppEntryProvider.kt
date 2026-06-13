package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import id.nearyou.app.screens.auth.AgeGateScreen
import id.nearyou.app.screens.auth.SignInScreen
import id.nearyou.app.screens.consent.ConsentScreen
import id.nearyou.app.screens.post.PostCreationScreen
import id.nearyou.app.screens.post.PostDetailScreen
import id.nearyou.app.screens.profile.ProfileScreen
import id.nearyou.app.screens.search.SearchScreen
import id.nearyou.app.screens.shell.AppShellScreen
import org.koin.compose.koinInject

/**
 * Maps each [NavKey] route to its screen composable, wiring every screen's narrow navigation lambdas
 * (design Decision 6) to back-stack operations on [backStack]. Auth-boundary transitions use
 * [replaceAll] (clear-and-set — no back-navigation across the boundary); in-auth transitions use
 * `add()` (push) / `removeLastOrNull()` (pop):
 *
 *  - [RootRoute] → [RootRouterScreen] — token-presence routing → `replaceAll(HomeRoute/SignInRoute)`.
 *  - [SignInRoute] → [SignInScreen] — success → `replaceAll(HomeRoute)`; 404 no-account → `add(AgeGateRoute)`.
 *  - [HomeRoute] → [AppShellScreen] — the bottom-nav section shell (Home / Notifikasi / Profil); the Home
 *    section hosts the feed tab host + composer FAB → `add(PostCreationRoute)`, and a feed card tap →
 *    `add(PostDetailRoute(...))` — both on the root stack (above the shell).
 *  - [AgeGateRoute] → [AgeGateScreen] — success → `replaceAll(ConsentRoute)`; account-exists / absent
 *    identity → `replaceAll(SignInRoute)`.
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
            // mobile-session-expiry-and-proactive-refresh (D5) — on sign-in success, return the user to
            // the destination captured before an involuntary terminal-401 re-route (else HomeRoute), then
            // clear the holder. A fresh-launch sign-in has no captured destination → restores HomeRoute.
            val pendingReturnDestination = koinInject<PendingReturnDestination>()
            SignInScreen(
                onSignedIn = {
                    val destination = pendingReturnDestination.peek()
                    pendingReturnDestination.clear()
                    backStack.restoreAfterReauth(destination)
                },
                onNoAccount = { backStack.add(AgeGateRoute) },
            )
        }
        entry<HomeRoute> {
            // The bottom-nav section shell is the authenticated root entry (mobile-home-tab-host §
            // "Bottom navigation is a top-level section shell"). The shell hosts HomeScreen for the Home
            // section; BOTH root-stack pushes live HERE at the call site (the shell + HomeScreen hold no
            // back-stack reference, mirroring the composer FAB): the composer FAB → PostCreationRoute,
            // and a feed card tap → PostDetailRoute (built from the tapped card's non-PII
            // [PostDetailTarget] — NO latitude/longitude — appended above the shell so the detail overlays
            // the section bar). This absorbs #159's onOpenPost wiring through the shell (design D9 /
            // tasks.md 14.5): the call site moved from HomeScreen to AppShellScreen, which forwards
            // onOpenPost to the Home section's HomeScreen.
            AppShellScreen(
                onOpenComposer = { backStack.add(PostCreationRoute) },
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
                            authorUsername = target.authorUsername,
                            authorDisplayName = target.authorDisplayName,
                        ),
                    )
                },
                // The cards' reply shortcut (mobile-inline-post-actions): the SAME detail push with
                // focusReplyComposer = true, so the entry autofocuses the reply composer. The
                // whole-card onOpenPost above keeps the default false.
                onOpenPostReply = { target ->
                    backStack.add(
                        PostDetailRoute(
                            postId = target.postId,
                            content = target.content,
                            cityName = target.cityName,
                            distanceM = target.distanceM,
                            createdAtIso = target.createdAtIso,
                            likedByViewer = target.likedByViewer,
                            replyCount = target.replyCount,
                            authorUsername = target.authorUsername,
                            authorDisplayName = target.authorDisplayName,
                            focusReplyComposer = true,
                        ),
                    )
                },
                // The card identity tap (mobile-profile): push the author's profile onto the root stack.
                // The host receives the resolved authorUserId (the screens resolve it from the VM's raw
                // DTO outcome — never on the PII-free card model); ProfileRoute carries only that id.
                onOpenProfile = { authorUserId -> backStack.add(ProfileRoute(authorUserId)) },
                // The Home brand app bar's search action (mobile-search) → push the parameterless
                // SearchRoute onto the root stack (above the shell, overlaying the section bar). Same
                // call-site mechanism as onOpenComposer; the shell + app bar hold no back-stack reference.
                onOpenSearch = { backStack.add(SearchRoute) },
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
        entry<ProfileRoute> { route ->
            // The other-user profile overlay (mobile-profile). `removeLastOrNull()` is size-safe:
            // ProfileRoute is only ever appended ATOP HomeRoute (the card identity tap), so popping it
            // leaves HomeRoute. targetUserId = the route's resource key; onBack pops the overlay.
            ProfileScreen(targetUserId = route.userId, onBack = { backStack.removeLastOrNull() })
        entry<SearchRoute> {
            // The Cari surface (mobile-search). `removeLastOrNull()` is size-safe: SearchRoute is only
            // ever appended ATOP HomeRoute (the app-bar search action). A result tap pushes
            // PostDetailRoute built from the hit's non-PII fields PLUS documented defaults — the search
            // wire carries no cityName/distanceM/likedByViewer/replyCount, so those default
            // ("", null, false, 0); the detail screen's /likes/count + /replies fetches are authoritative
            // (mobile-search § "A result tap opens PostDetailRoute with documented default fields").
            SearchScreen(
                onBack = { backStack.removeLastOrNull() },
                onOpenPost = { hit ->
                    backStack.add(
                        PostDetailRoute(
                            postId = hit.postId,
                            content = hit.content,
                            cityName = "",
                            distanceM = null,
                            createdAtIso = hit.createdAt,
                            likedByViewer = false,
                            replyCount = 0,
                            authorUsername = hit.authorUsername,
                            authorDisplayName = hit.authorDisplayName,
                        ),
                    )
                },
            )
        }
    }
