package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/*
 * The typed Navigation 3 route keys for :mobile:app — one NavKey per destination, mapped to its
 * screen composable by appEntryProvider. Routes are typed entities (not stringly-typed paths), per
 * the mobile-app-scaffold § "Typed navigation host with start destination" requirement.
 *
 * Every route is @Serializable and registered in navSavedStateConfiguration's polymorphic
 * SerializersModule so the back stack is saveable on non-JVM targets (iOS), where Nav3's
 * reflection-based serialization is unavailable (mobile-app-scaffold § "Back stack uses
 * serializable NavKey routes").
 *
 * AgeGateRoute is a parameterless marker carrying NO identity payload (design Decision 4 +
 * mobile-age-gate § "AgeGateRoute declares no identity property"): the verified Google id_token
 * lives only in the in-memory PendingSignupIdentity holder, never in the serialized back stack.
 */

/** Start destination. Reads token presence once, then `replaceAll`s to [HomeRoute] or [SignInRoute]. */
@Serializable
data object RootRoute : NavKey

/** Unauthenticated entry surface (Google Sign-In). */
@Serializable
data object SignInRoute : NavKey

/** Authenticated home surface — hosts the Nearby feed + the post-composer FAB. */
@Serializable
data object HomeRoute : NavKey

/**
 * Signup-new-user surface, reached when sign-in reports no existing account. A parameterless marker:
 * the verified `id_token` is read from [PendingSignupIdentity], never carried as a route field
 * (which would write it into the serialized back stack on iOS — design Decision 4).
 */
@Serializable
data object AgeGateRoute : NavKey

/** Post-composer surface, opened by the home-surface FAB. */
@Serializable
data object PostCreationRoute : NavKey
