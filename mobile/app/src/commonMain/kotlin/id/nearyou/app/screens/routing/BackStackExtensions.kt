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
