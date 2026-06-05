# mobile-home-tab-host Specification

## Purpose

The Nearby/Following/Global tab host in `:mobile:app`. `HomeScreen` is repurposed from a single-feed host into a Material 3 `NavigationBar` host over **per-tab Navigation-3 back stacks** — one saveable `NavKey` back stack per tab, each rendered by its own `NavDisplay` with the established per-entry ViewModel + saved-state decorators — so each tab carries independent navigation state for the intra-tab pushes (post detail, profile) the tabs will gain. The composer FAB stays at the home level (one affordance shared across all three tabs) and pushes `PostCreationRoute` onto the **root** back stack so the composer overlays the tab bar. The Nearby tab hosts the shipped `NearbyTimelineScreen`; the Global tab hosts the new `GlobalTimelineScreen` (`mobile-global-timeline`); the Following tab renders a documented empty-state placeholder and issues NO network fetch (the real Following feed is deferred — there is no follow-action UI yet — and tracked by `FOLLOW_UPS.md` `mobile-following-timeline-screen`). Feed load-state ViewModels are scoped to the `HomeRoute` NavEntry so switching tabs and round-tripping through the composer never re-fetches. Every label/copy is sourced via `:shared:resources`; the authenticated default tab is Nearby. This closes the `FOLLOW_UPS.md` entries `mobile-home-tab-host` + `mobile-timeline-empty-global-cta`.

## ADDED Requirements

### Requirement: HomeScreen is the Nearby/Following/Global tab host

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`, mapped from the `HomeRoute` `NavKey` by the `entryProvider`) SHALL render a Material 3 `Scaffold` whose bottom bar is a `NavigationBar` with exactly three destinations — Nearby, Following, Global — each labelled via `stringResource` (`Res.string.tab_nearby`, `Res.string.tab_following`, `Res.string.tab_global`) with an icon + `contentDescription` sourced via `stringResource`. The `Scaffold` body SHALL render the **selected tab's** content (Nearby → `NearbyTimelineScreen`; Following → the deferred placeholder per the § "Following tab renders the deferred placeholder" requirement; Global → `GlobalTimelineScreen`). No hardcoded UI string literals SHALL appear in `HomeScreen`. The host SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Tab host renders three labelled destinations

- **WHEN** a test composes `HomeScreen` under `NearYouTheme` (with fakes for the tab feeds)
- **THEN** the rendered tree contains a `NavigationBar` with selectable nodes whose text matches `stringResource(Res.string.tab_nearby)`, `stringResource(Res.string.tab_following)`, and `stringResource(Res.string.tab_global)`

#### Scenario: Selecting a tab swaps the body to that tab's content

- **GIVEN** the tab host is composed with the Nearby tab selected (default)
- **WHEN** the test activates the Global navigation-bar item
- **THEN** the body renders the Global surface (a node matching `stringResource(Res.string.timeline_global_title)`) AND no longer renders the Nearby surface's title

#### Scenario: No hardcoded UI strings in HomeScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / label call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Per-tab Navigation-3 back stacks are serializable and iOS-safe

The tab host SHALL maintain one Navigation-3 back stack per tab — a `rememberSaveable` map `Tab → NavBackStack` (each a saveable `NavKey` list seeded with that tab's root key) — and render the active tab through its own `NavDisplay` carrying the same `rememberViewModelStoreNavEntryDecorator()` + `rememberSavedStateNavEntryDecorator()` used by the root host (`mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"). The three tab-root keys (`NearbyTabRoot`, `FollowingTabRoot`, `GlobalTabRoot`) SHALL be `@Serializable` `data object`s declared in `screens/routing/NavKeys.kt` and registered in the `AppNavSerialization` polymorphic `SerializersModule`, so the per-tab back stacks are saveable on Kotlin/Native (iOS), where Nav3's reflection-based serialization is unavailable (`mobile-app-scaffold` § "Back stack uses serializable NavKey routes for cross-platform state restoration"). No tab-root key SHALL carry a PII payload.

#### Scenario: Tab-root keys are serializable and registered

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt` and `AppNavSerialization.kt`
- **THEN** `NearbyTabRoot`, `FollowingTabRoot`, and `GlobalTabRoot` are each `@Serializable` AND each is registered as a polymorphic `NavKey` subclass in the `SerializersModule`

#### Scenario: Per-tab back stacks round-trip through serialization

- **GIVEN** a commonTest that builds each tab's back stack and serializes it via the `AppNavSerialization` configuration (the same path exercised for the root back stack)
- **WHEN** the serialized state is decoded
- **THEN** each tab-root key decodes back to its original `data object` (no `SerializationException`), proving the iOS saved-state path

### Requirement: The composer FAB stays at the home level and pushes onto the root back stack

The tab host SHALL render a single `FloatingActionButton` (the composer affordance, labelled via `stringResource(Res.string.cta_post)`) at the `HomeScreen` level — visible regardless of the selected tab — that invokes the injected `onOpenComposer` lambda, which appends `PostCreationRoute` to the **root** back stack (above `HomeRoute`), so the composer overlays the entire surface including the `NavigationBar`. The FAB MUST NOT be duplicated per tab and MUST NOT push into a per-tab back stack.

#### Scenario: FAB is present on every tab and pushes the composer onto the root stack

- **GIVEN** the tab host composed over a test root back stack (or with a recording `onOpenComposer` callback)
- **WHEN** the FAB is activated while the Nearby tab is selected, and again while the Global tab is selected
- **THEN** a single `FloatingActionButton` is present in both cases AND each activation appends `PostCreationRoute` to the root back stack (or invokes the recording callback) — never into a per-tab back stack

### Requirement: The authenticated default tab is Nearby

When the tab host is first composed for an authenticated session, the selected tab SHALL default to **Nearby** (preserving the pre-tab-host `HomeRoute`→Nearby landing). The selected-tab value SHALL be held in `rememberSaveable` so it survives configuration change and process death. (The `docs/03-UX-Design.md` "Default tab: Global" applies to the deferred guest pre-login first-open, not the authenticated home — see `design.md` D5.)

#### Scenario: First composition selects Nearby

- **WHEN** the tab host is composed fresh (no saved selected-tab state)
- **THEN** the Nearby tab is selected AND the body renders the Nearby surface (`stringResource(Res.string.timeline_nearby_title)`)

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between tabs SHALL preserve each tab's navigation state (its `rememberSaveable` back stack) and each feed's already-loaded state. Because the Nearby and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …" and `mobile-global-timeline` § "Global feed load state is scoped …"), leaving a feed tab and returning to it SHALL NOT trigger a re-fetch — the previously loaded posts render immediately.

#### Scenario: Returning to a feed tab does not re-fetch

- **GIVEN** a commonTest with a `FakeNearbyTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the tab host composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Global tab (first Global fetch occurs), then back to Nearby, then back to Global
- **THEN** the Nearby fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on tab return)

### Requirement: Following tab renders the deferred placeholder and issues no fetch

The Following tab SHALL render a documented empty-state placeholder whose copy is sourced via `stringResource(Res.string.timeline_following_placeholder)` ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu.*", aligned with `docs/03-UX-Design.md` § Empty State "Following empty → direct user to Nearby/Global"). This change SHALL NOT issue any `GET /api/v1/timeline/following` request and SHALL NOT wire a Following timeline API client / repository / flow — the real Following feed is **deferred** (no follow-action UI exists on mobile, so the feed would be perpetually empty) and is tracked by the `FOLLOW_UPS.md` entry `mobile-following-timeline-screen`, which will MODIFY this requirement to introduce the live feed.

#### Scenario: Following tab shows the placeholder copy

- **WHEN** the Following tab is selected in the composed tab host
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_following_placeholder)`

#### Scenario: Following tab issues no network fetch

- **GIVEN** a Ktor MockEngine capturing all outbound requests, wired into the composed tab host
- **WHEN** the Following tab is selected and rendered
- **THEN** no request to a path containing `/api/v1/timeline/following` is captured AND inspecting `:mobile:app` shows no Following-timeline API-client/repository/flow type is wired

### Requirement: Test coverage for the tab host

The change SHALL ship: (1) a Robolectric `HomeTabHostScreenTest` (or the existing `HomeScreenTest`/`HomeScreenFabTest` extended) under `mobile/app/src/androidUnitTest/...` covering the three labelled tabs, tab switching swapping the body, the FAB present on each tab, and the Following placeholder — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention, since the `ui-test-manifest` host activity is debug-only); (2) a commonTest covering tab-root `NavKey` serialization round-trip + the no-re-fetch-on-tab-switch invariant via fakes; (3) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the tab host on the simulator.

#### Scenario: Tab-host tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the tab-host Robolectric screen test and the commonTest serialization + no-re-fetch tests are discovered AND each documented tab-host behavior corresponds to at least one `@Test`

#### Scenario: Tab-host screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the tab-host `*ScreenTest` glob alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes
