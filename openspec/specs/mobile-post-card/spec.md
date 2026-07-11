# mobile-post-card Specification

## Purpose

`mobile-post-card` is the ONE shared timeline post-card contract for `:mobile:app` — the `ui/components/` composable (docs/11 § 2.1 reuse-first) that Nearby and Global consume today and that future feed/profile/search surfaces MODIFY-by-consume, ending the per-screen card duplication the 2026-06-10 audit flagged (item 05-#11: `NearbyPostCard` / `GlobalPostCard` / `PostHeader` near-verbatim copies that drifted). It pins the card's look and behavior to the canonical mockup (frames 1 + 19 of `dev/mockups/nearyou-screens-mockup.html`, per docs/11 § 2.8): a deterministic letter avatar on `NearYouTheme` tonal containers, the author display identity (display name + @handle — the product-spec'd header, `docs/02-Product.md:159`), time as text, content, the coral location-pin meta row with the shared `DistanceRenderer`, and the interactive action row (inline like + reply shortcut, `mobile-inline-post-actions`). It also carries the card-level safety rules: the author UUID and raw coordinates are structurally absent from the rendered model, and the whole card opens the detail while the action row's like + reply affordances are the only other tap targets (no dead controls — the send-message action stays deferred to chat, and profile navigation + media are separate deferred capabilities).
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
- An optional **attached image**: when the card model supplies a non-null `imageUrl`, the card SHALL render the image below the content via the async image loader (Coil 3), with an aspect-ratio placeholder and graceful failure (no error chrome) per the docs/02 § 6 delivery rules — no preload during scroll, on-screen render only. The image element SHALL carry a meaningful `contentDescription` sourced via `stringResource` (an accessibility alt-text label for the attached post image — not a hardcoded literal, not null/empty). When `imageUrl` is null the card renders no image element and is visually identical to the pre-image baseline.
- A **location meta row**: the coral location pin (tint `locationPin`) + `city_name` (when non-empty) + the distance string via `DistanceRenderer.render(distanceM)` when a non-null `distanceM` is supplied (Nearby); Global supplies `null` and renders no distance. When `city_name` is empty AND `distanceM` is null, the location meta row (including the pin) SHALL be omitted entirely (no orphan pin icon).
- The **action row** per § "Action row renders interactive reply and like affordances per mockup frame 1".

The card model/API SHALL NOT accept the author UUID or raw `latitude`/`longitude` (the fields do not exist on the rendered model), so the card structurally cannot render them. The card model MAY accept a public `imageUrl: String?` (the coordinate-independent delivery URL) — this is not PII.

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

#### Scenario: Card renders the attached image when imageUrl is present, and nothing when absent

- **WHEN** the card is rendered once with a non-null `imageUrl` and once with `imageUrl = null`
- **THEN** the first render contains an async image node below the content carrying a non-empty `contentDescription` sourced from `Res.string.*` (accessibility alt text) AND the second render contains no image element (the no-image card is unchanged from the pre-image baseline)

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

- **GIVEN** a rendered card with `onReport = null` and recording `onOpen` / `onToggleLike` / `onReplyShortcut` callbacks
- **WHEN** the semantics tree is inspected, then the like affordance is tapped, then the reply affordance is tapped
- **THEN** the tree contains exactly four clickable nodes (the card itself, the identity header — § "Whole-card tap opens the detail; the identity header opens the author's profile" — the like affordance, and the reply affordance; the prior "exactly three" wording predated the tappable identity header and is corrected here to match the shipped contract) AND the like tap fires `onToggleLike` exactly once with `onOpen` NOT fired AND the reply tap fires `onReplyShortcut` exactly once with `onOpen` NOT fired

#### Scenario: A supplied report action adds exactly the kebab as a fifth click target

- **GIVEN** a rendered card with a non-null `onReport` and recording callbacks
- **WHEN** the semantics tree is inspected (menu closed)
- **THEN** the interactive targets are exactly five: the card itself, the identity header, the like affordance, the reply affordance, and the overflow kebab (§ "Optional overflow kebab per mockup frame 1")

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

### Requirement: Send-message card action is deferred

The action row SHALL render exactly TWO affordances — reply and like. The send-message (kirim pesan) action shown in mockup frame 1 (the `send` glyph between `mode_comment` and `favorite`) SHALL NOT be rendered in any form — no icon node, no disabled placeholder, no third action slot: no 1:1 chat surface exists in `:mobile:app` yet, and an unwired send control would violate the no-dead-controls rule. This requirement is the deliberate, spec-recorded divergence from frame 1 AND the explicit MODIFY hook for the future mobile chat change: that change SHALL MODIFY this requirement to render the send action (between reply and like, per frame 1) wired to the chat entry point with the post embed (`docs/02-Product.md` § Chat Context Card UX, mockup frames 5–6). GitHub issue [#238](https://github.com/aditrioka/nearyou-id/issues/238) `mobile-post-card-send-message-action` (label `follow-up`) tracks the deferral.

#### Scenario: No send affordance is rendered

- **WHEN** the card is rendered and its semantics tree plus `PostCard.kt` source are inspected
- **THEN** the action row contains exactly the reply and like affordances AND no send/kirim-pesan icon node, disabled control, or third action slot exists

#### Scenario: The mockup divergence is tracked, not silently dropped

- **WHEN** comparing mockup frame 1's three-action row (reply / send / like) against the shipped card AND inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** the absent send action corresponds to THIS deferred requirement (the future chat change's MODIFY hook) AND GitHub issue [#238](https://github.com/aditrioka/nearyou-id/issues/238) (label `follow-up`) tracks `mobile-post-card-send-message-action` — it is NOT an undocumented mockup deviation

### Requirement: Whole-card tap opens the detail; the identity header opens the author's profile

The card SHALL invoke a hoisted `onOpen` callback when tapped anywhere on the card OUTSIDE the identity header, the two action-row affordances, and the optional overflow kebab (§ "Optional overflow kebab per mockup frame 1"). The **identity header** (the letter avatar + display name + @handle region) SHALL be a separate tap target invoking a hoisted **`onOpenProfile: () -> Unit`** callback (parameterless at the card boundary — the card holds NO author UUID; the host binds the target user id by closure, per `mobile-nearby-timeline` / `mobile-global-timeline`). Activating the identity header MUST NOT also fire the whole-card `onOpen`. The action-row affordances (§ "Action row renders interactive reply and like affordances per mockup frame 1") and the optional overflow kebab remain the only other interactive sub-controls. The card itself SHALL NOT hold navigation references and SHALL NOT carry or render the author UUID; navigation wiring (resolving the author id and building `ProfileRoute`) stays with the host screens per their specs. This supersedes the prior "identity is not separately tappable (no profile screen exists yet — issue [#196](https://github.com/aditrioka/nearyou-id/issues/196))" posture now that the profile screen ships (`mobile-profile`).

#### Scenario: Tapping the identity header fires onOpenProfile, not the whole-card open

- **GIVEN** a rendered card with recording `onOpen` / `onOpenProfile` callbacks
- **WHEN** the test taps on the avatar/display-name/handle identity region
- **THEN** `onOpenProfile` fires exactly once AND `onOpen` does NOT fire

#### Scenario: Tapping the card body outside identity and actions fires onOpen

- **GIVEN** a rendered card with recording `onOpen` / `onOpenProfile` / `onToggleLike` / `onReplyShortcut` callbacks
- **WHEN** the test taps the card content region (outside the identity header and the action row)
- **THEN** `onOpen` fires exactly once AND `onOpenProfile` / `onToggleLike` / `onReplyShortcut` do NOT fire

### Requirement: Optional overflow kebab per mockup frame 1

The card SHALL accept an optional hoisted report action (`onReport: (() -> Unit)? = null`). When non-null, the card SHALL render an overflow kebab (`more_vert`) trailing the identity header row — the mockup frame-1 `.post .head .more` placement (`dev/mockups/nearyou-screens-mockup.html`; 20dp glyph in a muted `onSurfaceVariant` treatment; the M3 `IconButton` owns the ≥48dp touch metrics) — opening a `DropdownMenu` whose single item "Laporkan" (resource `profile_report_action`) invokes `onReport`. When `onReport` is null the kebab SHALL NOT be rendered in any form (no icon node, no disabled placeholder) — a menu with zero items would be a dead control, so hosts supply the action only when at least one item applies (the mockup shows the kebab on every card; the null-gated absence on own posts / non-feed hosts is the deliberate, spec-recorded divergence until more menu items exist). The kebab SHALL be a separate tap target: activating it (or a menu item) MUST NOT fire the whole-card `onOpen` nor the identity header's `onOpenProfile`. Its `contentDescription` SHALL come via `stringResource`. The card stays presentation-only and PII-free: the callback is hoisted and parameterless, and NO author UUID is introduced on `PostCardModel`.

#### Scenario: Kebab renders and routes when a report action is supplied

- **GIVEN** a rendered card with a recording `onReport` (plus recording `onOpen` / `onOpenProfile`)
- **WHEN** the kebab is tapped and the "Laporkan" menu item is selected
- **THEN** `onReport` fires exactly once AND `onOpen` and `onOpenProfile` do NOT fire

#### Scenario: No kebab when the action is absent

- **WHEN** the card is rendered with `onReport = null` (the default)
- **THEN** the tree contains no kebab icon node and no overflow menu — byte-identical affordance surface to the pre-kebab card

