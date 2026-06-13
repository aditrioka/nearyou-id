## MODIFIED Requirements

### Requirement: Global post card opens post detail via a hoisted onOpenPost lambda

The Global post card (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, and `distanceM = null` since Global has no spatial filter) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` SHALL remain navigation-free. As of `mobile-inline-post-actions` the card's action row is wired on this surface: the like affordance routes to the shared inline-like path (§ "Inline like on Global cards reuses the shared controller and like seam") and the reply affordance invokes a hoisted `onOpenPostReply(...)` lambda carrying the SAME non-PII display fields with `distanceM = null` (wired by `mobile-home-tab-host` to push `PostDetailRoute` with `focusReplyComposer = true`); the whole-card `onOpenPost` keeps pushing with the default `focusReplyComposer = false`. NO distance is rendered or passed (Global has no distance), consistent with `mobile-global-timeline` § "Post card renders only API-returned display fields, no distance, no PII". As of `mobile-profile-screen` the card's **identity header** is a separate tap target (per `mobile-post-card` § "Whole-card tap opens the detail; the identity header opens the author's profile"): `GlobalTimelineScreen` SHALL hoist an `onOpenProfile(authorUserId: String)` lambda and bind each card's parameterless `onOpenProfile` to `{ onOpenProfile(post.authorUserId) }`, supplying the `authorUserId` it already parses from the Global DTO (`author_user_id`, never rendered, never serialized into the card or `PostDetailRoute` payload). The screen-level `onOpenProfile` is a host-level callback (wired by `mobile-home-tab-host` to push `ProfileRoute(authorUserId)` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` stays navigation-free.

#### Scenario: Tapping a Global card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Global feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped (outside the action row and the identity header)
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` with `distanceM = null` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: Tapping the reply affordance invokes the reply-shortcut callback, not the whole-card open

- **GIVEN** the Global feed composed with a loaded post and recording `onOpenPost` + `onOpenPostReply` callbacks
- **WHEN** the card's reply affordance is tapped
- **THEN** `onOpenPostReply` fires exactly once carrying the same non-PII display fields with `distanceM = null` AND `onOpenPost` does NOT fire

#### Scenario: Tapping a Global card's identity header invokes onOpenProfile with the author id

- **GIVEN** the Global feed composed with a loaded post whose `author_user_id = "11111111-1111-1111-1111-111111111111"` and a recording screen-level `onOpenProfile` callback
- **WHEN** the card's identity header (avatar/name/handle) is tapped
- **THEN** the screen-level `onOpenProfile` fires exactly once carrying `authorUserId = "11111111-1111-1111-1111-111111111111"` AND `onOpenPost` does NOT fire AND the UUID is not rendered in the card tree

#### Scenario: GlobalTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the card-tap, reply-shortcut, and identity-tap are delivered via the hoisted `onOpenPost` / `onOpenPostReply` / `onOpenProfile` lambdas only; the screen holds no back-stack reference and performs no back-stack push of its own
