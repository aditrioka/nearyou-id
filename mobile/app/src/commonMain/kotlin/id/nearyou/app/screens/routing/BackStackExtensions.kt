package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Clears the back stack to a single [key] — the Nav3 equivalent of Voyager's `replaceAll`, used at
 * every auth boundary (`RootRouter` → Home/SignIn, sign-in success → Home, age-gate success → Home,
 * account-exists → SignIn, session-expiry → SignIn) so there is NO back-navigation across an auth
 * boundary (design Decision 6). In-auth transitions use `add(key)` (push) and `removeLastOrNull()`
 * (pop) directly on the back stack.
 */
fun NavBackStack<NavKey>.replaceAll(key: NavKey) {
    clear()
    add(key)
}

/**
 * The auth/sign-in-flow routes — the destinations that MUST NOT be captured as a post-re-auth return
 * target (restoring into the auth flow makes no sense) and MUST NOT host the session-expired notice
 * (mobile-session-expiry-and-proactive-refresh, design D5). `SessionExpiryEffect` falls back to
 * `HomeRoute` when the captured top is one of these.
 */
fun NavKey.isAuthRoute(): Boolean = this is RootRoute || this is SignInRoute || this is AgeGateRoute || this is ConsentRoute

/**
 * Restore the back stack after a successful re-authentication (mobile-session-expiry-and-proactive-refresh,
 * design D5). [destination] is the route captured before the involuntary re-route (or `null` → Home):
 *  - `null` or `HomeRoute` → just `[HomeRoute]`.
 *  - any non-Home destination (e.g. a `PostDetailRoute` the user was viewing) → `[HomeRoute, destination]`,
 *    so the section shell sits beneath the restored overlay and back-press returns to it.
 *
 * A restored destination that needs fresh data simply re-fetches on mount (design D5).
 */
fun NavBackStack<NavKey>.restoreAfterReauth(destination: NavKey?) {
    clear()
    add(HomeRoute)
    if (destination != null && destination != HomeRoute) {
        add(destination)
    }
}
