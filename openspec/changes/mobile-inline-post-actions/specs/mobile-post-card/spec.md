# mobile-post-card — Delta Specification

## ADDED Requirements

### Requirement: Action row renders interactive reply and like affordances per mockup frame 1

The card's bottom row SHALL be the interactive **action row** per the canonical mockup (frame 1, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8 — values are dp intent on the 4dp grid; the M3 affordance owns its metrics):

- A **reply affordance** (left): the reply icon + the `replyCount` value rendered as ONE tappable unit, invoking a hoisted `onReplyShortcut: () -> Unit` callback.
- A **like affordance** (to its right — the row's last action while the send action is deferred, see § "Send-message card action is deferred"): the like icon ONLY — filled + `locationPin`-tinted when `likedByViewer = true`, outlined + `onSurfaceVariant`-muted otherwise (the existing treatment, keeping the existing filled/outlined like-icon test tags) — invoking a hoisted `onToggleLike: () -> Unit` callback. NO numeric like count is rendered: the timeline wire carries no like count — the known, deliberate divergence from the mockup's `favorite 12` action, carried over from the replaced counts-row requirement.

Both affordances SHALL be real interactive controls: click semantics with press indication (ripple), minimum 48dp touch targets (the M3 minimum — the mockup's 20dp glyph + 8dp padding is dp intent, not the touch contract), and a `contentDescription` sourced via `stringResource` (they are interactive now — the prior decorative `contentDescription = null` posture no longer applies to them). The liked state MUST NOT be communicated by color alone — the filled-vs-outlined icon shape carries it. Activating an affordance MUST NOT trigger the whole-card `onOpen`. The card stays presentation-only: both callbacks are hoisted; the card holds no like state machine, no repository reference, and no navigation reference — the rendered like state remains driven solely by the `likedByViewer` value on the supplied model.

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

### Requirement: Whole-card tap opens the detail and identity is not separately tappable

The card SHALL invoke a hoisted `onOpen` callback when tapped anywhere on the card OUTSIDE the two action-row affordances (including over the avatar/name region). The action-row affordances (§ "Action row renders interactive reply and like affordances per mockup frame 1") are the ONLY interactive sub-controls on the card; the avatar and author identity SHALL NOT be separate tap targets (no profile screen exists yet — issue [#196](https://github.com/aditrioka/nearyou-id/issues/196); per the no-dead-controls rule nothing on the card may look or act tappable without a wired destination). The card itself SHALL NOT hold navigation references; navigation wiring stays with the host screens per their specs.

#### Scenario: Tapping the avatar region triggers the whole-card open

- **GIVEN** a rendered card with a recording `onOpen` callback
- **WHEN** the test taps on the avatar/identity region
- **THEN** `onOpen` fires exactly once (the same whole-card action) AND the semantics tree contains no separate clickable node for the avatar or author name

### Requirement: Send-message card action is deferred

The action row SHALL render exactly TWO affordances — reply and like. The send-message (kirim pesan) action shown in mockup frame 1 (the `send` glyph between `mode_comment` and `favorite`) SHALL NOT be rendered in any form — no icon node, no disabled placeholder, no third action slot: no 1:1 chat surface exists in `:mobile:app` yet, and an unwired send control would violate the no-dead-controls rule. This requirement is the deliberate, spec-recorded divergence from frame 1 AND the explicit MODIFY hook for the future mobile chat change: that change SHALL MODIFY this requirement to render the send action (between reply and like, per frame 1) wired to the chat entry point with the post embed (`docs/02-Product.md` § Chat Context Card UX, mockup frames 5–6).

#### Scenario: No send affordance is rendered

- **WHEN** the card is rendered and its semantics tree plus `PostCard.kt` source are inspected
- **THEN** the action row contains exactly the reply and like affordances AND no send/kirim-pesan icon node, disabled control, or third action slot exists

#### Scenario: The mockup divergence is tracked by this requirement, not silently dropped

- **WHEN** comparing mockup frame 1's three-action row (reply / send / like) against the shipped card
- **THEN** the absent send action corresponds to THIS deferred requirement (the divergence is recorded at spec level as the future chat change's MODIFY hook) — it is NOT an undocumented mockup deviation

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Counts row is read-only with no interactive sub-controls

**Reason**: Un-deferred — `mobile-inline-post-actions` ships the inline card actions (closes issue [#201](https://github.com/aditrioka/nearyou-id/issues/201)); the read-only counts row becomes the interactive action row.
**Migration**: Replaced by § "Action row renders interactive reply and like affordances per mockup frame 1" (ADDED above). The liked-icon filled/outlined treatment and the no-like-count wire divergence carry over unchanged; the no-interactive-sub-controls posture is superseded by exactly two spec'd affordances.

### Requirement: Whole-card tap is the single interactive affordance and identity is not separately tappable

**Reason**: The action row introduces exactly two additional tap targets, so "single interactive affordance" no longer holds.
**Migration**: Replaced by § "Whole-card tap opens the detail and identity is not separately tappable" (ADDED above) — the same `onOpen` + identity-not-tappable + no-navigation-reference contract, restated around the two action-row affordances.
