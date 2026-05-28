## MODIFIED Requirements

### Requirement: Typed navigation host with start destination

The `App()` composable SHALL host a navigation framework (declared in `design.md`) configured with at least one screen registered as the start destination. The navigation framework SHALL provide typed routes — each screen is represented as a typed entity (e.g., a Voyager `Screen` implementation), not a stringly-typed path. Adding a new screen in a subsequent change SHALL NOT require restructuring the navigation host wiring beyond declaring the new screen in the framework's registry pattern.

#### Scenario: Start destination renders on app launch

- **WHEN** `App()` is composed for the first time with no prior navigation state
- **THEN** the registered start-destination placeholder screen (named `HomeScreen` or the equivalent defined in `design.md`) is rendered; no other screen is shown

#### Scenario: Navigation host is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the navigation host is instantiated inside `App()` (or a commonMain helper invoked by `App()`); no platform-specific source set declares its own navigation host

#### Scenario: Placeholder screen renders app identity via Compose Multiplatform Resources

- **WHEN** the start-destination placeholder is composed
- **THEN** the rendered content includes a "NearYouID" identifier label consumed via `stringResource(Res.string.home_placeholder_title)` from `:shared:resources` (NOT a hardcoded string literal, NOT the legacy `MR.strings.home_placeholder_title` Moko accessor), AND a version label consumed via `stringResource(Res.string.home_placeholder_version, "1.0")` with the runtime version supplied as the format argument, AND no networking call, no auth lookup, and no feature-specific business logic is invoked

#### Scenario: HomeScreen consumes brand logo via CMP Resources accessor

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** the brand-logo selection uses `Res.drawable.logo_brand_dark` (when `isSystemInDarkTheme()` is true) and `Res.drawable.logo_brand_light` (when false), consumed via `painterResource(...)` from the Compose Multiplatform Resources accessor; the file contains NO references to `MR.images.*` or `MR.strings.*` (the legacy Moko accessors)
