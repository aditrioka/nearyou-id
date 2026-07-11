# mobile-post-card (delta)

## ADDED Requirements

### Requirement: Optional overflow kebab per mockup frame 1

The card SHALL accept an optional hoisted report action (`onReport: (() -> Unit)? = null`). When non-null, the card SHALL render an overflow kebab (`more_vert`) trailing the identity header row — the mockup frame-1 `.post .head .more` placement (`dev/mockups/nearyou-screens-mockup.html`; 20dp glyph in a muted `onSurfaceVariant` treatment; the M3 `IconButton` owns the ≥48dp touch metrics) — opening a `DropdownMenu` whose single item "Laporkan" (resource `profile_report_action`) invokes `onReport`. When `onReport` is null the kebab SHALL NOT be rendered in any form (no icon node, no disabled placeholder) — a menu with zero items would be a dead control, so hosts supply the action only when at least one item applies (the mockup shows the kebab on every card; the null-gated absence on own posts / non-feed hosts is the deliberate, spec-recorded divergence until more menu items exist). The kebab SHALL be a separate tap target: activating it (or a menu item) MUST NOT fire the whole-card `onOpen` nor the identity header's `onOpenProfile`. Its `contentDescription` SHALL come via `stringResource`. The card stays presentation-only and PII-free: the callback is hoisted and parameterless, and NO author UUID is introduced on `PostCardModel`.

#### Scenario: Kebab renders and routes when a report action is supplied

- **GIVEN** a rendered card with a recording `onReport` (plus recording `onOpen` / `onOpenProfile`)
- **WHEN** the kebab is tapped and the "Laporkan" menu item is selected
- **THEN** `onReport` fires exactly once AND `onOpen` and `onOpenProfile` do NOT fire

#### Scenario: No kebab when the action is absent

- **WHEN** the card is rendered with `onReport = null` (the default)
- **THEN** the tree contains no kebab icon node and no overflow menu — byte-identical affordance surface to the pre-kebab card

## MODIFIED Requirements

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
