## MODIFIED Requirements

### Requirement: Nearby feed load state is scoped to the Home NavEntry and survives the composer round-trip

The Nearby feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped ViewModel (`NearbyTimelineViewModel`, resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — see `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction. The ViewModel SHALL expose exactly ONE `uiState: StateFlow<NearbyTimelineUiState>` produced via `combine(_outcome, _isInitialLoad) { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)`, whose projection delegates to the unchanged pure `nearbyTimelineUiState(outcome, isInitialLoad)` function (reused, not reimplemented) — so the outcome→state mapping is owned by the ViewModel and is NOT recomputed in the composable. The initial-load flag SHALL be an INTERNAL (private) `MutableStateFlow` reflected only through `uiState` (which projects to `Loading` until the first outcome arrives); it SHALL NOT be a separate public flow. The raw fetched `NearbyTimelineOutcome` SHALL remain exposed as the ViewModel's domain-state seam — it carries cursor/anchor paging state (a coordinate-bearing anchor that the PII-free `NearbyTimelineUiState` deliberately strips) and is read by the inline-like / load-more controllers and the white-box ViewModel tests; it is NOT a screen-rendered `XxxUiState`. The pull-to-refresh indicator SHALL be a SEPARATE `isRefreshing` flag, NOT folded into `uiState` (per docs/11 §2.2, "data class when fields vary independently"). On `reload()` the ViewModel SHALL keep the existing outcome and set `isRefreshing = true` (so `uiState` keeps projecting `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` — which survives both the post composer being on top AND switching/swiping between the Nearby/Following/Global feeds (and bottom-nav sections) — opening the composer and returning without posting, or swiping away and back to Nearby, SHALL NOT re-fetch the Nearby feed; the already-loaded posts are shown immediately. The one exception is a successful post: the HomeRoute feed reload key changes (`mobile-post-creation` § "Successful post returns to Home and refreshes the Nearby and Global feeds"), and the ViewModel SHALL re-fetch page 1 once through `reload()`, with the same retained-outcome / `isRefreshing` discipline as pull-to-refresh. The ViewModel records the first key it observes without fetching. The ViewModel is cleared only when `HomeRoute` is popped. A coordinate-acquisition failure SHALL continue to map to the existing retryable `NearbyTimelineOutcome.NetworkError` (no new outcome member).

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a `commonTest` `NearbyTimelineViewModel` over a `FakeNearbyTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: VM exposes one uiState StateFlow via stateIn delegating to the pure projection

- **WHEN** inspecting `NearbyTimelineViewModel`
- **THEN** it exposes a single `uiState: StateFlow<NearbyTimelineUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyTimelineUiState.Loading)` AND for the held `(outcome, isInitialLoad)` its value equals `nearbyTimelineUiState(outcome, isInitialLoad)` (the pure function is reused, not reimplemented) AND the initial-load flag is NOT exposed as a separate public flow

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, with uiState staying Content

- **GIVEN** a `NearbyTimelineViewModel` that has loaded a `Loaded` outcome (so `uiState.value` is `Content`) AND a `backgroundScope` collector on `uiState`
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `uiState.value` stays `Content` (it does NOT revert to `Loading`) AND the previously exposed `Loaded` outcome is retained (not nulled); on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: uiState retains the resolved state across a fresh collector (configuration-change proxy)

- **GIVEN** a `NearbyTimelineViewModel` whose load resolved to a `Loaded` outcome so `uiState.value` is `Content`
- **WHEN** the screen composition is recreated (the configuration-change case) and a fresh `backgroundScope` collector re-collects the same entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` is still `Content` (the outcome was retained by the entry-scoped ViewModel, not reset to `Loading`)

#### Scenario: NearbyFeed observes the entry-scoped ViewModel's single uiState, not a composition-local projection

- **WHEN** inspecting `NearbyFeed` in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the feed collects the ViewModel's single `uiState` (plus the separate `isRefreshing` flag) via `collectAsStateWithLifecycle()` AND does NOT recompute `nearbyTimelineUiState(...)` in the composable over separately-collected `outcome` / `isInitialLoad` flows

#### Scenario: Swiping away and returning to Nearby does not re-fetch

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the tab host composed with Nearby selected (one Nearby fetch having occurred)
- **WHEN** the test swipes to the Global tab and then back to the Nearby tab
- **THEN** the Nearby fetch invocation count remains 1 (the `HomeRoute`-scoped ViewModel survived the swipe — no re-fetch)

#### Scenario: A changed feed reload key re-fetches page 1 once

- **GIVEN** a `NearbyTimelineViewModel` whose initial load completed (fetch count 1) and which has observed feed reload key `k`
- **WHEN** it observes key `k + 1`
- **THEN** `loadFirstPage()` is invoked a second time (count 2) through `reload()` AND observing `k + 1` again does not fetch

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`NearbyTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `NearbyTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags on the `Loaded` outcome, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error` (the prior `else`/wildcard branch that produced `NetworkError` for an unenumerated 401 is removed). The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_request` / `location_out_of_bounds` / `radius_out_of_bounds` / `invalid_cursor` — not expected from the stub's always-valid params) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — `NetworkError` remains reserved for actual connectivity faults, distinct from the terminal-401 `SessionExpired` above.
- **Any other unenumerated non-2xx status** (e.g. an unexpected 403/404) → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix for the bug this change addresses is to branch `401` explicitly to `SessionExpired` ahead of this fallback, never to delete the fallback.

The one carve-out keyed on a parsed `error.code` is the server Premium gate. A `403` with `error.code = "radius_premium_only"` SHALL be surfaced as `NearbyFetchResult.PremiumGated` ahead of this mapping (`mobile-nearby-radius-slider` § "On-entry tier resolution and reactive 403 backstop"). Every other result SHALL be returned as `NearbyFetchResult.Loaded(<the member above>)`, so this mapping and its members are unchanged, including any other 403 → `NetworkError`.

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell
- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"` (shipped camelCase wire key), and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error
- **GIVEN** a MockEngine returning 200 with `{ posts: [], next_cursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError
- **GIVEN** a MockEngine that responds 401 to the Nearby fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT the retryable `Error` AND the `signin_error_network` (connectivity) copy is not the selected state

#### Scenario: 5xx / network-IO maps to NetworkError
- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

#### Scenario: Unexpected 400 maps to retryable Error with a logged diagnostic
- **GIVEN** a MockEngine returning HTTP 400 `{"error":{"code":"invalid_request"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is the retryable `Error` AND a diagnostic is emitted to logs (NOT a silent no-op, NOT a crash)

#### Scenario: Every fetch result maps to exactly one outcome
- **WHEN** inspecting the repository result mapping and the `NearbyTimelineOutcome` sealed type
- **THEN** each of HTTP 200, terminal 401, 400, 5xx, and network/IO failure maps to exactly one `NearbyTimelineOutcome` member (`Loaded` / `SessionExpired` / `Error` / `NetworkError`); terminal 401 maps to `SessionExpired` (navigation remains delegated to the shipped `Auth` plugin) AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback. There is NO branch emitting a generic "load failed" copy — but the `NetworkError` fallback itself is a DEFINED branch (required because the match is over an `Int`), not a generic-copy fallthrough
