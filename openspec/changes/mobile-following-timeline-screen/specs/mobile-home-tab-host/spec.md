# mobile-home-tab-host — Delta Specification

## ADDED Requirements

### Requirement: Following tab renders the live Following feed

The Following tab SHALL render the live `FollowingTimelineScreen` (`mobile-following-timeline`) as the middle page (page 1) of the Home `HorizontalPager`, replacing the retired `FollowingPlaceholderScreen`. The Following page SHALL issue `GET /api/v1/timeline/following` on first display via its `HomeRoute`-scoped `FollowingTimelineViewModel` (resolved via `viewModel { }` under the `HomeRoute` NavEntry, exactly as the Nearby and Global pages resolve theirs). The Following page SHALL compose **directly** under the `HomeRoute` scope (NOT inside a per-tab `NavDisplay` and NOT introducing any new tab-root `NavKey`), so its ViewModel survives feed swipes/tab switches, bottom-nav section switches, and the composer round-trip with **no re-fetch**. The host SHALL hoist `onOpenPost` and `onOpenPostReply` into the Following page (per the MODIFIED § "The tab host hoists onOpenPost …") and SHALL additionally wire an `onSwitchToGlobal` lambda into the Following page (the empty-state "*Lihat Global*" CTA per `mobile-following-timeline` § "The empty-state CTA switches the Home pager to the Global tab"), implemented as `pagerState.animateScrollToPage(<Global page index>)`.

#### Scenario: Following tab renders the live feed and fetches once on first display

- **GIVEN** the Home section composed over a Ktor MockEngine wired to the Following graph, with the Following tab selected
- **THEN** the Following page renders `FollowingTimelineScreen` (its loaded list, loading skeleton, or directive empty state — NOT the removed placeholder) AND exactly one `GET` request to a path containing `/api/v1/timeline/following` is issued on first display

#### Scenario: Following feed survives swipe/section/composer round-trips with no re-fetch

- **GIVEN** a `FakeFollowingTimelineFlow` counting fetch invocations, the Home section composed with the Following tab selected (one Following fetch having occurred)
- **WHEN** the test swipes to Global and back to Following, then switches to the Notifikasi section and back to Home/Following, then opens the composer and returns
- **THEN** the Following fetch count remains 1 (the `HomeRoute`-scoped ViewModel is not reconstructed)

#### Scenario: The empty-state "Lihat Global" CTA animates the pager to Global

- **GIVEN** the Home section composed with the Following tab selected and the Following feed in its directive empty state
- **WHEN** the "*Lihat Global*" (`cta_see_global`) control is activated
- **THEN** the pager scrolls to the Global page (page 2) AND the `PrimaryTabRow` selected tab becomes Global

## MODIFIED Requirements

### Requirement: HomeScreen is the Nearby/Following/Global tab host

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render the **Home section's content**: the Nearby / Following / Global feeds as a **top tab row** (Material 3 `PrimaryTabRow`), NOT as the bottom `NavigationBar` (the bottom bar is the app section shell — see § "Bottom navigation is a top-level section shell"). `HomeScreen` SHALL render a `PrimaryTabRow` with exactly three feed tabs — Nearby, Following, Global — each labelled via `stringResource` (`Res.string.tab_nearby`, `Res.string.tab_following`, `Res.string.tab_global`) **in Bahasa Indonesia** (per `mobile-design-system` § "User-facing labels are single-language Bahasa Indonesia"). The tabs SHALL be **text-only** with the Material 3 `PrimaryTabRow` underline indicator — NO icon and NO brand-tinted dot (per `mobile-design-system` § "Material 3 icons are the canonical navigation, action, and card affordance"). The body below the tab row SHALL render the selected feed tab's content via a **swipeable `HorizontalPager`** (per § "Feed tabs are swipeable via a HorizontalPager synced with the tab row"): Nearby → `NearbyTimelineScreen`; Following → `FollowingTimelineScreen` (per § "Following tab renders the live Following feed" / `mobile-following-timeline`); Global → `GlobalTimelineScreen`. `HomeScreen` SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` — the app section shell owns the single inset-owning `Scaffold` (per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"); `HomeScreen` renders the tab row + pager under the shell's `innerPadding`. No hardcoded UI string literals SHALL appear in `HomeScreen`. `HomeScreen` SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Home section renders three labelled feed tabs in a top tab row

- **WHEN** a test composes `HomeScreen` under `NearYouTheme` (with fakes for the feed tabs)
- **THEN** the rendered tree contains a `PrimaryTabRow` with selectable **text-only** feed tabs (no icon, no dot) whose text matches `stringResource(Res.string.tab_nearby)`, `stringResource(Res.string.tab_following)`, and `stringResource(Res.string.tab_global)` — and NO bottom `NavigationBar` is rendered by `HomeScreen` itself (the bottom nav belongs to the shell)

#### Scenario: Selecting a feed tab swaps the body to that feed's content

- **GIVEN** `HomeScreen` composed with the Nearby feed tab selected (default)
- **WHEN** the test activates the Global feed tab
- **THEN** the body renders the Global feed surface (its list or loading skeleton — asserted via the Global feed list test tag / Global-only content) AND no longer renders the Nearby feed surface

#### Scenario: HomeScreen declares no Scaffold of its own

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** `HomeScreen` declares no `Scaffold` and no `TopAppBar`; it renders the tab row + pager directly under the shell-provided padding (the single Scaffold lives in the shell)

#### Scenario: No hardcoded UI strings in HomeScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / label call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Tab switching preserves each tab's state and never re-fetches

Switching between **feed tabs** within the Home section SHALL preserve the selected-feed-tab value and each feed's already-loaded state. Because the Nearby, Following, and Global feed load-state ViewModels are scoped to the `HomeRoute` NavEntry (`mobile-nearby-timeline` § "Nearby feed load state is scoped …", `mobile-following-timeline` § "Following feed load state is scoped …", and `mobile-global-timeline` § "Global feed load state is scoped …") and each feed screen composes directly under that scope, leaving a feed tab and returning SHALL NOT trigger a re-fetch (the previously loaded posts render immediately). Switching **bottom-nav sections** (Home ↔ Notifikasi ↔ Profil) and returning to Home SHALL likewise preserve the Home feeds' loaded state (the Home section content is not torn down and re-fetched on section switch).

#### Scenario: Returning to a feed tab does not re-fetch

- **GIVEN** a commonTest with a `FakeNearbyTimelineFlow` + `FakeFollowingTimelineFlow` + `FakeGlobalTimelineFlow` counting fetch invocations, the Home section composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Following feed tab (first Following fetch occurs), then the Global feed tab (first Global fetch occurs), then back to Nearby, then back to Following, then back to Global
- **THEN** the Nearby fetch count remains 1 AND the Following fetch count remains 1 AND the Global fetch count remains 1 (no re-fetch on feed-tab return)

#### Scenario: Returning to the Home section does not re-fetch the feeds

- **GIVEN** the shell composed with the Home section selected and the Nearby feed loaded once
- **WHEN** the test switches to the Notifikasi section and back to Home
- **THEN** the Nearby feed's fetch count is unchanged (the Home feeds are not re-fetched on section return)

### Requirement: The tab host hoists onOpenPost, wired at the call site to a root-stack PostDetailRoute push

`HomeScreen` SHALL hoist an `onOpenPost(...)` callback (taking a card's non-PII display fields) and pass it into ALL THREE feed tabs — `NearbyTimelineScreen`, `FollowingTimelineScreen`, and `GlobalTimelineScreen` — exactly as it already hoists `onOpenComposer`. It SHALL additionally hoist an `onOpenPostReply(...)` callback (same non-PII display-field payload — the feed cards' reply shortcut) into all three feed tabs. The actual `PostDetailRoute` **root** back-stack appends SHALL be wired at the **shell** call site (in `screens/routing/AppEntryProvider.kt`, where `appEntryProvider` maps `HomeRoute` → `AppShellScreen(onOpenComposer = { backStack.add(PostCreationRoute) }, onOpenPost = { … backStack.add(PostDetailRoute(...)) }, onOpenPostReply = { … backStack.add(PostDetailRoute(..., focusReplyComposer = true)) })`; `AppShellScreen` forwards both to the Home section's `HomeScreen`), NOT inside `HomeScreen.kt` / `AppShellScreen.kt` (neither holds a back-stack reference, matching the existing composer-FAB wiring). The appended `PostDetailRoute` SHALL be constructed from exactly the card fields (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`; never `latitude`/`longitude`, never the author UUID) — with `focusReplyComposer = true` when constructed from `onOpenPostReply` and the default `false` when constructed from the whole-card `onOpenPost` — so the detail surface overlays the section `NavigationBar`, NOT introducing a per-tab `NavDisplay` back stack (still deferred per GitHub issue [#189](https://github.com/aditrioka/nearyou-id/issues/189) `mobile-home-tab-host-per-tab-backstacks` (label `follow-up`)). As of `mobile-following-timeline-screen` the **Following tab is a live feed** and therefore wires `onOpenPost` and `onOpenPostReply` identically to Nearby and Global (the prior "the Following tab is a deferred placeholder and wires no callbacks" clause is removed). The host additionally wires an `onSwitchToGlobal` lambda into the Following page (per § "Following tab renders the live Following feed"). The Following cards supply `distanceM = null` (Following has no spatial filter).

#### Scenario: Invoking onOpenPost in any feed tab pushes PostDetailRoute onto the root stack

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack, or `HomeScreen` composed with a recording `onOpenPost` callback
- **WHEN** a Nearby card's `onOpenPost` is invoked (and again with the Following tab selected and its card's `onOpenPost`, and again with the Global tab)
- **THEN** in each case a `PostDetailRoute` carrying the card's display fields including `authorUsername`/`authorDisplayName` with `focusReplyComposer = false` (and no `latitude`/`longitude`, no author UUID) is appended to the **root** back stack, becoming the current entry over `HomeRoute` — and the Following case carries `distanceM = null`

#### Scenario: Invoking onOpenPostReply pushes the route with focusReplyComposer = true

- **GIVEN** the `AppShellScreen` call site (`appEntryProvider`) composed over a test root back stack
- **WHEN** the Nearby card's `onOpenPostReply` is invoked (and again from the Following tab, and again from the Global tab)
- **THEN** in each case a `PostDetailRoute` carrying the same non-PII display fields with `focusReplyComposer = true` is appended to the **root** back stack

#### Scenario: HomeScreen hoists both callbacks into all three feed tabs; the appends live at the call site; no per-tab NavDisplay

- **WHEN** inspecting `screens/home/HomeScreen.kt`, `screens/shell/AppShellScreen.kt`, and `screens/routing/AppEntryProvider.kt`
- **THEN** `HomeScreen` takes `onOpenPost` and `onOpenPostReply` as hoisted parameters and passes them into all three feed tabs (including `FollowingTimelineScreen`) and holds no back-stack reference, `AppShellScreen` forwards both to the Home-section `HomeScreen`, AND the `backStack.add(PostDetailRoute(...))` appends live at the `AppShellScreen(...)` call site in `appEntryProvider`, AND no per-tab `NavDisplay` / tab-root `NavKey` is introduced

### Requirement: Test coverage for the tab host

The change SHALL ship: (1) a Robolectric shell/host test (`mobile/app/src/androidUnitTest/...`, e.g. `AppShellScreenTest` / extended `HomeScreenTest` / `HomeTabHostScreenTest`) covering the three bottom-nav sections, section switching swapping the section body, the three Home feed top-tabs, feed-tab switching swapping the feed body, the composer FAB on the Home section, the **Profil placeholder and the live Following feed** (the Following page now renders `FollowingTimelineScreen` and issues its fetch), and the Notifikasi badge — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention); (2) a commonTest covering the selected-section + selected-feed-`Tab` saved-state round-trips + the no-re-fetch-on-feed-tab-switch AND no-re-fetch-on-section-switch invariants via fakes (now including the Following fake); (3) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `HomeTabHostFlowIosTest`) exercising the shell + Home tabs on the simulator, with Kotlin/Native-legal test function names. The obsolete `FollowingTabNoFetchScanTest` (which asserted the Following tab issues no fetch) SHALL be **removed** — the live feed now fetches on first display.

#### Scenario: Shell + tab-host tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the shell/host Robolectric test and the commonTest serialization + no-re-fetch tests are discovered AND each documented shell/tab-host behavior corresponds to at least one `@Test` AND no `FollowingTabNoFetchScanTest` remains in the source tree

#### Scenario: Shell/host screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the shell/host `*ScreenTest` glob alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes

## REMOVED Requirements

### Requirement: Following tab renders the deferred placeholder and issues no fetch

**Reason:** Fulfilled by the `mobile-following-timeline-screen` change. The Following tab now renders the live `FollowingTimelineScreen` (the new `mobile-following-timeline` capability + the ADDED "Following tab renders the live Following feed" requirement above), issuing `GET /api/v1/timeline/following` on first display. The two conditions that justified the deferral are resolved: the backend endpoint is shipped (carrying the author-identity fields `mobile-timeline-card-redesign` added for this consumer), and the follow-action UI is in flight (`mobile-profile-screen`, PR [#245](https://github.com/aditrioka/nearyou-id/pull/245)). The requirement's "issues no fetch" / "wires no Following timeline API client" scenarios are now intentionally false.

**Migration:** `FollowingPlaceholderScreen.kt` is deleted and the Following pager page is repointed to `FollowingTimelineScreen`. The `timeline_following_placeholder` string is **retained and reused** as the live feed's directive empty-state copy (per `mobile-following-timeline` § "Screen state mapping …"). The `FollowingTabNoFetchScanTest` is removed (covered by the MODIFIED "Test coverage for the tab host" requirement). No data migration is required.
