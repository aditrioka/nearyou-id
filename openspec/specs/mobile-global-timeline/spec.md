# mobile-global-timeline Specification

## Purpose

The mobile Global-timeline feed surface in `:mobile:app` — the all-Indonesia chronological feed and the onboarding entry-point surface ("*Global is the entry point*", `docs/02-Product.md` § Timeline Features). `GlobalTimelineScreen` (hosted in the Global tab of `mobile-home-tab-host`) loads `GET /api/v1/timeline/global` through a status-driven `GlobalTimelineRepository` / `GlobalTimelineFlow` seam and renders read-only post cards (content, `city_name` under the author, `created_at`, read-only `liked_by_viewer` + `reply_count`) in a Material 3 pull-to-refresh `LazyColumn` under `NearYouTheme`, mapping every fetch result to exactly one of six explicit states — loading / content / empty / error / rate-limit-hard / rate-limit-soft — with no generic fallthrough. The Global feed has **no spatial filter**, so the request carries **no `lat`/`lng`/`radius_m`** and the card renders **no distance** (the response DTO has no `distanceM`). The response DTOs mirror the SHIPPED mixed-case wire (`GlobalPostDto`/`GlobalResponse` in `TimelineRoutes.kt`: `authorUserId`/`createdAt`/`nextCursor` camelCase; `city_name`/`liked_by_viewer`/`reply_count` snake) — NOT the stale snake_case spec example. PII discipline is enforced: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged. The feed is authenticated-only (guest Global is deferred upstream) and reuses the existing per-process singleton `SessionIdProvider` for the `X-Session-Id` soft-cap header. This mirrors the layering of `mobile-nearby-timeline`, reusing its proven seam.
## Requirements
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

### Requirement: Global fetch targets the canonical endpoint with no spatial params and a session header

`GlobalTimelineApiClient` SHALL issue `GET /api/v1/timeline/global` (the canonical endpoint per `openspec/specs/global-timeline/spec.md`) with NO `lat`/`lng`/`radius_m` query parameters (Global has no spatial filter). The first-page request SHALL omit the `cursor` parameter. The request SHALL carry the `X-Session-Id` header from the existing singleton `SessionIdProvider` (reused, not a new instance; value matching `^[A-Za-z0-9-]{1,64}$`) so the backend's per-session soft-cap accounting engages. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment).

#### Scenario: First-page request shape

- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `GlobalTimelineApiClient.fetchGlobal(...)` runs for the first page
- **THEN** the captured request is `GET` with path `/api/v1/timeline/global` AND carries NO `lat`, `lng`, `radius_m`, or `cursor` query parameter

#### Scenario: X-Session-Id header reuses the shared provider

- **GIVEN** a Ktor MockEngine capturing outbound requests AND the same singleton `SessionIdProvider` used by the Nearby feed
- **WHEN** the Global fetch runs
- **THEN** the captured request carries an `X-Session-Id` header whose value matches `^[A-Za-z0-9-]{1,64}$` AND equals the id the shared `SessionIdProvider` yields (a new provider instance is NOT constructed for Global)

### Requirement: Response DTOs mirror the SHIPPED Global wire and carry no distance

`GlobalTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`GlobalPostDto` / `GlobalResponse`) and `Upsell` — which is **mixed-case, NOT uniformly snake_case**, and which has **NO `distanceM`** (Global has no spatial filter). The mobile DTOs MUST be generated from that shipped source, NOT from any spec's snake_case JSON example. Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `content`, `latitude` (Double), `longitude` (Double), `createdAt` (String). There SHALL be NO `distanceM` field.
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null` (bare `soft: Boolean = false`, `hard: Boolean = false`).

The optional `upsell` object and `nextCursor` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)`). `latitude`/`longitude` are NOT rendered as raw coordinates and, since Global has no distance, the card renders no distance string and `:shared:distance` `DistanceRenderer` is NOT invoked on this surface.

#### Scenario: Full Global post shape parses against the shipped mixed-case, distance-less wire

- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED Global wire keys (`authorUserId`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake; NO `distanceM`) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `createdAt`, `likedByViewer`, and `replyCount` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption

- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `created_at` / top-level `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: No distance field is defined or rendered

- **WHEN** inspecting the `GlobalPostDto` definition and `GlobalTimelineScreen` card
- **THEN** `GlobalPostDto` declares no `distanceM` field AND the card invokes no `DistanceRenderer` and renders no distance string

### Requirement: Post card renders only API-returned display fields, no distance, no PII

`GlobalTimelineScreen` and its cards SHALL render only display fields returned by the API: `content`, `city_name` (shown under the author per `docs/02-Product.md` § Global Timeline), the `created_at` value, and the read-only `liked_by_viewer` + `reply_count`. No distance is rendered (Global has no spatial filter). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging). An empty `city_name = ""` (the backend's never-null empty-string convention) SHALL render without the city label (no crash, no literal `""`).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND NO distance string

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""`
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`GlobalTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `GlobalTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error` (the prior `else`/wildcard branch that produced `NetworkError` for an unenumerated 401 is removed). The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_cursor` — not expected on the always-valid first page) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — `NetworkError` remains reserved for actual connectivity faults, distinct from the terminal-401 `SessionExpired` above.
- **Any other unenumerated non-2xx status** (e.g. an unexpected 403/404) → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix for the bug this change addresses is to branch `401` explicitly to `SessionExpired` ahead of this fallback, never to delete the fallback.

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell

- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"`, and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error

- **GIVEN** a MockEngine returning 200 with `{ posts: [], nextCursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError

- **GIVEN** a MockEngine that responds 401 to the Global fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT the retryable `Error` AND the `signin_error_network` (connectivity) copy is not the selected state

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

#### Scenario: Every fetch result maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `GlobalTimelineOutcome` sealed type
- **THEN** each of HTTP 200, terminal 401, 400, 5xx, and network/IO failure maps to exactly one `GlobalTimelineOutcome` member (`Loaded` / `SessionExpired` / `Error` / `NetworkError`); terminal 401 maps to `SessionExpired` (navigation remains delegated to the shipped `Auth` plugin) AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback. There is NO branch emitting a generic "load failed" copy — but the `NetworkError` fallback itself is a DEFINED branch (required because the match is over an `Int`), not a generic-copy fallthrough

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

#### Scenario: Empty Global renders the loading-skeleton copy

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell` (`isInitialLoad = false`) — the `Empty` `GlobalTimelineUiState` member
- **THEN** the rendered tree renders the loading-skeleton presentation with a node whose text matches `stringResource(Res.string.timeline_loading)` (reusing the existing key per the Empty-state note) AND renders zero post cards AND no new `timeline_empty_global` key is referenced

### Requirement: Repository, ApiClient wired as Koin singletons behind a testable seam, reusing SessionIdProvider

`GlobalTimelineApiClient` and `GlobalTimelineRepository` SHALL be registered in the commonMain Koin `mobileModule`. `GlobalTimelineRepository` SHALL be bound behind a `GlobalTimelineFlow` interface (`single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }`) so a `FakeGlobalTimelineFlow` can drive the screen tests, mirroring the Nearby seam. The Global graph SHALL reuse the existing `SessionIdProvider` singleton (no new session-id provider is registered).

#### Scenario: Koin registers the Global graph behind the flow interface and reuses SessionIdProvider

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `GlobalTimelineApiClient` and `GlobalTimelineRepository` AND binds `single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }` AND the Global client resolves the **existing** `SessionIdProvider` single (no second `SessionIdProvider` registration is added)

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

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable is never torn down); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred (tracked alongside the `mobile-nearby-timeline-infinite-scroll` follow-up, extended to cover Global).

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeGlobalTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: Pull-to-refresh works from the empty / error state

- **GIVEN** the screen in the empty or error state (a non-`Content` post-load state) with a counting `FakeGlobalTimelineFlow`
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized, per `mobile-design-system` § "Canonical list loading and refresh pattern") AND the state remains that same non-`Content` state during the refresh

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change AND GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (label `follow-up`) tracks `mobile-nearby-timeline-infinite-scroll` (extended to cover Global)

### Requirement: Test coverage for the screen, projection, and networking

The change SHALL ship: (1) a Robolectric `GlobalTimelineScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the initial render plus each of the six visual states via a `FakeGlobalTimelineFlow`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention); (2) a commonTest `GlobalTimelineUiStateTest` for the pure outcome→state projection; (3) MockEngine-backed `GlobalTimelineApiClient` / `GlobalTimelineRepository` tests verifying the endpoint path (no spatial params), mixed-case + distance-less wire parsing (fixtures use the shipped keys, plus the snake_case-only negative regression guard and a "no distanceM" assertion), the reused `X-Session-Id` header, `upsell` parsing, and the status→outcome mapping.

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `GlobalTimelineScreenTest`, `GlobalTimelineUiStateTest`, and the `GlobalTimelineApiClient`/`Repository` MockEngine tests are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant exclude block lists `**/GlobalTimelineScreenTest*` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Global post card opens post detail via a hoisted onOpenPost lambda

The Global post card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, and `distanceM = null` since Global has no spatial filter) — and explicitly NOT `latitude`/`longitude`. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` SHALL remain navigation-free. The card gains NO inline like/reply control and NO distance is rendered or passed (Global has no distance), consistent with `mobile-global-timeline` § "Post card renders only API-returned display fields, no distance".

#### Scenario: Tapping a Global card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Global feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount` with `distanceM = null` AND the payload contains no `latitude`/`longitude`

#### Scenario: GlobalTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own

### Requirement: Terminal 401 renders a neutral session-expired redirect state, not the connectivity error

When the fetch outcome is `SessionExpired` (terminal 401), `GlobalTimelineScreen` SHALL render a neutral redirect placeholder — a short notice via `stringResource` (e.g. `timeline_session_redirect` "Mengalihkan ke halaman masuk…") with **no** retry control — and SHALL NOT render `stringResource(Res.string.signin_error_network)` nor any "Coba lagi" retry. The connectivity-error state (`signin_error_network` + `cta_retry`) remains reserved exclusively for the `NetworkError` outcome (genuine transport failure). This is the in-screen complement to the reliable `SignInScreen` re-route (`mobile-auth-signin`).

#### Scenario: SessionExpired renders the redirect placeholder, not the connectivity error

- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control

#### Scenario: NetworkError still shows the connectivity copy and retry

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` (unchanged from the prior behavior)

