## MODIFIED Requirements

### Requirement: Navigation and tab labels are visible in selected and unselected states

`NavigationBarItem` and feed `Tab` labels SHALL remain visible in BOTH the selected and unselected states. Feed `Tab`s SHALL use the default `Tab` content color (selected = `primary` = brand cobalt, readable as-is). The bottom-nav `NavigationBarItem`s SHALL apply **readable, brand-aligned M3 content-color tokens explicitly** via `NavigationBarItemDefaults.colors(...)` — a single shared `nearYouNavigationBarItemColors()` helper (`AppShellScreen.kt`). As of Material 3 1.4 the bare `NavigationBarItemDefaults.colors()` default resolves `selectedTextColor` to `secondary` and `indicatorColor` to `secondaryContainer`; now that `NearYouColorScheme` defines those as genuine readable accents (no longer neutralized near-white), the bare default would render a *visible* selected label — so the override is **no longer a readability band-aid**. It is RETAINED as a deliberate **brand-identity** choice: the selected bottom-nav state SHALL use the PRIMARY (brand cobalt) family rather than M3's default `secondary` accent. The applied tokens are `primaryContainer` (indicator pill), `primary` (selected icon — brand cobalt), `onSurface` (selected label), and `onSurfaceVariant` (unselected icon + label) — readable in light and dark. A selected bottom-nav or tab item SHALL never render an invisible (background-colored) label, and the selected nav label SHALL clear WCAG AA contrast (≥ 4.5:1) against the nav surface.

#### Scenario: Selected nav item label is readable (WCAG contrast)

- **GIVEN** a `NavigationBarItem(selected = true)` composed under `NearYouTheme` with the shell's `nearYouNavigationBarItemColors()`
- **THEN** the selected label's resolved content color (read via `LocalContentColor` in the label slot) clears **WCAG AA contrast (≥ 4.5:1)** against BOTH the `surface` and the `surfaceContainer` — a contrast check, NOT a mere inequality (a neutralized near-white-on-white selected label is unequal to the background yet unreadable, and would fail this)

#### Scenario: Bottom-nav applies explicit brand-family tokens; tabs use the default

- **WHEN** inspecting the `NavigationBarItem` and `Tab` call sites
- **THEN** the bottom-nav items get their colors from `nearYouNavigationBarItemColors()` (built on `NavigationBarItemDefaults.colors(...)` with the brand-family tokens above — selected icon = `primary`, indicator = `primaryContainer`, selected label = `onSurface`, unselected = `onSurfaceVariant` — NOT the bare default, which would resolve the selected label to `secondary`), and the feed `Tab`s use the default `Tab` content color (no custom color that resolves to the container/background)

#### Scenario: Selected nav icon uses the brand cobalt primary

- **WHEN** inspecting `nearYouNavigationBarItemColors()` in `AppShellScreen.kt`
- **THEN** `selectedIconColor` resolves to `MaterialTheme.colorScheme.primary` (the brand cobalt `#1E4FD6`), `indicatorColor` to `primaryContainer`, `selectedTextColor` to `onSurface`, and the unselected icon + label to `onSurfaceVariant`; the selected icon color (`primary`) clears at least 3:1 against the indicator pill (`primaryContainer`)
