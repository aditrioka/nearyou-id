package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavKey
import id.nearyou.app.followlist.FollowListTab
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
 * The Premium username-customization ("Ganti Username") surface (the `mobile-premium-username` capability),
 * reached from the Settings AKUN "Ganti username" row and pushed **unconditionally** onto the ROOT back
 * stack above [HomeRoute] (overlaying the section bar, the [SearchRoute] / [PostDetailRoute] mechanism).
 * A **parameterless** marker — the current handle is fetched by the screen's ViewModel (a self-profile
 * read), never carried on the route, so no identity enters the serialized back stack. The route-scoped
 * screen owns the Free/Premium gate (an on-entry self-`isPremium` read + the reactive `403` backstop), so
 * Settings holds no premium signal. Registered in the `navSavedStateConfiguration` polymorphic
 * `SerializersModule` for the iOS-saveable back stack.
 */
@Serializable
data object UsernameCustomizationRoute : NavKey

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
 * The edit-post surface (the `mobile-post-editing` editor), pushed onto the ROOT back stack above the
 * [PostDetailRoute] it was opened from (the same overlay mechanism). Carries the [postId] + the
 * [initialContent] (the current post text to prefill the editor); on a successful edit the entry pops and
 * post-detail refreshes its content + "Diedit" label on resume. No PII — the content is the post's own
 * text (already shown on detail); no author UUID, no coordinate.
 */
@Serializable
data class EditPostRoute(
    val postId: String,
    val initialContent: String,
) : NavKey

/**
 * Settings surface (the `mobile-settings` capability — mockup frame 16 "Pengaturan"), pushed onto the
 * ROOT back stack above [HomeRoute] so it overlays the section bar (the [PostDetailRoute] mechanism).
 * A parameterless marker carrying NO identity payload (the user identity lives in the persisted token,
 * never in the serialized back stack). The entry affordance is the settings gear on the self-profile
 * section ([id.nearyou.app.screens.profile.ProfileScreen]'s self variant), wired via
 * `AppShellScreen.onOpenSettings` → `ProfileScreen.onSettings` to a push here (#288).
 */
@Serializable
data object SettingsRoute : NavKey

/** Block-list management sub-surface (Settings › Privasi › "Pengguna diblokir"), pushed atop [SettingsRoute]. */
@Serializable
data object BlockedUsersRoute : NavKey

/** Analytics & data consent sub-surface (Settings › Privasi › "Privasi & data"), pushed atop [SettingsRoute]. */
@Serializable
data object ConsentSettingsRoute : NavKey

/**
 * The ban/suspension appeal surface (`mobile-appeal`, `content-moderation-appeal`), reached from the
 * banned/suspended session state. A **parameterless** marker: the limited appeal token lives in the
 * in-memory [id.nearyou.app.appeal.AppealSession] holder, NEVER on the route — a credential must not enter
 * the serialized back stack (the [AgeGateRoute] `id_token` precedent). The screen's ViewModel reads the
 * token from the holder; a restored back stack on this route after process death (token gone) falls back to
 * re-sign-in. Registered in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
 */
@Serializable
data object AppealRoute : NavKey

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
 * Follower/following member-list surface ([id.nearyou.app.screens.followlist.FollowListScreen]), opened by
 * tapping the profile's follower/following counts (`mobile-follow-lists`). Pushed onto the ROOT back stack
 * above [HomeRoute] (overlaying the section bar — the [ProfileRoute] mechanism). A payload-carrying
 * `@Serializable data class`, so it MUST be registered in the `navSavedStateConfiguration` polymorphic
 * `SerializersModule` (the iOS-saveable back stack requirement).
 *
 * Carries ONLY [userId] — the profile whose lists to read (the `GET /api/v1/users/{userId}/followers` +
 * `/following` resource key) — plus [initialTab], the deep-link target tab (the tapped count's side:
 * follower count → [FollowListTab.Followers]; following count → [FollowListTab.Following]). [userId] is the
 * target's UUID, supplied by the host from the resolved profile id; it is NEVER rendered as a UI string
 * (only the API path param) and is distinct from coordinate-PII / token material. It MUST NOT carry any
 * `latitude`/`longitude` or token.
 */
@Serializable
data class FollowListRoute(
    val userId: String,
    val initialTab: FollowListTab,
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

/**
 * The gated surface that opened the paywall — drives the contextual hero headline on the
 * [id.nearyou.app.screens.paywall.PaywallScreen]. A non-PII enum carried by [PaywallRoute] (safe to
 * serialize into the iOS-persisted back stack). This change wires [LIKE_CAP] (the daily-cap upsell
 * dialog) and [SEARCH_GATE] (the search `403` Premium gate); [USERNAME] is a reserved-but-unwired
 * forward-compat value for the in-flight premium-username-customization (#301), which adds its own
 * entry point — this change does NOT navigate to the paywall from a username surface.
 */
@Serializable
enum class PaywallEntry {
    LIKE_CAP,
    SEARCH_GATE,
    USERNAME,
}

/**
 * Premium paywall surface (the `mobile-paywall` capability, mockup frame 17), opened from the
 * daily-cap upsell dialog and the search Premium gate. Pushed onto the ROOT back stack (overlaying the
 * section bar — the same mechanism [PostDetailRoute] / [SearchRoute] use). A payload-carrying
 * `@Serializable data class`, so it MUST be registered in the `navSavedStateConfiguration` polymorphic
 * `SerializersModule` for the iOS-saveable back stack.
 *
 * Carries ONLY the non-PII [entry] enum (which gated surface opened it) — NEVER a token, user id, or
 * coordinate (the back stack persists to disk on iOS; the same PII discipline [PostDetailRoute] /
 * [AgeGateRoute] follow).
 */
@Serializable
data class PaywallRoute(
    val entry: PaywallEntry,
) : NavKey
