## MODIFIED Requirements

### Requirement: Typed navigation host with start destination

The `App()` composable SHALL host **Navigation 3** (`org.jetbrains.androidx.navigation3`, declared in `design.md`) configured with a developer-owned back stack seeded with a start-destination route. The navigation host SHALL be a `NavDisplay` rendering a `rememberNavBackStack`, where each destination is a typed **`NavKey`** entity mapped to its screen composable by an `entryProvider` — routes are typed entities, not stringly-typed paths. Navigation SHALL be expressed as back-stack list operations (add to push, `removeLastOrNull()` to pop, a `replaceAll(key)` extension to clear-and-set across auth boundaries); no Voyager `Navigator` / `Screen` / `LocalNavigator` API SHALL remain in mobile sources. Adding a new screen in a subsequent change SHALL NOT require restructuring the navigation host wiring beyond declaring the new screen's `NavKey` and adding one `entry<…>` mapping to the `entryProvider`.

#### Scenario: Start destination renders on app launch

- **WHEN** `App()` is composed for the first time with no prior navigation state
- **THEN** the back stack is seeded with the start-destination route (`RootRoute`, defined in `design.md`) and `NavDisplay` renders that route's mapped composable; no other entry is shown

#### Scenario: Navigation host is declared in commonMain

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** the navigation host (`NavDisplay` over a `rememberNavBackStack`) is instantiated inside `App()` (or a commonMain helper invoked by `App()`); no platform-specific source set declares its own navigation host

#### Scenario: Placeholder screen renders app identity via Compose Multiplatform Resources

- **WHEN** the start-destination placeholder is composed
- **THEN** the rendered content includes a "NearYouID" identifier label consumed via `stringResource(Res.string.home_placeholder_title)` from `:shared:resources` (NOT a hardcoded string literal, NOT the legacy `MR.strings.home_placeholder_title` Moko accessor), AND a version label consumed via `stringResource(Res.string.home_placeholder_version, "1.0")` with the runtime version supplied as the format argument, AND no networking call, no auth lookup, and no feature-specific business logic is invoked

#### Scenario: HomeScreen consumes brand logo via CMP Resources accessor

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** the brand-logo selection uses `Res.drawable.logo_brand_dark` (when `isSystemInDarkTheme()` is true) and `Res.drawable.logo_brand_light` (when false), consumed via `painterResource(...)` from the Compose Multiplatform Resources accessor; the file contains NO references to `MR.images.*` or `MR.strings.*` (the legacy Moko accessors)

## ADDED Requirements

### Requirement: Back stack uses serializable NavKey routes for cross-platform state restoration

Every navigation route SHALL be a `@Serializable` type implementing `NavKey`, and ALL route types SHALL be registered in a single polymorphic `SerializersModule` (`polymorphic(NavKey::class) { subclass(...) }`) supplied to the back stack via `SavedStateConfiguration`. The back stack SHALL be created with `rememberNavBackStack(<config>, <startRoute>)` so it is saveable on non-JVM targets (iOS, where reflection-based serialization is unavailable). The serialization module SHALL live in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/`.

#### Scenario: Every route key round-trips through the polymorphic module

- **WHEN** a `commonTest` serializes each declared `NavKey` route via the polymorphic `SavedStateConfiguration` module and deserializes the result back
- **THEN** each route deserializes to a value equal to the original (every route type is registered in the `SerializersModule` — a missing `subclass(...)` registration fails this test)

#### Scenario: Back stack is created with the polymorphic configuration

- **WHEN** inspecting the navigation host in `mobile/app/src/commonMain/kotlin/id/nearyou/app/`
- **THEN** the back stack is created via `rememberNavBackStack` passed the `SavedStateConfiguration` carrying the polymorphic `NavKey` `SerializersModule` (NOT a reflection-defaulted back stack that would fail to save on iOS)

### Requirement: NavDisplay scopes per-entry saveable state and ViewModels via entry decorators

The `NavDisplay` SHALL include, in its `entryDecorators` (in this order), `rememberSaveableStateHolderNavEntryDecorator()` so each `NavEntry` receives its own `SaveableStateRegistry` (per-screen `rememberSaveable` state — e.g. the composer draft — is scoped to its entry and retained while that entry remains in the back stack) **and** `rememberViewModelStoreNavEntryDecorator()` (from the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` artifact) so each `NavEntry` receives its own `ViewModelStore`. A screen MAY scope a `ViewModel` to its entry via `viewModel { … }`; that ViewModel SHALL survive the entry going off-screen (e.g. while another destination is on top) and SHALL be cleared only when the entry is popped off the back stack. The Nearby feed is the first such screen (its load state is held in a `HomeRoute`-scoped ViewModel so returning from the composer does not re-fetch — see `mobile-nearby-timeline`).

#### Scenario: NavDisplay wires both entry decorators

- **WHEN** inspecting the `NavDisplay` declaration in `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** its `entryDecorators` list includes `rememberSaveableStateHolderNavEntryDecorator()` AND `rememberViewModelStoreNavEntryDecorator()`

#### Scenario: The per-entry ViewModel-store artifact is declared

- **WHEN** inspecting the version catalog (`gradle/libs.versions.toml`) and `mobile/app/build.gradle.kts`
- **THEN** the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` dependency is declared (pinned to the project's `androidx-lifecycle` version) and added to the `:mobile:app` `commonMain` dependencies
