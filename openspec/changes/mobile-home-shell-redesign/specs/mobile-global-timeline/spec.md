## MODIFIED Requirements

### Requirement: GlobalTimelineScreen renders the Global feed surface

The mobile app SHALL ship a composable `GlobalTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`) that renders the authenticated Global feed. The screen is navigation-free (it holds no back-stack reference; it is embedded by the tab host as the Global pager page). The screen SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` or `TopAppBar` (the app section shell owns the single inset-owning `Scaffold` per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"). The screen SHALL display: (a) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields, no distance" requirement) wrapped in a pull-to-refresh container that **fills the available space** between the tab row and the bottom navigation; (b) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. The screen SHALL NOT render a redundant in-screen header duplicating the selected section/tab (the `timeline_global_title` "*Seluruh Indonesia*" `TopAppBar` title is removed — the Global tab label already identifies the surface). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Screen renders inset-free with no redundant header

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the screen declares no `Scaffold` and no `TopAppBar` AND renders no node whose text matches `stringResource(Res.string.timeline_global_title)` (the redundant "Seluruh Indonesia" header is removed)

#### Scenario: The post list fills the available space

- **GIVEN** `GlobalTimelineScreen` composed under `NearYouTheme` with a fake emitting a loaded list, inside the shell's padded body
- **THEN** the pull-to-refresh list occupies the full height between the tab row and the bottom navigation (the list is `fillMaxSize` under the shell-provided padding, with no extra header band or unfilled gap)

#### Scenario: No hardcoded UI strings in GlobalTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → the loading-skeleton presentation reusing `stringResource(Res.string.timeline_loading)` ("*Sedang memuat postingan…*"). The existing `timeline_loading` key already holds the exact copy `docs/03-UX-Design.md` § Empty State prescribes for the Global-empty edge case (which it frames as a loading skeleton because Global is effectively never empty), so NO new `timeline_empty_global` key is added. The Empty `GlobalTimelineUiState` member remains distinct from Loading at the projection level even though both render the same skeleton + copy.
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

The screen state SHALL be modeled as a Compose-free `GlobalTimelineUiState` data class (or sealed type) plus a pure projection (`globalTimelineUiState(outcome: GlobalTimelineOutcome?, isInitialLoad: Boolean): GlobalTimelineUiState`) — mirroring `mobile-nearby-timeline`'s `NearbyTimelineUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL map `isInitialLoad = true` to `Loading`, and otherwise map the `outcome` to its state — so that during a refresh (`isInitialLoad = false`, a previous `Loaded` retained) it returns `Content`, NOT `Loading`. The pull-to-refresh `isRefreshing` value is carried separately (passed to `PullToRefreshBox`), NOT folded into this projection. The projection MUST carry no PII (no `author_user_id`, no coordinates).

#### Scenario: Projection maps each outcome to its state with the initial-vs-refresh distinction

- **WHEN** the projection is invoked for `isInitialLoad = true` (any outcome), for `Loaded(non-empty, no upsell)` with `isInitialLoad = false`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** the `isInitialLoad = true` call returns `Loading`; each non-initial call returns the corresponding content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable is never torn down); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred (tracked alongside the `mobile-nearby-timeline-infinite-scroll` follow-up, extended to cover Global).

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeGlobalTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change

### Requirement: Global feed load state is scoped to the HomeRoute NavEntry and survives tab switch and the composer round-trip

The Global feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped `GlobalTimelineViewModel` (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `HomeRoute` — `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction (the first time the Global tab/page is shown). The ViewModel SHALL expose two distinct booleans — `isInitialLoad` (true only until the first outcome arrives) and `isRefreshing` (true during a reload while a prior outcome is retained) — replacing the prior single `inFlight` flag; on `reload()` it SHALL keep the existing outcome and set `isRefreshing = true` (so the screen keeps rendering `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` (which survives both feed swipes/tab switches and the composer being pushed above it), switching/swiping away from the Global feed and back, or opening the composer and returning, SHALL NOT re-fetch the Global feed.

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a commonTest `GlobalTimelineViewModel` over a `FakeGlobalTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, not isInitialLoad

- **GIVEN** a `GlobalTimelineViewModel` that has loaded a `Loaded` outcome (so `isInitialLoad = false`)
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `isInitialLoad` remains `false` AND the previously exposed `Loaded` outcome is retained (not nulled) so the screen keeps rendering `Content`; on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: A load failure maps to the existing retryable error

- **GIVEN** a `FakeGlobalTimelineFlow` whose `loadFirstPage()` throws
- **WHEN** the `GlobalTimelineViewModel` loads
- **THEN** its exposed outcome is `GlobalTimelineOutcome.NetworkError` (the retryable state — no special outcome member for this)
