# mobile-home-tab-host Specification

## Purpose

The Nearby/Following/Global tab host in `:mobile:app`. `HomeScreen` is repurposed from a single-feed host into a Material 3 `NavigationBar` host whose body renders the selected tab's screen **directly under the `HomeRoute` scope** — selection is a `rememberSaveable` serializable `Tab` enum, NOT per-tab `NavDisplay` back stacks. Because each tab's feed screen composes directly under the `HomeRoute` NavEntry, its feed load-state ViewModel (resolved via `viewModel { }` inside the screen, exactly as the shipped Nearby feed already does) is `HomeRoute`-scoped and survives both tab switches and the composer round-trip with no re-fetch. Per-tab `NavDisplay` back stacks are **deferred** to the first intra-tab destination (post detail / profile) — there is no intra-tab navigation in this change, so building them now would be vestigial; the deferral is tracked by `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`. The composer FAB stays at the home level (one affordance shared across all three tabs) and pushes `PostCreationRoute` onto the **root** back stack so the composer overlays the tab bar. The Nearby tab hosts the shipped `NearbyTimelineScreen`; the Global tab hosts the new `GlobalTimelineScreen` (`mobile-global-timeline`); the Following tab renders a documented empty-state placeholder and issues NO network fetch (the real Following feed is deferred — there is no follow-action UI yet — and tracked by `FOLLOW_UPS.md` `mobile-following-timeline-screen`). Every label/copy is sourced via `:shared:resources`; the authenticated default tab is Nearby. This closes the `FOLLOW_UPS.md` entries `mobile-home-tab-host` + `mobile-timeline-empty-global-cta`.
## Requirements
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

### Requirement: Tab selection is serializable and survives process death

The selected tab SHALL be modeled as a `@Serializable` `Tab` enum (Nearby / Following / Global) held in `rememberSaveable`, so the active tab survives configuration change and process death on every target including Kotlin/Native (iOS), where reflection-based saving is unavailable. The tab host SHALL render the selected tab's screen **directly under the `HomeRoute` scope** (NOT inside a per-tab `NavDisplay`), so each feed screen's `viewModel { }` resolves to the `HomeRoute` NavEntry store. Per-tab `NavDisplay` back stacks are **deferred** until a tab gains an intra-tab destination (post detail / profile) — tracked by `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`; this change adds NO new tab-root `NavKey`s.

#### Scenario: Selected tab survives a saved-state round-trip

- **GIVEN** a commonTest that sets the selected `Tab` and saves + restores it via the `rememberSaveable` saver (the serializable-enum path)
- **WHEN** the saved value is restored
- **THEN** the restored selection equals the original `Tab` (no `SerializationException`) — proving the iOS-safe saved-state path

#### Scenario: No per-tab NavDisplay or tab-root NavKey is introduced

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` and `screens/routing/NavKeys.kt`
- **THEN** the tab host renders the selected tab's screen directly (no nested per-tab `NavDisplay`) AND no `NearbyTabRoot` / `FollowingTabRoot` / `GlobalTabRoot` `NavKey` is declared (per-tab back stacks are deferred)

### Requirement: The composer FAB stays at the home level and pushes onto the root back stack

The tab host SHALL render a single `FloatingActionButton` (the composer affordance, labelled via `stringResource(Res.string.cta_post)`) at the `HomeScreen` level — visible regardless of the selected tab — that invokes the injected `onOpenComposer` lambda, which appends `PostCreationRoute` to the **root** back stack (above `HomeRoute`), so the composer overlays the entire surface including the `NavigationBar`. The FAB MUST NOT be duplicated per tab; it pushes onto the root back stack only.

#### Scenario: FAB is present on every tab and pushes the composer onto the root stack

- **GIVEN** the tab host composed over a test root back stack (or with a recording `onOpenComposer` callback)
- **WHEN** the FAB is activated while the Nearby tab is selected, and again while the Global tab is selected
- **THEN** a single `FloatingActionButton` is present in both cases AND each activation appends `PostCreationRoute` to the root back stack (or invokes the recording callback)

### Requirement: The authenticated default tab is Nearby

When the tab host is first composed for an authenticated session, the selected tab SHALL default to **Nearby** (preserving the pre-tab-host `HomeRoute`→Nearby landing). The selected-tab value SHALL be held in `rememberSaveable` so it survives configuration change and process death. (The `docs/03-UX-Design.md` "Default tab: Global" applies to the deferred guest pre-login first-open, not the authenticated home — see `design.md` D5.)

#### Scenario: First composition selects Nearby

- **WHEN** the tab host is composed fresh (no saved selected-tab state)
- **THEN** the Nearby tab is selected AND the body renders the Nearby surface (`stringResource(Res.string.timeline_nearby_title)`)

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between tabs SHALL preserve the selected-tab value and each feed's already-loaded state. Because the Nearby and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …" and `mobile-global-timeline` § "Global feed load state is scoped …") and each feed screen composes directly under that scope, leaving a feed tab and returning to it SHALL NOT trigger a re-fetch — the previously loaded posts render immediately.

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

The change SHALL ship: (1) a Robolectric `HomeTabHostScreenTest` (or the existing `HomeScreenTest`/`HomeScreenFabTest` extended) under `mobile/app/src/androidUnitTest/...` covering the three labelled tabs, tab switching swapping the body, the FAB present on each tab, and the Following placeholder — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention, since the `ui-test-manifest` host activity is debug-only); (2) a commonTest covering the selected-`Tab`-enum saved-state round-trip + the no-re-fetch-on-tab-switch invariant via fakes; (3) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the tab host on the simulator.

#### Scenario: Tab-host tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the tab-host Robolectric screen test and the commonTest serialization + no-re-fetch tests are discovered AND each documented tab-host behavior corresponds to at least one `@Test`

#### Scenario: Tab-host screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the tab-host `*ScreenTest` glob alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenComposer`. The actual `PostDetailRoute` **root** back-stack append SHALL be wired at the `HomeScreen` call site (in `screens/routing/AppEntryProvider.kt`, where `appEntryProvider` maps `HomeRoute` → `HomeScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) })`), NOT inside `HomeScreen.kt` (which holds no back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`; never `latitude`/`longitude`) so the detail surface overlays the `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per `FOLLOW_UPS mobile-home-tab-host-per-tab-backstacks`). The Following tab (a deferred placeholder) wires no `onOpenPost` (it has no feed/cards).

#### Scenario: Invoking onOpenPost in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `HomeScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback, with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields (and no `latitude`/`longitude`) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: HomeScreen hoists onOpenPost; the append lives at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt` and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` as a hoisted parameter and holds no back-stack reference, AND the `backStack.add(PostDetailRoute(...))` append lives at the `HomeScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced

