# mobile-home-tab-host Specification

## Purpose

The Nearby/Following/Global tab host in `:mobile:app`. `HomeScreen` is repurposed from a single-feed host into a Material 3 `NavigationBar` host whose body renders the selected tab's screen **directly under the `HomeRoute` scope** — selection is a `rememberSaveable` serializable `Tab` enum, NOT per-tab `NavDisplay` back stacks. Because each tab's feed screen composes directly under the `HomeRoute` NavEntry, its feed load-state ViewModel (resolved via `viewModel { }` inside the screen, exactly as the shipped Nearby feed already does) is `HomeRoute`-scoped and survives both tab switches and the composer round-trip with no re-fetch. Per-tab `NavDisplay` back stacks are **deferred** to the first intra-tab destination (post detail / profile) — there is no intra-tab navigation in this change, so building them now would be vestigial; the deferral is tracked by `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`. The composer FAB stays at the home level (one affordance shared across all three tabs) and pushes `PostCreationRoute` onto the **root** back stack so the composer overlays the tab bar. The Nearby tab hosts the shipped `NearbyTimelineScreen`; the Global tab hosts the new `GlobalTimelineScreen` (`mobile-global-timeline`); the Following tab renders a documented empty-state placeholder and issues NO network fetch (the real Following feed is deferred — there is no follow-action UI yet — and tracked by `FOLLOW_UPS.md` `mobile-following-timeline-screen`). Every label/copy is sourced via `:shared:resources`; the authenticated default tab is Nearby. This closes the `FOLLOW_UPS.md` entries `mobile-home-tab-host` + `mobile-timeline-empty-global-cta`.
## Requirements
### Requirement: HomeScreen is the Nearby/Following/Global tab host

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render the **Home section's content**: the Nearby / Following / Global feeds as a **top tab row** (Material 3 `PrimaryTabRow`), NOT as the bottom `NavigationBar` (the bottom bar is the app section shell — see § "Bottom navigation is a top-level section shell"). `HomeScreen` SHALL render a `PrimaryTabRow` with exactly three feed tabs — Nearby, Following, Global — each labelled via `stringResource` (`Res.string.tab_nearby`, `Res.string.tab_following`, `Res.string.tab_global`) **in Bahasa Indonesia** (per `mobile-design-system` § "User-facing labels are single-language Bahasa Indonesia"). The tabs SHALL be **text-only** with the Material 3 `PrimaryTabRow` underline indicator — NO icon and NO brand-tinted dot (per `mobile-design-system` § "Material 3 icons are the canonical navigation, action, and card affordance"; matching the operator's X / Niche-style text-tab references, `design.md` D10). The body below the tab row SHALL render the selected feed tab's content via a **swipeable `HorizontalPager`** (per § "Feed tabs are swipeable via a HorizontalPager synced with the tab row"): Nearby → `NearbyTimelineScreen`; Following → the deferred placeholder per the unchanged § "Following tab renders the deferred placeholder"; Global → `GlobalTimelineScreen`. `HomeScreen` SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` — the app section shell owns the single inset-owning `Scaffold` (per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"); `HomeScreen` renders the tab row + pager under the shell's `innerPadding`. No hardcoded UI string literals SHALL appear in `HomeScreen`. `HomeScreen` SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Home section renders three labelled feed tabs in a top tab row

- **WHEN** a test composes `HomeScreen` under `NearYouTheme` (with fakes for the feed tabs)
- **THEN** the rendered tree contains a `PrimaryTabRow` with selectable **text-only** feed tabs (no icon, no dot) whose text matches `stringResource(Res.string.tab_nearby)`, `stringResource(Res.string.tab_following)`, and `stringResource(Res.string.tab_global)` — and NO bottom `NavigationBar` is rendered by `HomeScreen` itself (the bottom nav belongs to the shell)

#### Scenario: Selecting a feed tab swaps the body to that feed's content

- **GIVEN** `HomeScreen` composed with the Nearby feed tab selected (default)
- **WHEN** the test activates the Global feed tab
- **THEN** the body renders the Global feed surface (its list or loading skeleton — asserted via the Global feed list test tag / Global-only content, NOT the removed `timeline_global_title` header) AND no longer renders the Nearby feed surface

#### Scenario: HomeScreen declares no Scaffold of its own

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** `HomeScreen` declares no `Scaffold` and no `TopAppBar`; it renders the tab row + pager directly under the shell-provided padding (the single Scaffold lives in the shell)

#### Scenario: No hardcoded UI strings in HomeScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / label call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Tab selection is serializable and survives process death

The selected **feed tab** within the Home section SHALL be modeled as a `@Serializable` `Tab` enum (Nearby / Following / Global) held in `rememberSaveable`, so it survives configuration change and process death on every target including Kotlin/Native (iOS), where reflection-based saving is unavailable. The selected **bottom-nav section** SHALL likewise be serializable (see § "Bottom navigation is a top-level section shell"). The Home section SHALL render the selected feed tab's screen **under the `HomeRoute` scope via a `HorizontalPager`** (per § "Feed tabs are swipeable via a HorizontalPager synced with the tab row") — NOT inside a per-tab `NavDisplay` — so each feed screen's `viewModel { }` resolves to the `HomeRoute` NavEntry store. The `HorizontalPager` is a layout surface within the single `HomeRoute` scope and is explicitly NOT a per-tab navigation scope. Per-tab `NavDisplay` back stacks remain **deferred** (tracked by `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`); this change adds NO new tab-root `NavKey`s.

#### Scenario: Selected feed tab survives a saved-state round-trip

- **GIVEN** a commonTest that sets the selected feed `Tab` and saves + restores it via the `rememberSaveable` saver (the serializable-enum path)
- **WHEN** the saved value is restored
- **THEN** the restored selection equals the original `Tab` (no `SerializationException`) — proving the iOS-safe saved-state path

#### Scenario: The pager is not a per-tab NavDisplay and adds no tab-root NavKey

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt` and `screens/routing/NavKeys.kt`
- **THEN** the Home section renders the feeds via a single `HorizontalPager` under the `HomeRoute` scope (no nested per-tab `NavDisplay`) AND no `NearbyTabRoot` / `FollowingTabRoot` / `GlobalTabRoot` `NavKey` is declared (per-tab back stacks remain deferred)

### Requirement: The composer FAB stays at the home level and pushes onto the root back stack

The **Home section** SHALL render a single **icon-only** `FloatingActionButton` (the composer affordance) — visible across all three feed tabs of the Home section — that invokes the injected `onOpenComposer` lambda, which appends `PostCreationRoute` to the **root** back stack (above the shell), so the composer overlays the entire surface including the bottom `NavigationBar`. The FAB SHALL render a Material **icon** (per `mobile-design-system` § "Material 3 icons are the canonical navigation and action affordance") with a `contentDescription` sourced via `stringResource(Res.string.cta_post)` — it SHALL NOT display a visible text label (it is a `FloatingActionButton`, NOT an `ExtendedFloatingActionButton`). The FAB MUST NOT be duplicated per feed tab; it pushes onto the root back stack only. The FAB belongs to the Home section (it is NOT shown on the Notifikasi or Profil sections) and is NOT hosted by a nested `Scaffold` inside `HomeScreen` (the single Scaffold is the shell's; the FAB is rendered in the Home section's inset-free body or the shell's FAB slot gated on the Home section).

#### Scenario: FAB is icon-only and present on every Home feed tab

- **GIVEN** the Home section composed over a test root back stack (or with a recording `onOpenComposer` callback)
- **WHEN** the FAB is activated while the Nearby feed tab is selected, and again while the Global feed tab is selected
- **THEN** a single `FloatingActionButton` (icon-only — a Material icon with `contentDescription` = `stringResource(Res.string.cta_post)`, no visible text label) is present in both cases AND each activation appends `PostCreationRoute` to the root back stack (or invokes the recording callback)

#### Scenario: FAB is absent on the Notifikasi and Profil sections

- **GIVEN** the shell composed with the Notifikasi section selected, and again with the Profil section selected
- **THEN** no composer `FloatingActionButton` is rendered in either case (the FAB belongs to the Home section only)

### Requirement: The authenticated default tab is Nearby

When the shell is first composed for an authenticated session, the selected **section** SHALL default to **Home**, and within the Home section the selected **feed tab** SHALL default to **Nearby** (preserving the pre-restructure landing). Both the selected-section value and the selected-feed-tab value SHALL be held in `rememberSaveable` so they survive configuration change and process death. (The `docs/03-UX-Design.md` "Default tab: Global" describes the deferred guest pre-login first-open, NOT the authenticated home — the authenticated landing is Nearby.)

#### Scenario: First composition selects Home → Nearby

- **WHEN** the shell is composed fresh (no saved selected-section/feed-tab state)
- **THEN** the Home section is selected AND within it the Nearby feed tab is selected AND the body renders the Nearby feed surface (its list or loading skeleton — asserted via the Nearby feed list test tag / Nearby-only content, NOT the removed `timeline_nearby_title` header), NOT the Global or Following surface

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between **feed tabs** within the Home section SHALL preserve the selected-feed-tab value and each feed's already-loaded state. Because the Nearby and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …" and `mobile-global-timeline` § "Global feed load state is scoped …") and each feed screen composes directly under that scope, leaving a feed tab and returning SHALL NOT trigger a re-fetch (the previously loaded posts render immediately). Switching **bottom-nav sections** (Home ↔ Notifikasi ↔ Profil) and returning to Home SHALL likewise preserve the Home feeds' loaded state (the Home section content is not torn down and re-fetched on section switch).

#### Scenario: Returning to a feed tab does not re-fetch

- **GIVEN** a commonTest with a `FakeNearbyTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the Home section composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Global feed tab (first Global fetch occurs), then back to Nearby, then back to Global
- **THEN** the Nearby fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on feed-tab return)

#### Scenario: Returning to the Home section does not re-fetch the feeds

- **GIVEN** the shell composed with the Home section selected and the Nearby feed loaded once
- **WHEN** the test switches to the Notifikasi section and back to Home
- **THEN** the Nearby feed's fetch count is unchanged (the Home feeds are not re-fetched on section return)

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

The change SHALL ship: (1) a Robolectric shell/host test (`mobile/app/src/androidUnitTest/...`, e.g. `AppShellScreenTest` / extended `HomeScreenTest`) covering the three bottom-nav sections, section switching swapping the section body, the three Home feed top-tabs, feed-tab switching swapping the feed body, the composer FAB on the Home section, the Following + Profil placeholders, and the Notifikasi badge — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention, since the `ui-test-manifest` host activity is debug-only); (2) a commonTest covering the selected-section + selected-feed-`Tab` saved-state round-trips + the no-re-fetch-on-feed-tab-switch AND no-re-fetch-on-section-switch invariants via fakes; (3) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the shell + Home tabs on the simulator, with Kotlin/Native-legal test function names.

#### Scenario: Shell + tab-host tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the shell/host Robolectric test and the commonTest serialization + no-re-fetch tests are discovered AND each documented shell/tab-host behavior corresponds to at least one `@Test`

#### Scenario: Shell/host screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the shell/host `*ScreenTest` glob alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Bottom navigation is a top-level section shell (Home / Notifikasi / Profil)

The authenticated root surface SHALL be an app **section shell** (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt` or equivalent) rendering a Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` with exactly three top-level **sections** — **Home**, **Notifikasi**, **Profil** — each labelled via `stringResource` (`Res.string.section_home`, `Res.string.section_notifications`, `Res.string.section_profile`) with a **Material icon** (per `mobile-design-system` § "Material 3 icons …" — a bundled vector drawable, NOT a brand-tinted dot) + `contentDescription` via `stringResource`. This shell `Scaffold` SHALL be the **single inset-owning Scaffold** for the authenticated surface, running edge-to-edge (per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"); section/feed/screen content rendered in its body is inset-free. Each section item's label SHALL be **visible in both the selected and unselected states** via readable M3 item theming — the shell's `nearYouNavigationBarItemColors()`, NOT the bare `NavigationBarItemDefaults.colors()` default (per `mobile-design-system` § "Navigation and tab labels are visible in selected and unselected states"). The shell body SHALL render the selected section's content (Home → `HomeScreen`; Notifikasi → the notifications surface per `mobile-notifications-list`; Profil → the deferred placeholder per § "The Profil section renders a deferred placeholder"). The selected section SHALL be a `@Serializable` `Section` enum held in `rememberSaveable` (iOS-safe), defaulting to Home. The shell SHALL be the authenticated root surface (mapped from the root `NavDisplay` via `AppEntryProvider`); the Home section's content (`HomeScreen`) SHALL render under the `HomeRoute` NavEntry scope so the feed ViewModels continue to resolve to `HomeRoute` scope (per `mobile-app-scaffold` § entry decorators), preserving the no-re-fetch invariant. No hardcoded UI string literals SHALL appear in the shell source. The shell SHALL render under `NearYouTheme`.

#### Scenario: Shell renders three labelled sections with Material icons and defaults to Home

- **WHEN** a test composes the shell under `NearYouTheme` with fakes
- **THEN** the rendered tree contains a bottom `NavigationBar` with selectable nodes whose text matches `stringResource(Res.string.section_home)`, `stringResource(Res.string.section_notifications)`, and `stringResource(Res.string.section_profile)` — each with a Material icon affordance — AND the Home section is selected by default (the Home feed surface is rendered)

#### Scenario: Selected section label is visible (not collapsed to the background)

- **GIVEN** the shell composed with the Home section selected
- **THEN** the selected section's label node is present AND its resolved content color is a non-background Material 3 token (the label is not invisible)

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

### Requirement: The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into BOTH the Nearby tab content (`NearbyTimelineScreen`) and the Global tab content (`GlobalTimelineScreen`) — exactly as it already hoists `onOpenComposer`. The actual `PostDetailRoute` **root** back-stack append SHALL be wired at the **shell** call site (in `screens/routing/AppEntryProvider.kt`, where — after the section-shell restructure of § "Bottom navigation is a top-level section shell" — `appEntryProvider` maps `HomeRoute` → `AppShellScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) })`; `AppShellScreen` forwards `onOpenPost` to the Home section's `HomeScreen`), NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`; never `latitude`/`longitude`) so the detail surface overlays the section `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per `FOLLOW_UPS mobile-home-tab-host-per-tab-backstacks`). The Following tab (a deferred placeholder) wires no `onOpenPost` (it has no feed/cards).

#### Scenario: Invoking onOpenPost in either feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback, with the Nearby tab selected
- **WHEN** the Nearby card's `onOpenPost` is invoked (and again with the Global tab selected and its card's `onOpenPost`)
- **THEN** in both cases a `PostDetailRoute` carrying the card's display fields (and no `latitude`/`longitude`) is appended to the **root** back stack, becoming the current entry over `HomeRoute`

#### Scenario: HomeScreen hoists onOpenPost; the append lives at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt`, `screens/shell/AppShellScreen.kt`, and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` as a hoisted parameter and holds no back-stack reference, `AppShellScreen` forwards `onOpenPost` to the Home-section `HomeScreen`, AND the `backStack.add(PostDetailRoute(...))` append lives at the `AppShellScreen(...)` call site in `appEntryProvider` (the same mechanism as `onOpenComposer`), AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced

### Requirement: Feed tabs are swipeable via a HorizontalPager synced with the tab row

The Home section's three feeds SHALL be horizontally swipeable: the body below the `PrimaryTabRow` SHALL be a Material 3 `HorizontalPager` whose page order equals the tab order (page 0 = Nearby, page 1 = Following, page 2 = Global). The pager and the tab row SHALL be **bidirectionally synced**: `PrimaryTabRow`'s `selectedTabIndex` is driven by `pagerState.currentPage`; tapping a tab animates the pager to that page (`pagerState.animateScrollToPage`); swiping the pager updates the selected tab once the swipe settles. Swiping left advances to the next feed (Nearby → Following → Global) and swiping right returns to the previous one. The selected feed SHALL remain modeled as a `@Serializable Tab` enum in `rememberSaveable` (the durable selection, kept in sync with the settled pager page), so it survives configuration change and process death on every target including Kotlin/Native. The pager SHALL NOT introduce a per-tab `NavDisplay` or any new tab-root `NavKey`: all three pages compose **directly** under the `HomeRoute` scope, so each feed's `viewModel { }` resolves to the `HomeRoute` store and switching feeds by swipe or tap (or switching bottom-nav sections and returning) does NOT re-fetch (the `HomeRoute`-scoped ViewModels survive). 

#### Scenario: Swiping the pager changes the selected feed tab

- **GIVEN** the Home section composed with the Nearby feed (page 0) selected
- **WHEN** the test performs a horizontal swipe-left gesture on the pager
- **THEN** the pager settles on the Following page (page 1) AND the `PrimaryTabRow` selected tab becomes Following AND a subsequent swipe-right returns to Nearby (page 0)

#### Scenario: Tapping a tab animates the pager to that page

- **GIVEN** the Home section composed with Nearby selected
- **WHEN** the test activates the Global tab
- **THEN** the pager scrolls to the Global page (page 2) AND the Global surface is rendered

#### Scenario: Swiping between feeds does not re-fetch and introduces no per-tab NavDisplay

- **GIVEN** a `FakeNearbyTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the Home section composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test swipes to Global (first Global fetch occurs), then swipes back to Nearby, then to Global again
- **THEN** the Nearby fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on swipe) AND inspecting `HomeScreen.kt` + `screens/routing/NavKeys.kt` shows no per-tab `NavDisplay` and no `NearbyTabRoot`/`FollowingTabRoot`/`GlobalTabRoot` `NavKey`

> The serializable-`Tab` saved-state round-trip (the durable selection kept in sync with the settled pager page) is covered by § "Tab selection is serializable and survives process death".

