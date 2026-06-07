## MODIFIED Requirements

### Requirement: HomeScreen is the Nearby/Following/Global tab host

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render the **Home section's content**: the Nearby / Following / Global feeds as a **top tab row** (Material 3 `PrimaryTabRow`), NOT as the bottom `NavigationBar` (the bottom bar is now the app section shell — see § "Bottom navigation is a top-level section shell"). `HomeScreen` SHALL render a `PrimaryTabRow` with exactly three feed tabs — Nearby, Following, Global — each labelled via `stringResource` (`Res.string.tab_nearby`, `Res.string.tab_following`, `Res.string.tab_global`) with a `contentDescription` sourced via `stringResource`. The body below the tab row SHALL render the **selected feed tab's** content (Nearby → `NearbyTimelineScreen`; Following → the deferred placeholder per the unchanged § "Following tab renders the deferred placeholder"; Global → `GlobalTimelineScreen`). No hardcoded UI string literals SHALL appear in `HomeScreen`. `HomeScreen` SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Home section renders three labelled feed tabs in a top tab row

- **WHEN** a test composes `HomeScreen` under `NearYouTheme` (with fakes for the feed tabs)
- **THEN** the rendered tree contains a `PrimaryTabRow` with selectable feed tabs whose text matches `stringResource(Res.string.tab_nearby)`, `stringResource(Res.string.tab_following)`, and `stringResource(Res.string.tab_global)` — and NO bottom `NavigationBar` is rendered by `HomeScreen` itself (the bottom nav belongs to the shell)

#### Scenario: Selecting a feed tab swaps the body to that feed's content

- **GIVEN** `HomeScreen` composed with the Nearby feed tab selected (default)
- **WHEN** the test activates the Global feed tab
- **THEN** the body renders the Global surface (a node matching `stringResource(Res.string.timeline_global_title)`) AND no longer renders the Nearby surface's title

#### Scenario: No hardcoded UI strings in HomeScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / label call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Tab selection is serializable and survives process death

The selected **feed tab** within the Home section SHALL be modeled as a `@Serializable` `Tab` enum (Nearby / Following / Global) held in `rememberSaveable`, so it survives configuration change and process death on every target including Kotlin/Native (iOS), where reflection-based saving is unavailable. The selected **bottom-nav section** SHALL likewise be serializable (see § "Bottom navigation is a top-level section shell"). The Home section SHALL render the selected feed tab's screen **directly under the `HomeRoute` scope** (NOT inside a per-tab `NavDisplay`), so each feed screen's `viewModel { }` resolves to the `HomeRoute` NavEntry store. Per-tab `NavDisplay` back stacks remain **deferred** (tracked by `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`); this change adds NO new tab-root `NavKey`s.

#### Scenario: Selected feed tab survives a saved-state round-trip

- **GIVEN** a commonTest that sets the selected feed `Tab` and saves + restores it via the `rememberSaveable` saver (the serializable-enum path)
- **WHEN** the saved value is restored
- **THEN** the restored selection equals the original `Tab` (no `SerializationException`) — proving the iOS-safe saved-state path

#### Scenario: No per-tab NavDisplay or tab-root NavKey is introduced

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` and `screens/routing/NavKeys.kt`
- **THEN** the Home section renders the selected feed tab's screen directly (no nested per-tab `NavDisplay`) AND no `NearbyTabRoot` / `FollowingTabRoot` / `GlobalTabRoot` `NavKey` is declared (per-tab back stacks remain deferred)

### Requirement: The composer FAB stays at the home level and pushes onto the root back stack

The **Home section** SHALL render a single `FloatingActionButton` (the composer affordance, labelled via `stringResource(Res.string.cta_post)`) — visible across all three feed tabs of the Home section — that invokes the injected `onOpenComposer` lambda, which appends `PostCreationRoute` to the **root** back stack (above the shell), so the composer overlays the entire surface including the bottom `NavigationBar`. The FAB MUST NOT be duplicated per feed tab; it pushes onto the root back stack only. The FAB belongs to the Home section (it is NOT shown on the Notifikasi or Profil sections).

#### Scenario: FAB is present on every Home feed tab and pushes the composer onto the root stack

- **GIVEN** the Home section composed over a test root back stack (or with a recording `onOpenComposer` callback)
- **WHEN** the FAB is activated while the Nearby feed tab is selected, and again while the Global feed tab is selected
- **THEN** a single `FloatingActionButton` is present in both cases AND each activation appends `PostCreationRoute` to the root back stack (or invokes the recording callback)

### Requirement: The authenticated default tab is Nearby

When the shell is first composed for an authenticated session, the selected **section** SHALL default to **Home**, and within the Home section the selected **feed tab** SHALL default to **Nearby** (preserving the pre-restructure landing). Both the selected-section value and the selected-feed-tab value SHALL be held in `rememberSaveable` so they survive configuration change and process death. (The `docs/03-UX-Design.md` "Default tab: Global" applies to the deferred guest pre-login first-open, not the authenticated home — see `design.md` D5 of `mobile-home-tab-host`.)

#### Scenario: First composition selects Home → Nearby

- **WHEN** the shell is composed fresh (no saved selected-section/feed-tab state)
- **THEN** the Home section is selected AND within it the Nearby feed tab is selected AND the body renders the Nearby surface (`stringResource(Res.string.timeline_nearby_title)`)

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between **feed tabs** within the Home section SHALL preserve the selected-feed-tab value and each feed's already-loaded state. Because the Nearby and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …" and `mobile-global-timeline` § "Global feed load state is scoped …") and each feed screen composes directly under that scope, leaving a feed tab and returning SHALL NOT trigger a re-fetch. Switching **bottom-nav sections** (Home ↔ Notifikasi ↔ Profil) and returning to Home SHALL likewise preserve the Home feeds' loaded state (the Home section content is not torn down and re-fetched on section switch).

#### Scenario: Returning to a feed tab does not re-fetch

- **GIVEN** a commonTest with a `FakeNearbyTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the Home section composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Global feed tab (first Global fetch occurs), then back to Nearby, then back to Global
- **THEN** the Nearby fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on feed-tab return)

#### Scenario: Returning to the Home section does not re-fetch the feeds

- **GIVEN** the shell composed with the Home section selected and the Nearby feed loaded once
- **WHEN** the test switches to the Notifikasi section and back to Home
- **THEN** the Nearby feed's fetch count is unchanged (the Home feeds are not re-fetched on section return)

### Requirement: Test coverage for the tab host

The change SHALL ship: (1) a Robolectric shell/host test (`mobile/app/src/androidUnitTest/...`, e.g. `AppShellScreenTest` / extended `HomeScreenTest`) covering the three bottom-nav sections, section switching swapping the section body, the three Home feed top-tabs, feed-tab switching swapping the feed body, the composer FAB on the Home section, the Following + Profil placeholders, and the Notifikasi badge — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention, since the `ui-test-manifest` host activity is debug-only); (2) a commonTest covering the selected-section + selected-feed-`Tab` saved-state round-trips + the no-re-fetch-on-feed-tab-switch AND no-re-fetch-on-section-switch invariants via fakes; (3) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the shell + Home tabs on the simulator, with Kotlin/Native-legal test function names.

#### Scenario: Shell + tab-host tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the shell/host Robolectric test and the commonTest serialization + no-re-fetch tests are discovered AND each documented shell/tab-host behavior corresponds to at least one `@Test`

#### Scenario: Shell/host screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the shell/host `*ScreenTest` glob alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes

## ADDED Requirements

### Requirement: Bottom navigation is a top-level section shell (Home / Notifikasi / Profil)

The authenticated root surface SHALL be an app **section shell** (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt` or equivalent) rendering a Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` with exactly three top-level **sections** — **Home**, **Notifikasi**, **Profil** — each labelled via `stringResource` (`Res.string.section_home`, `Res.string.section_notifications`, `Res.string.section_profile`) with an icon + `contentDescription` via `stringResource`. The shell body SHALL render the selected section's content (Home → `HomeScreen`; Notifikasi → the notifications surface per `mobile-notifications-list`; Profil → the deferred placeholder per § "The Profil section renders a deferred placeholder"). The selected section SHALL be a `@Serializable` `Section` enum held in `rememberSaveable` (iOS-safe), defaulting to Home. No hardcoded UI string literals SHALL appear in the shell source. The shell SHALL render under `NearYouTheme`. The shell replaces the prior arrangement where the bottom `NavigationBar` was the three feeds directly.

#### Scenario: Shell renders three labelled sections and defaults to Home

- **WHEN** a test composes the shell under `NearYouTheme` with fakes
- **THEN** the rendered tree contains a bottom `NavigationBar` with selectable nodes whose text matches `stringResource(Res.string.section_home)`, `stringResource(Res.string.section_notifications)`, and `stringResource(Res.string.section_profile)` AND the Home section is selected by default (the Home feed surface is rendered)

#### Scenario: Selecting a section swaps the shell body

- **GIVEN** the shell composed with the Home section selected (default)
- **WHEN** the test activates the Notifikasi section
- **THEN** the shell body renders the notifications surface AND no longer renders the Home feed surface

#### Scenario: Selected section survives a saved-state round-trip

- **GIVEN** a commonTest that sets the selected `Section` and saves + restores it via the `rememberSaveable` saver
- **WHEN** the saved value is restored
- **THEN** the restored selection equals the original `Section` (no `SerializationException`) — proving the iOS-safe saved-state path

### Requirement: The Notifikasi section hosts the notifications surface with an unread badge on its nav item

The Notifikasi bottom-nav item SHALL render an unread **badge** (Material 3 `Badge` on the `NavigationBarItem`) when the caller has unread notifications, sourced from `GET /api/v1/notifications/unread-count` (`{ count }`). The count SHALL be fetched on shell (re)composition/resume and refreshed when the user leaves the Notifikasi section (having likely read some); the badge is shown only when `count > 0`. Activating the Notifikasi section SHALL render the `NotificationsScreen` (owned by `mobile-notifications-list`) as the section body. Live/push/polling badge updates are explicitly deferred (the badge is one-shot per the above triggers). The badge `contentDescription` SHALL be sourced via `stringResource`.

#### Scenario: Unread badge shows when count > 0 and hides at zero

- **GIVEN** the unread-count source yields `count = 4`
- **THEN** the Notifikasi nav item renders an unread badge; AND **WHEN** the source yields `count = 0` the badge is absent

#### Scenario: Notifikasi section renders the notifications surface

- **WHEN** the Notifikasi section is selected in the composed shell
- **THEN** the shell body renders the `NotificationsScreen` (a node matching `stringResource(Res.string.notifications_title)`)

#### Scenario: Badge is one-shot (no live updates wired)

- **WHEN** inspecting the unread-count wiring on the shell
- **THEN** the count is fetched on shell composition/resume + on leaving the Notifikasi section only AND no polling timer / push-driven live subscription is wired (live updates deferred)

### Requirement: The Profil section renders a deferred placeholder

The Profil section SHALL render a documented placeholder (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/profile/ProfilePlaceholderScreen.kt`) whose copy is sourced via `stringResource(Res.string.profile_placeholder)` ("*Profil segera hadir.*"), issuing NO network fetch. The real profile/settings surface is deferred to a separate future change (tracked by `FOLLOW_UPS.md` `mobile-profile-section-screen`), which will MODIFY this requirement to introduce the live surface.

#### Scenario: Profil section shows the placeholder copy and issues no fetch

- **GIVEN** a Ktor MockEngine capturing all outbound requests, wired into the composed shell
- **WHEN** the Profil section is selected and rendered
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.profile_placeholder)` AND no network request is captured for the Profil section
