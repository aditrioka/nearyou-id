## MODIFIED Requirements

### Requirement: Material 3 theme follows system preference

The `App()` composable SHALL wrap its content in a `MaterialTheme` whose active `ColorScheme` is selected between the **NearYouID brand light scheme** (`NearYouColorScheme.light` from `:shared:resources`) and the **NearYouID brand dark scheme** (`NearYouColorScheme.dark` from `:shared:resources`) based on the platform's reported system dark-mode preference at composition time (via `isSystemInDarkTheme()` or the equivalent Compose Multiplatform API). The theme wrapper SHALL be defined as a reusable `NearYouTheme(content: @Composable () -> Unit)` composable in commonMain. The same wrapper SHALL also apply `NearYouTypography` from `:shared:resources` as the active `Typography` and SHALL provide the `ColorScheme` extension properties (e.g., `MaterialTheme.colorScheme.locationPin`, `.premiumBadge`) via `CompositionLocal` so they resolve at every call site within the theme's scope.

#### Scenario: Light mode applies brand light color scheme

- **WHEN** the device reports system dark-mode = OFF at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.light` from `:shared:resources` (NOT vanilla Material 3 `lightColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFF1E4FD6)`

#### Scenario: Dark mode applies brand dark color scheme

- **WHEN** the device reports system dark-mode = ON at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.dark` from `:shared:resources` (NOT vanilla Material 3 `darkColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFFB3C5FF)` (the mechanically-derived dark primary per `shared-resources-moko-bootstrap` design.md Decision 3)

#### Scenario: NearYouTheme applies brand typography

- **WHEN** `App()` is composed inside `NearYouTheme { ... }`
- **THEN** `MaterialTheme.typography` resolves to `NearYouTypography` from `:shared:resources` — every type role (`displayLarge` through `labelSmall`) uses Plus Jakarta Sans (with `FontFamily.SansSerif` as fallback)

#### Scenario: NearYouTheme exposes ColorScheme extension properties

- **WHEN** a composable invokes `MaterialTheme.colorScheme.locationPin` inside `NearYouTheme { ... }`
- **THEN** the property resolves to the theme-aware value (light: `Color(0xFFFF7A5C)`, dark: `Color(0xFFFFB59E)`) via the `CompositionLocal` wired by `NearYouTheme`

- **WHEN** a composable invokes `MaterialTheme.colorScheme.premiumBadge` inside `NearYouTheme { ... }`
- **THEN** the property resolves to the theme-aware value (light: `Color(0xFFF4B740)`, dark: `Color(0xFFE8B941)`)

#### Scenario: NearYouTheme is the single theming root

- **WHEN** grepping commonMain for `MaterialTheme {`
- **THEN** the only occurrence is inside `NearYouTheme`'s implementation (or `App()`'s direct invocation of `NearYouTheme`); no screen or component declares its own competing `MaterialTheme` wrapper

#### Scenario: No vanilla Material 3 default color schemes are referenced from NearYouTheme

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/theme/NearYouTheme.kt`
- **THEN** the file contains NO references to `lightColorScheme(` or `darkColorScheme(` (the vanilla Material 3 default-color constructors) — both schemes are sourced exclusively from `NearYouColorScheme` in `:shared:resources`

### Requirement: Typed navigation host with start destination

The `App()` composable SHALL host a navigation framework (declared in `design.md`) configured with at least one screen registered as the start destination. The navigation framework SHALL provide typed routes — each screen is represented as a typed entity (e.g., a Voyager `Screen` implementation), not a stringly-typed path. Adding a new screen in a subsequent change SHALL NOT require restructuring the navigation host wiring beyond declaring the new screen in the framework's registry pattern.

#### Scenario: Start destination renders on app launch

- **WHEN** `App()` is composed for the first time with no prior navigation state
- **THEN** the registered start-destination placeholder screen (named `HomeScreen` or the equivalent defined in `design.md`) is rendered; no other screen is shown

#### Scenario: Navigation host is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the navigation host is instantiated inside `App()` (or a commonMain helper invoked by `App()`); no platform-specific source set declares its own navigation host

#### Scenario: Placeholder screen renders app identity via Moko Resources

- **WHEN** the start-destination placeholder is composed
- **THEN** the rendered content includes a "NearYouID" identifier label consumed via `MR.strings.home_placeholder_title` from `:shared:resources` (NOT a hardcoded string literal), AND a version label consumed via `MR.strings.home_placeholder_version` with the runtime version supplied as the format argument, AND no networking call, no auth lookup, and no feature-specific business logic is invoked
