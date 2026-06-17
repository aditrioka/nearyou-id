## MODIFIED Requirements

### Requirement: Response DTOs mirror the SHIPPED wire casing and render distance via DistanceRenderer

`NearbyTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`NearbyPostDto` / `NearbyResponse`) and `Upsell.kt` — which is **mixed-case, NOT uniformly snake_case**. The mobile DTOs MUST be generated from that shipped source, NOT from any stale snake_case JSON example. Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `authorUsername` (String), `authorDisplayName` (String), `content`, `latitude` (Double), `longitude` (Double), `distanceM` (**nullable `Double? = null`** — see below), `createdAt` (String).
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null`.
- `UpsellDto`: bare `soft: Boolean = false`, `hard: Boolean = false`.

`distanceM` MUST be declared **nullable with a default (`Double? = null`)** so that an OMITTED `distanceM` parses successfully. As of the `hide-distance` capability the backend conditionally omits `distanceM` when the symmetric hide-distance rule applies (the prior shipped wire always sent it); a non-null declaration would now throw `MissingFieldException` for an effectively-hidden viewer/author. A present `distanceM` is raw meters; an absent one means "distance hidden" and the card renders city-only.

The `authorUsername` / `authorDisplayName` fields (added by `mobile-timeline-card-redesign`) are required non-null `String`s — the backend sends them on every post (NOT NULL since V2) and mobile + backend land in the same squash-merge. The optional `upsell` object, `nextCursor`, and (as of `hide-distance`) `distanceM` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)` so absent → default). The user-facing distance string SHALL be produced by `DistanceRenderer.render(distanceM)` from `:shared:distance` (NOT reimplemented locally) **only when `distanceM` is non-null**; `latitude`/`longitude` are display-only and MUST NOT be rendered as raw coordinates. The already-null-tolerant `mobile-post-card` (which omits the location distance segment when `distanceM` is null) requires no change to consume an omitted distance.

#### Scenario: Full post shape parses against the shipped mixed-case wire

- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED wire keys (`authorUserId`, `authorUsername`, `authorDisplayName`, `distanceM`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `distanceM`, `createdAt`, `likedByViewer`, `replyCount`, `authorUsername`, and `authorDisplayName` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: A post with an omitted distanceM parses (hide-distance) and renders city-only

- **GIVEN** a MockEngine returning a 200 body whose post object uses the shipped wire keys but OMITS `distanceM` entirely (the backend suppressed it under the hide-distance rule)
- **WHEN** the response is parsed AND the post card is rendered
- **THEN** parsing succeeds with `distanceM = null` (no `MissingFieldException`) AND the rendered card shows the `city_name` and NO distance string

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption

- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `author_username` / `author_display_name` / `distance_m` / `created_at` / top-level `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: Distance is rendered through the shared renderer at the card level

- **WHEN** a post card with `distanceM = 1234.5` is rendered AND a post card with `distanceM = 7600.0` is rendered
- **THEN** the rendered card tree contains a node whose text is `DistanceRenderer.render(1234.5)` = "5km" AND a node whose text is `DistanceRenderer.render(7600.0)` = "8km" respectively (asserted at the rendered-card level, NOT only via the `:shared:distance` module's own unit test — confirming the card consumes the shared renderer rather than a locally-reimplemented format)

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""` (the backend's never-null empty-string convention)
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)
