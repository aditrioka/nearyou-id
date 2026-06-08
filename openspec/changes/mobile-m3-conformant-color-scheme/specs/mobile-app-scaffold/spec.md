## MODIFIED Requirements

### Requirement: Material 3 theme follows system preference

The `App()` composable SHALL wrap its content in a `MaterialTheme` whose active `ColorScheme` is selected between the **NearYouID brand light scheme** (`NearYouColorScheme.light` from `:shared:resources`) and the **NearYouID brand dark scheme** (`NearYouColorScheme.dark` from `:shared:resources`) based on the platform's reported system dark-mode preference at composition time (via `isSystemInDarkTheme()` or the equivalent Compose Multiplatform API). The theme wrapper SHALL be defined as a reusable `NearYouTheme(content: @Composable () -> Unit)` composable in commonMain. The same wrapper SHALL also apply `NearYouTypography` from `:shared:resources` as the active `Typography` and SHALL provide the `ColorScheme` extension properties (e.g., `MaterialTheme.colorScheme.locationPin`, `.premiumBadge`) via `CompositionLocal` so they resolve at every call site within the theme's scope.

#### Scenario: Light mode applies brand light color scheme

- **WHEN** the device reports system dark-mode = OFF at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.light` from `:shared:resources` (NOT vanilla Material 3 `lightColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFF1E4FD6)`

#### Scenario: Dark mode applies brand dark color scheme

- **WHEN** the device reports system dark-mode = ON at the time `App()` is composed
- **THEN** `MaterialTheme.colorScheme` resolves to `NearYouColorScheme.dark` from `:shared:resources` (NOT vanilla Material 3 `darkColorScheme()`); `MaterialTheme.colorScheme.primary` resolves to `Color(0xFFB7C4FF)` (the brand-blue palette tone 80 from the MTB-derived dark scheme per `mobile-m3-conformant-color-scheme` design.md Decision 3)

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
