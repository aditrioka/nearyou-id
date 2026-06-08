## ADDED Requirements

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`) — and explicitly NOT `latitude`/`longitude`. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. The card gains NO inline like/reply control (those are deferred per `mobile-post-detail` § "Inline-card like and reply shortcuts are deferred").

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount` AND the payload contains no `latitude`/`longitude`

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own
