# mobile-global-timeline — Delta Specification

## MODIFIED Requirements

### Requirement: Response DTOs mirror the SHIPPED Global wire and carry no distance

`GlobalTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`GlobalPostDto` / `GlobalResponse`) and `Upsell` — which is **mixed-case, NOT uniformly snake_case**, and which has **NO `distanceM`** (Global has no spatial filter). The mobile DTOs MUST be generated from that shipped source, NOT from any spec's snake_case JSON example. Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `authorUsername` (String), `authorDisplayName` (String), `content`, `latitude` (Double), `longitude` (Double), `createdAt` (String). There SHALL be NO `distanceM` field.
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null` (bare `soft: Boolean = false`, `hard: Boolean = false`).

The `authorUsername` / `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) are required non-null `String`s — the backend sends them on every post (NOT NULL since V2) and mobile + backend land in the same squash-merge. The optional `upsell` object and `nextCursor` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)`). `latitude`/`longitude` are NOT rendered as raw coordinates and, since Global has no distance, the card renders no distance string and `:shared:distance` `DistanceRenderer` is NOT invoked on this surface.

#### Scenario: Full Global post shape parses against the shipped mixed-case, distance-less wire

- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED Global wire keys (`authorUserId`, `authorUsername`, `authorDisplayName`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake; NO `distanceM`) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount`, `authorUsername`, and `authorDisplayName` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption

- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `author_username` / `author_display_name` / `created_at` / top-level `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: No distance field is defined or rendered

- **WHEN** inspecting the `GlobalPostDto` definition and `GlobalTimelineScreen` card
- **THEN** `GlobalPostDto` declares no `distanceM` field AND the card invokes no `DistanceRenderer` and renders no distance string

### Requirement: Post card renders only API-returned display fields, no distance, no PII

`GlobalTimelineScreen` and its cards (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL render only display fields returned by the API: the author **display identity** (`authorDisplayName`, the `authorUsername` handle — per `docs/02-Product.md` § Global Timeline, the city name shows under the author), `content`, `city_name`, the `created_at` value, and the read-only `liked_by_viewer` + `reply_count`. No distance is rendered (Global has no spatial filter — the shared card receives `distanceM = null` on this surface). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging). An empty `city_name = ""` (the backend's never-null empty-string convention) SHALL render without the city label (no crash, no literal `""`).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree while display identity is

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "dewi.kuliner"`, `authorDisplayName = "Dewi Lestari"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND NO distance string AND contains the "Dewi Lestari" display-name node and the "@dewi.kuliner" handle node

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""`
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)

### Requirement: Global post card opens post detail via a hoisted onOpenPost lambda

The Global post card (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, and `distanceM = null` since Global has no spatial filter) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` SHALL remain navigation-free. The card gains NO inline like/reply control and NO distance is rendered or passed (Global has no distance), consistent with `mobile-global-timeline` § "Post card renders only API-returned display fields, no distance"; the author identity is NOT a separate tap target (per `mobile-post-card` § "Whole-card tap is the single interactive affordance and identity is not separately tappable").

#### Scenario: Tapping a Global card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Global feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` with `distanceM = null` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: GlobalTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own
