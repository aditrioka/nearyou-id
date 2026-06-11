# mobile-nearby-timeline — Delta Specification

## MODIFIED Requirements

### Requirement: Response DTOs mirror the SHIPPED wire casing and render distance via DistanceRenderer

`NearbyTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`NearbyPostDto` / `NearbyResponse`) and `Upsell.kt` — which is **mixed-case, NOT uniformly snake_case**. The mobile DTOs MUST be generated from that shipped source, NOT from any stale snake_case JSON example. Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `authorUsername` (String), `authorDisplayName` (String), `content`, `latitude` (Double), `longitude` (Double), `distanceM` (Double), `createdAt` (String).
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null`.
- `UpsellDto`: bare `soft: Boolean = false`, `hard: Boolean = false`.

The `authorUsername` / `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) are required non-null `String`s — the backend sends them on every post (NOT NULL since V2) and mobile + backend land in the same squash-merge. The optional `upsell` object and `nextCursor` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)` so absent → default). The user-facing distance string SHALL be produced by `DistanceRenderer.render(distanceM)` from `:shared:distance` (NOT reimplemented locally); `latitude`/`longitude` are display-only and MUST NOT be rendered as raw coordinates.

#### Scenario: Full post shape parses against the shipped mixed-case wire

- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED wire keys (`authorUserId`, `authorUsername`, `authorDisplayName`, `distanceM`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `distanceM`, `createdAt`, `likedByViewer`, `replyCount`, `authorUsername`, and `authorDisplayName` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption

- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `author_username` / `author_display_name` / `distance_m` / `created_at` / top-level `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: Distance is rendered through the shared renderer at the card level

- **WHEN** a post card with `distanceM = 1234.5` is rendered AND a post card with `distanceM = 7600.0` is rendered
- **THEN** the rendered card tree contains a node whose text is `DistanceRenderer.render(1234.5)` = "5km" AND a node whose text is `DistanceRenderer.render(7600.0)` = "8km" respectively (asserted at the rendered-card level, NOT only via the `:shared:distance` module's own unit test — confirming the card consumes the shared renderer rather than a locally-reimplemented format)

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""` (the backend's never-null empty-string convention)
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)

### Requirement: No author identifier or coordinate is rendered or logged

`NearbyTimelineScreen` and its cards SHALL render only display fields returned by the API: the author **display identity** (`authorDisplayName`, the `authorUsername` handle — rendered via the shared `mobile-post-card` component as of `mobile-timeline-card-redesign`, per `docs/02-Product.md` § Timeline Features), `content`, `city_name`, the `DistanceRenderer` string, the `created_at` treatment, and the read-only `liked_by_viewer` + `reply_count`. The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree while display identity is

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "raka.jkt"`, `authorDisplayName = "Raka Pratama"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND contains the "Raka Pratama" display-name node and the "@raka.jkt" handle node (only the `DistanceRenderer` string + `city_name` represent location)

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. The card gains NO inline like/reply control (those are deferred per `mobile-post-detail` § "Inline-card like and reply shortcuts are deferred") and the author identity is NOT a separate tap target (per `mobile-post-card` § "Whole-card tap is the single interactive affordance and identity is not separately tappable").

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own
