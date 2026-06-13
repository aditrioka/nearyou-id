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
 * The Premium-gated search ("Cari") surface (the `mobile-search` capability), opened by the Home brand
 * app bar's search action icon and pushed onto the ROOT back stack (above [HomeRoute], overlaying the
 * section bar — the same mechanism [PostCreationRoute] / [PostDetailRoute] use). A **parameterless**
 * marker: unlike [PostDetailRoute] the search query is entered IN the screen, so the route carries NO
 * payload. Registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` so the back
 * stack is saveable on iOS.
 */
@Serializable
data object SearchRoute : NavKey

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
 *
 * [authorUsername] / [authorDisplayName] (added by `mobile-timeline-card-redesign`) carry the author
 * DISPLAY identity to the header — display data, NOT the banned author UUID. Defaulted to `""` so a
 * back stack serialized BEFORE this change still decodes on process-death restore; an empty value
 * renders the header without the identity row (graceful, spec'd).
 *
 * [focusReplyComposer] (added by `mobile-inline-post-actions`) carries the feed cards' reply-shortcut
 * intent: `true` autofocuses the reply composer (IME up) once on the detail entry's first composition;
 * the whole-card open pushes the default `false` (today's behavior). Defaulted so payloads serialized
 * BEFORE this change still decode — the same compatibility precedent as the identity fields. A boolean
 * intent flag, no PII.
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
    val authorUsername: String = "",
    val authorDisplayName: String = "",
    val focusReplyComposer: Boolean = false,
) : NavKey

/**
 * Other-user profile surface ([id.nearyou.app.screens.profile.ProfileScreen]), opened by tapping a feed
 * card's author identity (pushed onto the ROOT back stack above [HomeRoute], overlaying the tab bar —
 * the same mechanism [PostDetailRoute] uses). The second **payload-carrying** route, so it MUST be
 * `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
 *
 * Carries ONLY [userId] — the resource key the keyed read (`GET /api/v1/users/{userId}`) structurally
 * requires (a profile route without it is impossible). The id is the target user's UUID, supplied by the
 * host from the timeline DTO's `author_user_id` (parsed but never rendered); it is NEVER rendered as a UI
 * string (only used as the API path param) and is distinct from coordinate-PII / token material — it is
 * already transmitted on the timeline wire to every client (design D4). It MUST NOT carry any
 * `latitude`/`longitude` or token. The SELF profile has no [ProfileRoute] — it is rendered in the shell's
 * Profil section, resolving the self id from the session (design D1).
 */
@Serializable
data class ProfileRoute(
    val userId: String,
) : NavKey

/**
 * Conversation-list surface (`mobile-chat-screen`), opened from the Home brand app-bar "Pesan" action
 * and pushed onto the ROOT back stack above [HomeRoute] (overlaying the section bar, like [PostDetailRoute]).
 * A parameterless `data object`: the list is always fetched fresh (design D3).
 */
@Serializable
data object ConversationListRoute : NavKey

/**
 * Chat-thread surface (`mobile-chat-screen`), reached from a [ConversationListRoute] row tap (and, after
 * PR #245, the profile "Kirim pesan" create-or-return path). Pushed onto the ROOT back stack. A
 * payload-carrying `@Serializable data class` (like [PostDetailRoute]), so it MUST be registered in the
 * `navSavedStateConfiguration` polymorphic `SerializersModule` for the iOS-saveable back stack.
 *
 * It carries ONLY the [conversationId] (a conversation identifier — NOT user PII; required to fetch
 * `GET /api/v1/chat/{id}/messages` + subscribe to the realtime channel) plus the partner's DISPLAY
 * identity for the thread top bar. It MUST NOT carry the partner's user UUID, message content, or any
 * coordinate — the back stack persists to disk on iOS (the same PII discipline [PostDetailRoute] /
 * [AgeGateRoute] follow). The display fields default to `""` so a back stack serialized before this
 * change still decodes on restore (an empty value renders the `chat_account_deleted` placeholder).
 */
@Serializable
data class ChatThreadRoute(
    val conversationId: String,
    val partnerUsername: String = "",
    val partnerDisplayName: String = "",
) : NavKey
