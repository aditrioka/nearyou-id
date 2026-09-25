# mobile-post-card (delta)

## MODIFIED Requirements

### Requirement: Optional overflow kebab per mockup frame 1

The card SHALL accept two optional hoisted kebab actions: a report action (`onReport: (() -> Unit)? = null`) and a block action (`onBlock: (() -> Unit)? = null`). When **at least one** is non-null, the card SHALL render an overflow kebab (`more_vert`) trailing the identity header row — the mockup frame-1 `.post .head .more` placement (`dev/mockups/nearyou-screens-mockup.html`; 20dp glyph in a muted `onSurfaceVariant` treatment; the M3 `IconButton` owns the ≥48dp touch metrics) — opening a `DropdownMenu` carrying, in this order (the post-detail kebab's item order): the "Laporkan" item (resource `profile_report_action`) iff `onReport` is non-null, invoking `onReport`; and the "Blokir @{username}" item (resource `profile_block_action`, interpolated with the model's `authorUsername` — the public display handle already on `PostCardModel`, not PII) iff `onBlock` is non-null, invoking `onBlock`. When BOTH actions are null the kebab SHALL NOT be rendered in any form (no icon node, no disabled placeholder) — a menu with zero items would be a dead control, so hosts supply actions only when at least one item applies (the mockup shows the kebab on every card; the null-gated absence on own posts / non-feed hosts is the deliberate, spec-recorded divergence). The kebab SHALL be a separate tap target: activating it (or a menu item) MUST NOT fire the whole-card `onOpen` nor the identity header's `onOpenProfile`. Its `contentDescription` SHALL come via `stringResource`, and no hardcoded UI string literal appears on the menu items (Compose Multiplatform Resources only). The card stays presentation-only and PII-free: both callbacks are hoisted and parameterless, and NO author UUID is introduced on `PostCardModel`.

#### Scenario: Kebab renders and routes when a report action is supplied

- **GIVEN** a rendered card with a recording `onReport` (plus recording `onOpen` / `onOpenProfile`)
- **WHEN** the kebab is tapped and the "Laporkan" menu item is selected
- **THEN** `onReport` fires exactly once AND `onOpen` and `onOpenProfile` do NOT fire

#### Scenario: Block item renders and routes when a block action is supplied

- **GIVEN** a rendered card for author `@raka.jkt` with a recording `onBlock` (plus recording `onOpen` / `onOpenProfile`)
- **WHEN** the kebab is tapped and the "Blokir @raka.jkt" menu item is selected
- **THEN** `onBlock` fires exactly once AND `onOpen` and `onOpenProfile` do NOT fire

#### Scenario: Either action alone is enough for the kebab; each item is gated on its own action

- **WHEN** the card is rendered with only `onBlock` supplied (`onReport = null`), and separately with only `onReport` supplied (`onBlock = null`)
- **THEN** both renders show the kebab AND the first shows only the "Blokir @{username}" item (no "Laporkan") AND the second shows only the "Laporkan" item (no block item)

#### Scenario: No kebab when both actions are absent

- **WHEN** the card is rendered with `onReport = null` and `onBlock = null` (the defaults)
- **THEN** the tree contains no kebab icon node and no overflow menu — byte-identical affordance surface to the pre-kebab card
