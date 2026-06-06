## MODIFIED Requirements

### Requirement: HomeScreen hosts NearbyTimelineScreen and routing is unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`, mapped from the `HomeRoute` `NavKey` by the `entryProvider`) SHALL be the Nearby/Following/Global **tab host** (per the `mobile-home-tab-host` capability) rather than a direct single-feed host. `NearbyTimelineScreen` SHALL be rendered as the **Nearby tab's** content within that host (Nearby is the default authenticated tab). `RootRouterScreen` SHALL continue to route the authenticated path to `HomeRoute` — the authenticated routing **target** (Home) is unchanged; only `HomeScreen`'s internal body changes from "renders `NearbyTimelineScreen` directly" to "renders the selected tab, which for Nearby is `NearbyTimelineScreen`". `HomeScreen` SHALL NOT render `home_placeholder_title` or `home_placeholder_version` (those strings are retained in the catalog but unreferenced by `HomeScreen`).

#### Scenario: HomeScreen's Nearby tab renders the Nearby timeline content

- **WHEN** a test composes the `HomeScreen` composable under `NearYouTheme` with the timeline fake emitting a loaded list (the Nearby tab is selected by default)
- **THEN** the rendered tree contains the `timeline_nearby_title` node (i.e., the Nearby tab delegates to `NearbyTimelineScreen`) AND contains NO node whose text matches `stringResource(Res.string.home_placeholder_title)`

#### Scenario: RootRouterScreen still routes to HomeRoute

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`
- **THEN** the authenticated branch routes to `HomeRoute` (the `HomeScreen` tab-host composable) — the routing **target** is unchanged (Home); this change introduces no edit to `RootRouterScreen`'s routing targets

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`:
- **Loading** (fetch in-flight) → a placeholder/skeleton list AND a node with `stringResource(Res.string.timeline_loading)`.
- **Content** (`Loaded` with non-empty posts) → the post-card list.
- **Empty** (`Loaded`, empty posts, no `upsell`) → a node with `stringResource(Res.string.timeline_empty_nearby)` AND a "lihat Global" CTA labelled `stringResource(Res.string.cta_see_global)` that invokes a hoisted `onSeeGlobal` callback (wired by the tab host to select the Global tab — `NearbyTimelineScreen` remains navigation-free: the callback is a hoisted lambda, not a back-stack reference). This closes the `mobile-timeline-empty-global-cta` follow-up (the empty copy implied the affordance; the CTA was deferred until a Global surface existed).
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty-area copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

#### Scenario: Loading shows the loading copy
- **WHEN** the screen is in the in-flight state
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_loading)`

#### Scenario: Empty area shows the sparse-area copy plus a lihat-Global CTA
- **WHEN** the outcome is `Loaded` with empty posts and no `upsell`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_empty_nearby)` AND a clickable node whose text matches `stringResource(Res.string.cta_see_global)` AND does NOT contain `stringResource(Res.string.timeline_limit_hard)`

#### Scenario: Empty-state CTA switches to the Global tab
- **GIVEN** the tab host is composed with the Nearby tab selected and the Nearby feed in the empty state
- **WHEN** the `cta_see_global` control is activated
- **THEN** the hoisted `onSeeGlobal` callback fires AND the tab host selects the Global tab (the body renders `stringResource(Res.string.timeline_global_title)`)

#### Scenario: Error shows network copy and a retry control
- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Rate-limit hard shows the limit copy with no posts
- **WHEN** the outcome is `Loaded` with empty posts and `upsell.hard = true`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_limit_hard)` AND renders zero post cards AND does NOT contain `stringResource(Res.string.timeline_empty_nearby)`

#### Scenario: Rate-limit soft shows posts plus a non-blocking banner
- **WHEN** the outcome is `Loaded` with 5 posts and `upsell.soft = true`
- **THEN** the rendered tree renders the 5 post cards AND contains a banner node whose text matches `stringResource(Res.string.timeline_limit_soft)`

### Requirement: Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip

The Nearby feed's first-page load state (the fetched outcome + the in-flight flag + the reload trigger) SHALL be held in a `HomeRoute`-scoped ViewModel (`NearbyTimelineViewModel`, resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — see `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction; pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` — which survives both the post composer being on top (pushed above `HomeRoute` on the root back stack) AND switching between the Nearby/Following/Global tabs (the tab selection is host state under the still-present `HomeRoute`) — opening the composer and returning, or switching to another tab and back to Nearby, SHALL NOT re-fetch the Nearby feed; the already-loaded posts are shown immediately. The ViewModel is cleared only when `HomeRoute` is popped. A coordinate-acquisition failure SHALL continue to map to the existing retryable `NearbyTimelineOutcome.NetworkError` (no new outcome member).

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
- **THEN** the feed's `outcome` / `inFlight` are observed from a `viewModel { NearbyTimelineViewModel(...) }` (collected via `collectAsState`), and the load is NOT driven by a composition-local `LaunchedEffect` over a `remember`-ed reload counter (so the load state is not lost when `HomeRoute` is disposed while the composer is on top, nor when the Nearby tab is deselected)

#### Scenario: Switching tabs and returning to Nearby does not re-fetch

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the tab host composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test switches to the Global tab and then back to the Nearby tab
- **THEN** the Nearby fetch invocation count remains 1 (the `HomeRoute`-scoped ViewModel survived the tab switch — no re-fetch)
