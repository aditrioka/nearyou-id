## ADDED Requirements

### Requirement: Global post card opens post detail via a hoisted onOpenPost lambda

The Global post card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, and `distanceM = null` since Global has no spatial filter) — and explicitly NOT `latitude`/`longitude`. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` SHALL remain navigation-free. The card gains NO inline like/reply control and NO distance is rendered or passed (Global has no distance), consistent with `mobile-global-timeline` § "Post card renders only API-returned display fields, no distance".

#### Scenario: Tapping a Global card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Global feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount` with `distanceM = null` AND the payload contains no `latitude`/`longitude`

#### Scenario: GlobalTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own
