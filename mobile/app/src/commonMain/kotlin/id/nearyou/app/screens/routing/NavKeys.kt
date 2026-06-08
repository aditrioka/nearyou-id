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

/**
 * Analytics & Tracking Consent surface (the `mobile-analytics-consent` capability), reached after
 * age-gate signup success. A parameterless marker — the user identity lives in the persisted token,
 * never in the serialized back stack. The signup-success transition REPLACES `AgeGateRoute` with
 * this route (not a push), so back-press on the consent screen cannot return to the age gate.
 */
@Serializable
data object ConsentRoute : NavKey

/**
 * Post-detail surface, opened by tapping a feed card (pushed onto the ROOT back stack above [HomeRoute],
 * overlaying the tab bar — the same mechanism the composer FAB uses). The FIRST **payload-carrying**
 * route (the others are parameterless `data object`s), so it MUST be `@Serializable` AND registered in
 * the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS-saveable back stack
 * requirement). It carries ONLY the non-PII display fields needed to render the post header from the
 * tapped card — there is NO single-post GET to re-fetch it (design D2). It MUST NOT declare a `latitude`
 * or `longitude` property: raw coordinates must never enter the serialized back stack (it persists to
 * disk on iOS — the same PII discipline [AgeGateRoute] applies to the `id_token`, design D3). [content]
 * is public post text, safe to serialize; [distanceM] is Nearby-origin only (`null` from Global) and is
 * carried per the spec'd payload but NOT rendered in the v1 header (the header reuses the card's
 * posted-from treatment, which shows no distance — `mobile-post-detail` § "post header").
 */
@Serializable
data class PostDetailRoute(
    val postId: String,
    val content: String,
    val cityName: String,
    val distanceM: Double?,
    val createdAtIso: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
) : NavKey
