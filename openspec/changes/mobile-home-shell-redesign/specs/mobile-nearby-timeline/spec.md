## MODIFIED Requirements

### Requirement: NearbyTimelineScreen renders the Nearby feed surface

The mobile app SHALL ship a composable `NearbyTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`) that renders the authenticated Nearby feed. The screen is navigation-free (it holds no back-stack reference and is embedded directly by `HomeScreen` as the Nearby pager page). The screen SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` or `TopAppBar` (the app section shell owns the single inset-owning `Scaffold` per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"). The screen SHALL display: (a) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields" requirement) wrapped in a pull-to-refresh container that **fills the available space** between the tab row and the bottom navigation; (b) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. The screen SHALL NOT render a redundant in-screen header duplicating the selected section/tab (the `timeline_nearby_title` "*Post dari lokasi ini*" `TopAppBar` title is removed — see the `shared-resources` retention note and the docs amendment; the location disambiguation it previously carried moves to the one-time onboarding hint per `docs/03-UX-Design.md`). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Screen renders inset-free with no redundant header

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the screen declares no `Scaffold` and no `TopAppBar` AND renders no node whose text matches `stringResource(Res.string.timeline_nearby_title)` (the redundant "Post dari lokasi ini" header is removed)

#### Scenario: The post list fills the available space

- **GIVEN** `NearbyTimelineScreen` composed under `NearYouTheme` with a fake emitting a loaded list, inside the shell's padded body
- **THEN** the pull-to-refresh list occupies the full height between the tab row and the bottom navigation (the list is `fillMaxSize` under the shell-provided padding, with no extra header band or unfilled gap)

#### Scenario: No hardcoded UI strings in NearbyTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: HomeScreen hosts NearbyTimelineScreen and routing is unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`, mapped from the `HomeRoute` `NavKey` by the `entryProvider`) SHALL be the Nearby/Following/Global **tab host** (per the `mobile-home-tab-host` capability) rather than a direct single-feed host. `NearbyTimelineScreen` SHALL be rendered as the **Nearby tab's** content within that host (Nearby is the default authenticated tab). `RootRouterScreen` SHALL continue to route the authenticated path to `HomeRoute` — the authenticated routing **target** (Home) is unchanged; only `HomeScreen`'s internal body changes. `HomeScreen` SHALL NOT render `home_placeholder_title` or `home_placeholder_version` (those strings are retained in the catalog but unreferenced by `HomeScreen`).

#### Scenario: HomeScreen's Nearby tab renders the Nearby timeline content

- **WHEN** a test composes the `HomeScreen` composable under `NearYouTheme` with the timeline fake emitting a loaded list (the Nearby tab is selected by default)
- **THEN** the rendered tree renders the Nearby feed surface (the `NearbyTimelineScreen` post list / its loading skeleton — asserted via the Nearby feed list test tag / Nearby-only content, NOT the removed `timeline_nearby_title` header) AND contains NO node whose text matches `stringResource(Res.string.home_placeholder_title)`

#### Scenario: RootRouterScreen still routes to HomeRoute

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`
- **THEN** the authenticated branch routes to `HomeRoute` (the `HomeScreen` tab-host composable) — the routing **target** is unchanged (Home); this change introduces no edit to `RootRouterScreen`'s routing targets

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → a node with `stringResource(Res.string.timeline_empty_nearby)` AND a "lihat Global" CTA labelled `stringResource(Res.string.cta_see_global)` that invokes a hoisted `onSeeGlobal` callback (wired by the tab host to select the Global tab — `NearbyTimelineScreen` remains navigation-free).
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty-area copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

#### Scenario: Initial loading shows the skeleton and the loading copy, no pull-to-refresh spinner

- **WHEN** the screen is in the initial-load state (no content yet)
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_loading)` AND a single in-content indicator AND the `PullToRefreshBox` `isRefreshing` argument is `false`

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Empty area shows the sparse-area copy plus a lihat-Global CTA

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_empty_nearby)` AND a clickable node whose text matches `stringResource(Res.string.cta_see_global)` AND does NOT contain `stringResource(Res.string.timeline_limit_hard)`

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Rate-limit hard shows the limit copy with no posts

- **WHEN** the outcome is `Loaded` with empty posts and `upsell.hard = true`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_limit_hard)` AND renders zero post cards AND does NOT contain `stringResource(Res.string.timeline_empty_nearby)`

#### Scenario: Rate-limit soft shows posts plus a non-blocking banner

- **WHEN** the outcome is `Loaded` with 5 posts and `upsell.soft = true`
- **THEN** the rendered tree renders the 5 post cards AND contains a banner node whose text matches `stringResource(Res.string.timeline_limit_soft)`

#### Scenario: Empty-state CTA switches to the Global tab

- **GIVEN** the tab host is composed with the Nearby tab selected and the Nearby feed in the empty state
- **WHEN** the `cta_see_global` control is activated
- **THEN** the hoisted `onSeeGlobal` callback fires AND the tab host selects the Global tab (the body renders the Global feed surface — asserted via the Global feed list test tag / Global-only content, NOT the removed `timeline_global_title` header)

### Requirement: Pure NearbyTimelineUiState plus a unit-testable projection

The mobile app SHALL model the screen state as a Compose-free `NearbyTimelineUiState` data class (or sealed type) and a pure projection function `nearbyTimelineUiState(outcome: NearbyTimelineOutcome?, isInitialLoad: Boolean): NearbyTimelineUiState` — mirroring `mobile-age-gate`'s `AgeGateUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL map `isInitialLoad = true` (no content yet) to `Loading`, and otherwise map the `outcome` to its state — so that during a refresh (`isInitialLoad = false`, a previous `Loaded` outcome retained) it returns `Content`, NOT `Loading`. The pull-to-refresh indicator state (`isRefreshing`) is carried separately and passed to `PullToRefreshBox`, NOT folded into this projection. The projection MUST NOT carry any PII (no `author_user_id`, no coordinates) beyond the display fields the cards render.

#### Scenario: Projection maps each outcome to its state with the initial-vs-refresh distinction

- **WHEN** the projection is invoked for `isInitialLoad = true` (any outcome), for `Loaded(non-empty, no upsell)` with `isInitialLoad = false`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** the `isInitialLoad = true` call returns `Loading`; each non-initial call returns the corresponding content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: A retained Loaded outcome during refresh projects to Content, not Loading

- **WHEN** the projection is invoked with a previous `Loaded(non-empty)` outcome AND `isInitialLoad = false`
- **THEN** it returns `Content` (the list stays); the refresh indicator is conveyed via the separate `isRefreshing` value, not by flipping the state to `Loading`

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable the gesture is attached to is never torn down — the prior bug, where the in-flight state collapsed the list to a full-screen loader, is removed); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred to `mobile-nearby-timeline-infinite-scroll`.

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change AND `FOLLOW_UPS.md` contains an entry `mobile-nearby-timeline-infinite-scroll`

### Requirement: Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip

The Nearby feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped ViewModel (`NearbyTimelineViewModel`, resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — see `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction. The ViewModel SHALL expose two distinct booleans — `isInitialLoad` (true only until the first outcome arrives) and `isRefreshing` (true during a reload while a prior outcome is retained) — replacing the prior single `inFlight` flag; on `reload()` the ViewModel SHALL keep the existing outcome and set `isRefreshing = true` (so the screen keeps rendering `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` — which survives both the post composer being on top AND switching/swiping between the Nearby/Following/Global feeds (and bottom-nav sections) — opening the composer and returning, or swiping away and back to Nearby, SHALL NOT re-fetch the Nearby feed; the already-loaded posts are shown immediately. The ViewModel is cleared only when `HomeRoute` is popped. A coordinate-acquisition failure SHALL continue to map to the existing retryable `NearbyTimelineOutcome.NetworkError` (no new outcome member).

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a `commonTest` `NearbyTimelineViewModel` over a `FakeNearbyTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, not isInitialLoad

- **GIVEN** a `NearbyTimelineViewModel` that has loaded a `Loaded` outcome (so `isInitialLoad = false`)
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `isInitialLoad` remains `false` AND the previously exposed `Loaded` outcome is retained (not nulled) so the screen keeps rendering `Content`; on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: NearbyFeed observes the entry-scoped ViewModel, not composition-local remember

- **WHEN** inspecting `NearbyFeed` in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the feed's `outcome` / `isInitialLoad` / `isRefreshing` are observed from a `viewModel { NearbyTimelineViewModel(...) }` (collected via `collectAsState`), not driven by a composition-local `LaunchedEffect` over a `remember`-ed reload counter

#### Scenario: Swiping away and returning to Nearby does not re-fetch

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the tab host composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test swipes to the Global tab and then back to the Nearby tab
- **THEN** the Nearby fetch invocation count remains 1 (the `HomeRoute`-scoped ViewModel survived the swipe — no re-fetch)
