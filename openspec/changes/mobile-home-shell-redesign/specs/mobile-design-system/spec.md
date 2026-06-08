## ADDED Requirements

### Requirement: The app shell owns a single Scaffold and window insets

The authenticated `:mobile:app` surface SHALL apply window insets in exactly **one** place: the app section shell's `Scaffold` (`AppShellScreen`), running edge-to-edge (the Android entry's `enableEdgeToEdge()` plus the shell `Scaffold`'s `contentWindowInsets`). Every composable rendered inside the shell body — section content, the Home feed tab host, and each feed/timeline screen — SHALL be **inset-free**: it MUST NOT wrap its body in its own `Scaffold` or `TopAppBar`, and it MUST consume the shell's `innerPadding` (via `Modifier.padding(innerPadding)` + `Modifier.consumeWindowInsets(innerPadding)`) so system-bar insets are applied once and not re-applied deeper. This is the substrate fix for the nested-Scaffold defect (a Compose `Scaffold` applies but does not consume insets, so nesting them re-adds the status-bar inset and re-owns content padding).

#### Scenario: Only the shell declares a Scaffold

- **WHEN** inspecting `screens/shell/AppShellScreen.kt`, `screens/home/HomeScreen.kt`, `screens/timeline/NearbyTimelineScreen.kt`, and `screens/timeline/GlobalTimelineScreen.kt`
- **THEN** exactly one `Scaffold` is declared (in `AppShellScreen`); `HomeScreen` and the timeline content composables declare no `Scaffold` and no `TopAppBar`

#### Scenario: The tab row sits flush against the status bar with no double inset

- **GIVEN** the authenticated shell composed edge-to-edge with the Home section + a feed tab selected
- **THEN** the top of the feed tab row aligns with the bottom of the status-bar inset (a single system-bar inset is applied by the shell `Scaffold`), with no additional status-bar-height gap introduced by a nested Scaffold or TopAppBar

### Requirement: Material 3 icons are the canonical navigation, action, and card affordance

Bottom-navigation sections, primary actions (the composer FAB), and post-card affordances (location, like, reply, time) in `:mobile:app` SHALL use Material 3 icon glyphs as their affordance — NOT brand-tinted placeholder dots. The icon glyphs SHALL be delivered as bundled vector-drawable assets in `:shared:resources` (the `logo_brand_*.xml` idiom) accessed via `painterResource(Res.drawable.*)`, so the app ships exactly the glyphs it uses without the heavy `material-icons-extended` artifact. The prior "no material-icons dependency / brand-dot" idiom is superseded for these affordances. **Feed tabs are the exception: they are text-only** with the Material 3 `PrimaryTabRow` underline indicator (NO icon, NO dot) — matching the operator's inspiration references (X / Niche-style text tabs); see `design.md` D10.

#### Scenario: Navigation, action, and card affordances render Material icon drawables, not dots

- **WHEN** inspecting `screens/shell/AppShellScreen.kt` (section items), `screens/home/HomeScreen.kt` (composer FAB), and the timeline post card
- **THEN** each section item, the composer FAB, and each post-card affordance (location / like / reply / time) renders a Material icon via `painterResource(Res.drawable.<icon>)` (or an `ImageVector` icon) AND no such affordance is a `Box(...).background(..., CircleShape)` placeholder dot

#### Scenario: Feed tabs are text-only with an underline indicator (no icons)

- **WHEN** inspecting the `screens/home/HomeScreen.kt` feed-tab composable
- **THEN** each feed `Tab` renders its `stringResource` label as text under a `PrimaryTabRow` underline indicator AND renders NO icon and NO `CircleShape` dot

### Requirement: Navigation and tab labels are visible in selected and unselected states

`NavigationBarItem` and feed `Tab` labels SHALL remain visible in BOTH the selected and unselected states, using the Material 3 default content-color tokens (`NavigationBarItemDefaults.colors()` / default `Tab` content color) rather than a custom color that can collapse to the background. A selected bottom-nav or tab item SHALL never render an invisible (background-colored) label.

#### Scenario: Selected nav item label is visible

- **GIVEN** the shell composed under `NearYouTheme` with the Home section selected
- **THEN** the selected section's label node is present AND its resolved content color is a non-background M3 token (e.g. `onSecondaryContainer` / `onSurface`), not equal to the surface/background color

#### Scenario: Default M3 item theming is used

- **WHEN** inspecting the `NavigationBarItem` and `Tab` call sites
- **THEN** the item colors come from `NavigationBarItemDefaults.colors()` / the `Tab` default content color (no custom `selectedTextColor`/`unselectedTextColor` that resolves to the container/background color)

### Requirement: Canonical list loading and refresh pattern

Every scrollable list surface in `:mobile:app` SHALL distinguish **initial load** (no content yet) from **refresh** (a reload while content already exists), and SHALL never display two progress indicators simultaneously:
- **Initial load** → a skeleton/placeholder presentation with at most one in-content progress indicator; the pull-to-refresh indicator is NOT shown.
- **Refresh of existing content** → the pull-to-refresh indicator is shown over the **retained** content list; the list (the scrollable the gesture is attached to) MUST stay mounted, and the in-content initial-load indicator is NOT shown.

A `PullToRefreshBox`'s `isRefreshing` argument SHALL reflect the refresh-of-existing-content state only (not the initial load). The **empty, error, and rate-limit states** (the non-`Content` post-initial-load states) SHALL be rendered inside a scrollable container so the pull-to-refresh gesture remains available from them (a `PullToRefreshBox` requires a scrollable child to recognize the gesture). A refresh triggered from a non-`Content` state SHALL **retain that state** (it MUST NOT flip back to the initial-load skeleton) while showing the pull-to-refresh indicator.

#### Scenario: Pull-to-refresh is available from a non-Content state

- **GIVEN** a list surface in a non-`Content` post-load state (e.g. empty or error) with a counting fake
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized) AND the state remains the same non-`Content` state during the refresh (it does NOT flip to the initial-load skeleton), with the pull-to-refresh indicator shown

#### Scenario: Initial load shows one indicator, no pull-to-refresh spinner

- **WHEN** a list surface is in its initial-load state (no content yet)
- **THEN** the rendered tree shows the skeleton/loading presentation with a single in-content indicator AND the `PullToRefreshBox` `isRefreshing` argument is `false`

#### Scenario: Refresh keeps content mounted and shows only the pull-to-refresh spinner

- **GIVEN** a list surface with loaded content
- **WHEN** a pull-to-refresh (or retry) reload is in flight
- **THEN** the content list remains mounted (the scrollable is not torn down) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content full-screen `CircularProgressIndicator` is rendered (exactly one progress indicator total)

### Requirement: User-facing labels are single-language Bahasa Indonesia

All user-facing labels across `:mobile:app` SHALL be a single language — Bahasa Indonesia — with no mixed English/Indonesian within the same surface, and all SHALL be sourced via `:shared:resources` `stringResource(Res.string.<name>)`. In particular the feed tab labels (previously English: "Nearby"/"Following"/"Global") SHALL be Bahasa Indonesia to match the Bahasa Indonesia bottom-nav section labels (Beranda/Notifikasi/Profil).

#### Scenario: Feed tabs and nav sections are the same language

- **WHEN** inspecting the rendered feed tab labels and bottom-nav section labels
- **THEN** all are Bahasa Indonesia (no English label remains among the navigation/tab labels) AND each is sourced via `stringResource(Res.string.<name>)`

### Requirement: Runtime user-selectable language switching is deferred

This capability SHALL NOT introduce runtime user-selectable language switching (locale resource variants, a language-preference store, or a settings toggle). The single-language Bahasa Indonesia rule above is satisfied by normalizing the catalog copy, NOT by an in-app language picker. Runtime language switching is **deferred** and tracked by the `FOLLOW_UPS.md` entry `mobile-localization-language-switching`, which will MODIFY this requirement to introduce the live capability.

#### Scenario: No language picker or locale-variant infrastructure is wired

- **WHEN** inspecting `:mobile:app` and `:shared:resources`
- **THEN** there is no user-facing language selector, no `values-en`/`values-id` locale-variant split, and no language-preference persistence AND `FOLLOW_UPS.md` contains an entry `mobile-localization-language-switching`
