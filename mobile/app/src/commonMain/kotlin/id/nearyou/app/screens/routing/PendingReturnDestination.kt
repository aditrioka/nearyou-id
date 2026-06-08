package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey

/**
 * In-memory holder for the cross-cutting state of an **involuntary** session invalidation
 * (mobile-session-expiry-and-proactive-refresh, design D5). It carries two consume-on-success facts,
 * both captured at the terminal-401 re-route and cleared on a successful re-authentication:
 *
 *  1. the **involuntary-entry flag** — so `SignInScreen` shows the session-expired notice ONLY when
 *     entered via a terminal-401 re-route, never on a fresh launch; and
 *  2. the **return [destination]** — the top route the user was on, so re-auth returns them there
 *     instead of `HomeRoute`.
 *
 * Registered as a Koin `single` (see `mobileModule`), mirroring [PendingSignupIdentity]: the state
 * lives in process memory ONLY — never persisted, never logged, and (critically) never placed on a
 * `NavKey` (the Nav3 back stack is serialized to disk on iOS — putting the destination/flag there
 * would re-route a restored back stack involuntarily). Because it is in-memory only, it does NOT
 * survive process death, so a cold launch after a kill is correctly treated as a fresh (voluntary)
 * launch with no notice.
 *
 * Lifecycle: [capture] is invoked by `SessionExpiryEffect` BEFORE `replaceAll(SignInRoute)`; [clear]
 * is invoked on sign-in success (`appEntryProvider`'s `SignInRoute` entry) after consuming the
 * destination. [wasInvoluntary] / [peek] are NON-clearing reads (so a recomposition / config change
 * while on `SignInScreen` keeps showing the notice).
 */
class PendingReturnDestination {
    private var destination: NavKey? = null
    private var involuntary: Boolean = false

    /**
     * Record an involuntary re-route. [destination] is the route to return to after re-auth, or `null`
     * to fall back to `HomeRoute` (the caller passes `null` when the captured top is itself an auth
     * route — never restore into the auth flow). Always marks the next sign-in as involuntary.
     */
    fun capture(destination: NavKey?) {
        this.destination = destination
        this.involuntary = true
    }

    /** True if `SignInScreen` was reached via an involuntary terminal-401 re-route. Non-clearing. */
    fun wasInvoluntary(): Boolean = involuntary

    /** The captured return destination (or `null` → restore `HomeRoute`). Non-clearing peek. */
    fun peek(): NavKey? = destination

    /** Drop the held state; called on sign-in success. Idempotent. */
    fun clear() {
        destination = null
        involuntary = false
    }
}
