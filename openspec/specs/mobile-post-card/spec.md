# mobile-post-card Specification

## Purpose

`mobile-post-card` is the ONE shared timeline post-card contract for `:mobile:app` — the `ui/components/` composable (docs/11 § 2.1 reuse-first) that Nearby and Global consume today and that future feed/profile/search surfaces MODIFY-by-consume, ending the per-screen card duplication the 2026-06-10 audit flagged (item 05-#11: `NearbyPostCard` / `GlobalPostCard` / `PostHeader` near-verbatim copies that drifted). It pins the card's look and behavior to the canonical mockup (frames 1 + 19 of `dev/mockups/nearyou-screens-mockup.html`, per docs/11 § 2.8): a deterministic letter avatar on `NearYouTheme` tonal containers, the author display identity (display name + @handle — the product-spec'd header, `docs/02-Product.md:176`), time as text, content, the coral location-pin meta row with the shared `DistanceRenderer`, and the interactive action row (inline like + reply shortcut, `mobile-inline-post-actions`). It also carries the card-level safety rules: the author UUID and raw coordinates are structurally absent from the rendered model, and the whole card opens the detail while the action row's like + reply affordances are the only other tap targets (no dead controls — the send-message action stays deferred to chat, and profile navigation + media are separate deferred capabilities).
## Requirements
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

- An **identity header row**: the letter avatar (per § "Letter avatar derivation is deterministic"), the author's **display name** (prominent), the **@username handle** (sourced via a `stringResource` format — the `@` prefix is not hardcoded in Kotlin), and the post **time label** (the existing date-label treatment; relative "5 mnt"-style formatting remains deferred to `mobile-timeline-relative-timestamp`). The display-name and handle texts render **single-line with ellipsis overflow**, so maximal-length identities (V2 maxima: 50-char display name, 60-char username) cannot wrap or push the time label out of the header.
- The post **content** text.
- A **location meta row**: the coral location pin (tint `locationPin`) + `city_name` (when non-empty) + the distance string via `DistanceRenderer.render(distanceM)` when a non-null `distanceM` is supplied (Nearby); Global supplies `null` and renders no distance. When `city_name` is empty AND `distanceM` is null, the location meta row (including the pin) SHALL be omitted entirely (no orphan pin icon).
- The **action row** per § "Action row renders interactive reply and like affordances per mockup frame 1".

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

#### Scenario: Maximal-length identity stays single-line and does not break the header

- **WHEN** the card is rendered with a 50-character `authorDisplayName` and a 60-character `authorUsername` (the V2 column maxima)
- **THEN** the display-name and handle nodes render single-line with ellipsis (no wrap) AND the time label remains visible in the header row

### Requirement: Letter avatar derivation is deterministic

The card's avatar SHALL be a **letter avatar** (no profile-photo capability exists yet): the initial(s) of `authorDisplayName` — the first Unicode code point of the first whitespace-separated word plus the first code point of the last word (a single-word name yields one code point), uppercased — centered on a circular container. Word splitting SHALL discard empty segments (leading/trailing/consecutive whitespace is safe), and a blank or whitespace-only display name SHALL yield empty initials — the avatar renders its container with no glyph, no crash (`users.display_name` is NOT NULL but carries no non-empty CHECK, and `PostDetailRoute` defaults the field to `""`). The container/content colors SHALL be a **deterministic** mapping from `authorUsername` (stable across recompositions, feeds, and sessions) onto the Material 3 tonal container token pairs of `NearYouTheme` (`primaryContainer`/`onPrimaryContainer`, `secondaryContainer`/`onSecondaryContainer`, `tertiaryContainer`/`onTertiaryContainer`) — never color literals. The derivation SHALL be a pure commonMain function unit-testable without composing UI.

#### Scenario: Two-word and single-word initials

- **WHEN** deriving initials for `authorDisplayName = "Budi Santoso"` and for `"Raka"`
- **THEN** the results are "BS" and "R" respectively

#### Scenario: Same username always yields the same container pair

- **WHEN** the avatar color mapping is evaluated twice for `authorUsername = "dewi.kuliner"`
- **THEN** both evaluations select the same tonal container token pair

#### Scenario: Surrogate-pair-leading names do not crash

- **WHEN** deriving initials for a display name whose first word begins with a non-BMP character (e.g. an emoji)
- **THEN** derivation returns the full first code point (no broken surrogate half, no exception)

#### Scenario: Blank and irregular-whitespace names are safe

- **WHEN** deriving initials for `""`, for `"   "`, and for `" Budi  Santoso"` (leading + consecutive spaces)
- **THEN** the first two yield empty initials (avatar container renders with no glyph, no crash) AND the third yields "BS" (empty segments discarded)

### Requirement: Card renders no author UUID and no raw coordinates

The rendered card tree SHALL NOT contain the author's `author_user_id` UUID nor any raw `latitude`/`longitude` value — only the display identity (`authorDisplayName`, the @-handle), `city_name`, and the `DistanceRenderer` string represent author/location. The component SHALL NOT log post fields.

#### Scenario: UUID and coordinates absent from the rendered tree

- **GIVEN** a post authored by `author_user_id = "11111111-1111-1111-1111-111111111111"` at `latitude = -6.21`, `longitude = 106.85` with `authorUsername = "raka.jkt"`
- **WHEN** the card is rendered
- **THEN** the tree contains NO node whose text contains "11111111-1111-1111-1111-111111111111", "-6.21", or "106.85" AND contains the "@raka.jkt" handle node

### Requirement: Action row renders interactive reply and like affordances per mockup frame 1

The card's bottom row SHALL be the interactive **action row** per the canonical mockup (frame 1, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8 — values are dp intent on the 4dp grid; the M3 affordance owns its metrics):

- A **reply affordance** (left): the reply icon + the `replyCount` value rendered as ONE tappable unit, invoking a hoisted `onReplyShortcut: () -> Unit` callback.
- A **like affordance** (to its right — the row's last action while the send action is deferred, see § "Send-message card action is deferred"): the like icon ONLY — filled + `locationPin`-tinted when `likedByViewer = true`, outlined + `onSurfaceVariant`-muted otherwise (the existing treatment, keeping the existing filled/outlined like-icon test tags) — invoking a hoisted `onToggleLike: () -> Unit` callback. NO numeric like count is rendered: the timeline wire carries no like count — the known, deliberate divergence from the mockup's `favorite 12` action, carried over from the replaced counts-row requirement.

Both affordances SHALL be real interactive controls: click semantics with press indication (ripple), minimum 48dp touch targets (the M3 minimum — the mockup's 20dp glyph + 8dp padding is dp intent, not the touch contract), and a `contentDescription` sourced via `stringResource` (they are interactive now — the prior decorative `contentDescription = null` posture no longer applies to them). The like affordance SHALL additionally expose its toggled state to accessibility services — state-bearing semantics (toggleable/`stateDescription` or a state-dependent content description, all strings via `stringResource`) — so the liked/not-liked state is announced, not visual-only. The liked state MUST NOT be communicated by color alone — the filled-vs-outlined icon shape carries it visually and the accessible state carries it non-visually. Activating an affordance MUST NOT trigger the whole-card `onOpen`. The card stays presentation-only: both callbacks are hoisted; the card holds no like state machine, no repository reference, and no navigation reference — the rendered like state remains driven solely by the `likedByViewer` value on the supplied model.

#### Scenario: Action row exposes exactly two click targets and routes them to the right callbacks

- **GIVEN** a rendered card with recording `onOpen` / `onToggleLike` / `onReplyShortcut` callbacks
- **WHEN** the semantics tree is inspected, then the like affordance is tapped, then the reply affordance is tapped
- **THEN** the tree contains exactly three clickable nodes (the card itself, the like affordance, the reply affordance) AND the like tap fires `onToggleLike` exactly once with `onOpen` NOT fired AND the reply tap fires `onReplyShortcut` exactly once with `onOpen` NOT fired

#### Scenario: Liked state switches the like affordance treatment

- **WHEN** the card is rendered with `likedByViewer = true` and again with `false`
- **THEN** the like affordance shows the filled/accent (`locationPin`) variant in the first render and the outlined/muted variant in the second

#### Scenario: Reply affordance shows the reply count and no like count is rendered

- **GIVEN** a post with `replyCount = 4`
- **WHEN** the card is rendered
- **THEN** the reply affordance contains the text "4" AND the action row contains no other numeric count node (no like count anywhere on the card)

#### Scenario: Affordances carry stringResource content descriptions

- **WHEN** the card is rendered and the like + reply affordance nodes are inspected
- **THEN** each exposes a content description resolved from a `Res.string` entry AND no hardcoded UI string literal appears in `PostCard.kt` (Compose Multiplatform Resources only, unchanged)

#### Scenario: The like affordance announces its toggled state

- **WHEN** the card is rendered with `likedByViewer = true` and again with `false`, inspecting the like affordance's accessibility semantics
- **THEN** the two renders expose distinguishable accessible state (toggle state / state description / state-dependent description — sourced via `stringResource`), so the liked state is not visual-only

### Requirement: Whole-card tap opens the detail and identity is not separately tappable

The card SHALL invoke a hoisted `onOpen` callback when tapped anywhere on the card OUTSIDE the two action-row affordances (including over the avatar/name region). The action-row affordances (§ "Action row renders interactive reply and like affordances per mockup frame 1") are the ONLY interactive sub-controls on the card; the avatar and author identity SHALL NOT be separate tap targets (no profile screen exists yet — issue [#196](https://github.com/aditrioka/nearyou-id/issues/196); per the no-dead-controls rule nothing on the card may look or act tappable without a wired destination). The card itself SHALL NOT hold navigation references; navigation wiring stays with the host screens per their specs.

#### Scenario: Tapping the avatar region triggers the whole-card open

- **GIVEN** a rendered card with a recording `onOpen` callback
- **WHEN** the test taps on the avatar/identity region
- **THEN** `onOpen` fires exactly once (the same whole-card action) AND the semantics tree contains no separate clickable node for the avatar or author name

### Requirement: Send-message card action is deferred

The action row SHALL render exactly TWO affordances — reply and like. The send-message (kirim pesan) action shown in mockup frame 1 (the `send` glyph between `mode_comment` and `favorite`) SHALL NOT be rendered in any form — no icon node, no disabled placeholder, no third action slot: no 1:1 chat surface exists in `:mobile:app` yet, and an unwired send control would violate the no-dead-controls rule. This requirement is the deliberate, spec-recorded divergence from frame 1 AND the explicit MODIFY hook for the future mobile chat change: that change SHALL MODIFY this requirement to render the send action (between reply and like, per frame 1) wired to the chat entry point with the post embed (`docs/02-Product.md` § Chat Context Card UX, mockup frames 5–6). GitHub issue [#238](https://github.com/aditrioka/nearyou-id/issues/238) `mobile-post-card-send-message-action` (label `follow-up`) tracks the deferral.

#### Scenario: No send affordance is rendered

- **WHEN** the card is rendered and its semantics tree plus `PostCard.kt` source are inspected
- **THEN** the action row contains exactly the reply and like affordances AND no send/kirim-pesan icon node, disabled control, or third action slot exists

#### Scenario: The mockup divergence is tracked, not silently dropped

- **WHEN** comparing mockup frame 1's three-action row (reply / send / like) against the shipped card AND inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** the absent send action corresponds to THIS deferred requirement (the future chat change's MODIFY hook) AND GitHub issue [#238](https://github.com/aditrioka/nearyou-id/issues/238) (label `follow-up`) tracks `mobile-post-card-send-message-action` — it is NOT an undocumented mockup deviation

