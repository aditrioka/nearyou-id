# mobile-design-system — Delta Specification

## MODIFIED Requirements

### Requirement: The app shell owns a single Scaffold and window insets

The authenticated `:mobile:app` surface SHALL apply window insets in exactly **one** place: the app section shell's `Scaffold` (`AppShellScreen`), running edge-to-edge (the Android entry's `enableEdgeToEdge()` plus the shell `Scaffold`'s `contentWindowInsets`). The shell `Scaffold`'s `topBar` slot is the ONLY place a top app bar may exist (as of `mobile-timeline-card-redesign` it hosts the Home section's centered brand-logo `CenterAlignedTopAppBar` per `mobile-home-tab-host` § "Bottom navigation is a top-level section shell") — a `topBar` on the single shell Scaffold keeps insets applied exactly once. Every composable rendered inside the shell body — section content, the Home feed tab host, and each feed/timeline screen — SHALL be **inset-free**: it MUST NOT wrap its body in its own `Scaffold` or `TopAppBar`, and it MUST consume the shell's `innerPadding` (via `Modifier.padding(innerPadding)` + `Modifier.consumeWindowInsets(innerPadding)`) so system-bar insets are applied once and not re-applied deeper. This is the substrate fix for the nested-Scaffold defect (a Compose `Scaffold` applies but does not consume insets, so nesting them re-adds the status-bar inset and re-owns content padding).

#### Scenario: Only the shell declares a Scaffold

- **WHEN** inspecting `screens/shell/AppShellScreen.kt`, `screens/home/HomeScreen.kt`, `screens/timeline/NearbyTimelineScreen.kt`, and `screens/timeline/GlobalTimelineScreen.kt`
- **THEN** exactly one `Scaffold` is declared (in `AppShellScreen`) AND the only top app bar is the one in that Scaffold's `topBar` slot; `HomeScreen` and the timeline content composables declare no `Scaffold` and no `TopAppBar`

#### Scenario: The feed surface applies the system-bar inset exactly once under the shell app bar

- **GIVEN** the authenticated shell composed edge-to-edge with the Home section + a feed tab selected
- **THEN** the shell app bar sits below the status-bar inset, the top of the feed tab row aligns flush under the shell app bar, and no additional status-bar-height gap is introduced by a nested Scaffold or TopAppBar (a single system-bar inset is applied by the shell `Scaffold`)

### Requirement: Material 3 icons are the canonical navigation, action, and card affordance

Bottom-navigation sections, primary actions (the composer FAB), and post-card affordances (location, like, reply) in `:mobile:app` SHALL use Material 3 icon glyphs as their affordance — NOT brand-tinted placeholder dots. The icon glyphs SHALL be delivered as bundled vector-drawable assets in `:shared:resources` (the `logo_brand_*.xml` idiom) accessed via `painterResource(Res.drawable.*)`, so the app ships exactly the glyphs it uses without the heavy `material-icons-extended` artifact. The prior "no material-icons dependency / brand-dot" idiom is superseded for these affordances. As of `mobile-timeline-card-redesign`, the post **time** label renders as plain text in the card's identity header (after the @-handle, per mockup frames 1 + 19) — the clock glyph is REMOVED from the card affordance set (`docs/03-UX-Design.md` § canonical glyph list is amended in the same PR). **Feed tabs are the exception: they are text-only** with the Material 3 `PrimaryTabRow` underline indicator (NO icon, NO dot) — matching the operator's inspiration references (X / Niche-style text tabs); see `design.md` D10.

#### Scenario: Navigation, action, and card affordances render Material icon drawables, not dots

- **WHEN** inspecting `screens/shell/AppShellScreen.kt` (section items), `screens/home/HomeScreen.kt` (composer FAB), and the shared `ui/components` post card
- **THEN** each section item, the composer FAB, and each post-card affordance (location / like / reply) renders a Material icon via `painterResource(Res.drawable.<icon>)` (or an `ImageVector` icon) AND no such affordance is a `Box(...).background(..., CircleShape)` placeholder dot

#### Scenario: Card time label is text-only in the identity header

- **WHEN** the shared post card is rendered
- **THEN** the time label appears as text in the identity header row AND no clock icon node is rendered on the card

#### Scenario: Feed tabs are text-only with an underline indicator (no icons)

- **WHEN** inspecting the `screens/home/HomeScreen.kt` feed-tab composable
- **THEN** each feed `Tab` renders its `stringResource` label as text under a `PrimaryTabRow` underline indicator AND renders NO icon and NO `CircleShape` dot
