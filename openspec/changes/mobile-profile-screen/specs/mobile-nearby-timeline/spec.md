## MODIFIED Requirements

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. As of `mobile-inline-post-actions` the card's action row is wired on this surface: the like affordance routes to the inline-like path (§ "Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam") and the reply affordance invokes a hoisted `onOpenPostReply(...)` lambda carrying the SAME non-PII display fields (wired by `mobile-home-tab-host` to push `PostDetailRoute` with `focusReplyComposer = true`); the whole-card `onOpenPost` keeps pushing with the default `focusReplyComposer = false`. As of `mobile-profile-screen` the card's **identity header** is a separate tap target (per `mobile-post-card` § "Whole-card tap opens the detail; the identity header opens the author's profile"): `NearbyTimelineScreen` SHALL hoist an `onOpenProfile(authorUserId: String)` lambda and bind each card's parameterless `onOpenProfile` to `{ onOpenProfile(post.authorUserId) }`, supplying the `authorUserId` it already parses from the Nearby DTO (`author_user_id`, never rendered, never serialized into the card or `PostDetailRoute` payload). The screen-level `onOpenProfile` is a host-level callback (wired by `mobile-home-tab-host` to push `ProfileRoute(authorUserId)` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` stays navigation-free.

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped (outside the action row and the identity header)
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: Tapping the reply affordance invokes the reply-shortcut callback, not the whole-card open

- **GIVEN** the Nearby feed composed with a loaded post and recording `onOpenPost` + `onOpenPostReply` callbacks
- **WHEN** the card's reply affordance is tapped
- **THEN** `onOpenPostReply` fires exactly once carrying the same non-PII display fields AND `onOpenPost` does NOT fire

#### Scenario: Tapping a Nearby card's identity header invokes onOpenProfile with the author id

- **GIVEN** the Nearby feed composed with a loaded post whose `author_user_id = "11111111-1111-1111-1111-111111111111"` and a recording screen-level `onOpenProfile` callback
- **WHEN** the card's identity header (avatar/name/handle) is tapped
- **THEN** the screen-level `onOpenProfile` fires exactly once carrying `authorUserId = "11111111-1111-1111-1111-111111111111"` AND `onOpenPost` does NOT fire AND the UUID is not rendered in the card tree

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap, reply-shortcut, and identity-tap are delivered via the hoisted `onOpenPost` / `onOpenPostReply` / `onOpenProfile` lambdas only; the screen holds no back-stack reference and performs no back-stack push of its own
