# mobile-post-card — Delta Specification

## ADDED Requirements

### Requirement: One shared post-card composable lives in ui/components and is the only timeline card implementation

The mobile app SHALL ship a single shared post-card composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/PostCard.kt`) — the first occupant of the docs/11 § 2.1 target-shape `ui/components/` package (design-system composables shared by ≥2 screens). `NearbyTimelineScreen` and `GlobalTimelineScreen` SHALL render their feed items EXCLUSIVELY through this composable; the per-screen `NearbyPostCard` / `GlobalPostCard` duplicates SHALL be deleted (absorbing the post-card half of audit item 05-#11 — the list-state kit half is explicitly NOT part of this capability). Future feed/profile/search surfaces SHALL consume this composable (MODIFY-by-consuming, the `mobile-design-system` pattern) rather than re-deriving a card. The composable SHALL be built entirely from `NearYouTheme` tokens (no color/typography literals) and SHALL render correctly under both light and dark schemes. No hardcoded UI string literals SHALL appear in the component source (Compose Multiplatform Resources only).

#### Scenario: Nearby and Global render through the shared card and the local copies are gone

- **WHEN** inspecting `screens/timeline/NearbyTimelineScreen.kt`, `screens/timeline/GlobalTimelineScreen.kt`, and `ui/components/PostCard.kt`
- **THEN** both screens render feed items via the `ui/components` post-card composable AND neither screen file declares its own card composable (`NearbyPostCard` / `GlobalPostCard` no longer exist)

#### Scenario: Card renders under both schemes from theme tokens

- **WHEN** the card is composed under `NearYouTheme` light and under `NearYouTheme` dark with the same post
- **THEN** it renders without crash in both AND the component source contains no hex color literals (theme tokens only)

### Requirement: Card layout renders identity header, content, and location meta per mockup frames 1 and 19

The card SHALL render, per the canonical mockup (frames 1 + 19, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8 — behavior governed by this spec):

- An **identity header row**: the letter avatar (per § "Letter avatar derivation is deterministic"), the author's **display name** (prominent), the **@username handle** (sourced via a `stringResource` format — the `@` prefix is not hardcoded in Kotlin), and the post **time label** (the existing date-label treatment; relative "5 mnt"-style formatting remains deferred to `mobile-timeline-relative-timestamp`).
- The post **content** text.
- A **location meta row**: the coral location pin (tint `locationPin`) + `city_name` (when non-empty) + the distance string via `DistanceRenderer.render(distanceM)` when a non-null `distanceM` is supplied (Nearby); Global supplies `null` and renders no distance. When `city_name` is empty AND `distanceM` is null, the location meta row (including the pin) SHALL be omitted entirely (no orphan pin icon).
- The **read-only counts row** per § "Counts row is read-only with no interactive sub-controls".

The card model/API SHALL NOT accept the author UUID or raw `latitude`/`longitude` (the fields do not exist on the rendered model), so the card structurally cannot render them.

#### Scenario: Identity header renders display name, handle, and time

- **GIVEN** a post with `authorDisplayName = "Raka Pratama"`, `authorUsername = "raka.jkt"`
- **WHEN** the card is rendered
- **THEN** the tree contains a node with text "Raka Pratama" AND a node whose text is the handle format applied to "raka.jkt" (renders as "@raka.jkt") AND a node with the post's time label

#### Scenario: Nearby variant renders city and distance; Global variant renders city only

- **WHEN** the card is rendered with `cityName = "Jakarta Selatan"`, `distanceM = 5400.0` AND again with `cityName = "Jakarta Selatan"`, `distanceM = null`
- **THEN** the first render contains the pin + "Jakarta Selatan" + `DistanceRenderer.render(5400.0)` AND the second render contains the pin + "Jakarta Selatan" and NO distance string

#### Scenario: Empty city and null distance hide the location row

- **WHEN** the card is rendered with `cityName = ""` and `distanceM = null`
- **THEN** the tree contains no location-pin icon node and no empty-string city text (the meta row is absent, no crash)

### Requirement: Letter avatar derivation is deterministic

The card's avatar SHALL be a **letter avatar** (no profile-photo capability exists yet): the initial(s) of `authorDisplayName` — the first Unicode code point of the first whitespace-separated word plus the first code point of the last word (a single-word name yields one code point), uppercased — centered on a circular container. The container/content colors SHALL be a **deterministic** mapping from `authorUsername` (stable across recompositions, feeds, and sessions) onto the Material 3 tonal container token pairs of `NearYouTheme` (`primaryContainer`/`onPrimaryContainer`, `secondaryContainer`/`onSecondaryContainer`, `tertiaryContainer`/`onTertiaryContainer`) — never color literals. The derivation SHALL be a pure commonMain function unit-testable without composing UI.

#### Scenario: Two-word and single-word initials

- **WHEN** deriving initials for `authorDisplayName = "Budi Santoso"` and for `"Raka"`
- **THEN** the results are "BS" and "R" respectively

#### Scenario: Same username always yields the same container pair

- **WHEN** the avatar color mapping is evaluated twice for `authorUsername = "dewi.kuliner"`
- **THEN** both evaluations select the same tonal container token pair

#### Scenario: Surrogate-pair-leading names do not crash

- **WHEN** deriving initials for a display name whose first word begins with a non-BMP character (e.g. an emoji)
- **THEN** derivation returns the full first code point (no broken surrogate half, no exception)

### Requirement: Counts row is read-only with no interactive sub-controls

The card SHALL render the read-only engagement state: the like affordance icon (filled + accent-tinted when `likedByViewer = true`, outlined + muted otherwise) and the reply icon + `replyCount` value. These SHALL remain **non-interactive** — no `onClick`, no button semantics, no ripple on the icons/counts; the whole-card tap is the card's only interactive target (inline like/reply/send actions are deferred to `mobile-inline-post-actions`, issue #201; a numeric like count is NOT rendered — the timeline wire carries no like count, a known divergence from the mockup's action row). The icons are decorative within a read-only row (`contentDescription = null`).

#### Scenario: Counts row exposes no click targets

- **WHEN** the card is rendered and its semantics tree is inspected
- **THEN** the like/reply icon nodes and the reply-count node have no click action; the only clickable node is the card itself

#### Scenario: Liked state switches the like icon treatment

- **WHEN** the card is rendered with `likedByViewer = true` and again with `false`
- **THEN** the like icon is the filled/accent variant in the first render and the outlined/muted variant in the second

### Requirement: Whole-card tap is the single interactive affordance and identity is not separately tappable

The card SHALL invoke a hoisted `onOpen` callback when tapped anywhere on the card (including over the avatar/name region). The avatar and author identity SHALL NOT be separate tap targets (no profile screen exists yet — issue #196; per the no-dead-controls rule nothing on the card may look or act tappable without a wired destination). The card itself SHALL NOT hold navigation references; navigation wiring stays with the host screens per their specs.

#### Scenario: Tapping the avatar region triggers the whole-card open

- **GIVEN** a rendered card with a recording `onOpen` callback
- **WHEN** the test taps on the avatar/identity region
- **THEN** `onOpen` fires exactly once (the same whole-card action) AND the semantics tree contains no separate clickable node for the avatar or author name

### Requirement: Card renders no author UUID and no raw coordinates

The rendered card tree SHALL NOT contain the author's `author_user_id` UUID nor any raw `latitude`/`longitude` value — only the display identity (`authorDisplayName`, the @-handle), `city_name`, and the `DistanceRenderer` string represent author/location. The component SHALL NOT log post fields.

#### Scenario: UUID and coordinates absent from the rendered tree

- **GIVEN** a post authored by `author_user_id = "11111111-1111-1111-1111-111111111111"` at `latitude = -6.21`, `longitude = 106.85` with `authorUsername = "raka.jkt"`
- **WHEN** the card is rendered
- **THEN** the tree contains NO node whose text contains "11111111-1111-1111-1111-111111111111", "-6.21", or "106.85" AND contains the "@raka.jkt" handle node
