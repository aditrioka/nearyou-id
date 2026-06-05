## MODIFIED Requirements

### Requirement: NearbyTimelineScreen renders the Nearby feed surface

The mobile app SHALL ship a composable `NearbyTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`) that renders the authenticated Nearby feed. The screen is navigation-free (it holds no back-stack reference and is embedded directly by `HomeScreen`). The screen SHALL display: (a) a top-bar title via `stringResource(Res.string.timeline_nearby_title)` ("*Post dari lokasi ini*"); (b) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields" requirement) wrapped in a pull-to-refresh container; (c) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows the Nearby title

- **WHEN** a test composes the `NearbyTimelineScreen` composable under `NearYouTheme` with a fake that emits a loaded list
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.timeline_nearby_title)`

#### Scenario: No hardcoded UI strings in NearbyTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: HomeScreen hosts NearbyTimelineScreen and routing is unchanged

The existing `HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL be a composable (mapped from the `HomeRoute` `NavKey` by the `entryProvider`) whose body renders `NearbyTimelineScreen` as its content. `RootRouterScreen` SHALL continue to route the authenticated path to `HomeRoute` — the authenticated routing **target** (Home) is unchanged; only the back-stack mechanism is migrated by the `mobile-nav-swap-to-navigation3` change (which re-expresses the `mobile-auth-signin` § "RootRouterScreen routes based on token presence" requirement onto the Nav3 back stack). `HomeScreen` SHALL NOT render `home_placeholder_title` or `home_placeholder_version` (those strings are retained in the catalog but unreferenced by `HomeScreen`).

#### Scenario: HomeScreen renders the Nearby timeline content

- **WHEN** a test composes the `HomeScreen` composable under `NearYouTheme` with the timeline fake emitting a loaded list
- **THEN** the rendered tree contains the `timeline_nearby_title` node (i.e., `HomeScreen` delegates to `NearbyTimelineScreen`) AND contains NO node whose text matches `stringResource(Res.string.home_placeholder_title)`

#### Scenario: RootRouterScreen still routes to HomeRoute

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`
- **THEN** the authenticated branch routes to `HomeRoute` (the `HomeScreen` composable) — the routing **target** is unchanged (Home); the back-stack mechanism is the Nav3 form migrated by `mobile-nav-swap-to-navigation3`

## ADDED Requirements

### Requirement: Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip

The Nearby feed's first-page load state (the fetched outcome + the in-flight flag + the reload trigger) SHALL be held in a `HomeRoute`-scoped ViewModel (`NearbyTimelineViewModel`, resolved via `viewModel { … }` under the `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — see `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember`. The first page SHALL load exactly once on the ViewModel's construction; pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel survives `HomeRoute` going off-screen (e.g. while the post composer is on top) and is cleared only when `HomeRoute` is popped, opening the composer and returning SHALL NOT re-fetch the Nearby feed — the already-loaded posts are shown immediately. A coordinate-acquisition failure SHALL continue to map to the existing retryable `NearbyTimelineOutcome.NetworkError` (no new outcome member).

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a `commonTest` `NearbyTimelineViewModel` over a `FakeNearbyTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: A load failure maps to the existing retryable error

- **GIVEN** a `FakeNearbyTimelineFlow` whose `loadFirstPage()` throws
- **WHEN** the `NearbyTimelineViewModel` loads
- **THEN** its exposed outcome is `NearbyTimelineOutcome.NetworkError` (the existing retryable state — no new outcome member is introduced)

#### Scenario: NearbyFeed observes the entry-scoped ViewModel, not composition-local remember

- **WHEN** inspecting `NearbyFeed` in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the feed's `outcome` / `inFlight` are observed from a `viewModel { NearbyTimelineViewModel(...) }` (collected via `collectAsState`), and the load is NOT driven by a composition-local `LaunchedEffect` over a `remember`-ed reload counter (so the load state is not lost when `HomeRoute` is disposed while the composer is on top)
