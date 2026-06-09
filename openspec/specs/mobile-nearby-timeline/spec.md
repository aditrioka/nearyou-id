# mobile-nearby-timeline Specification

## Purpose
The mobile Nearby-timeline surface — the first authenticated product screen in `:mobile:app`. `NearbyTimelineScreen` (hosted by the repurposed `HomeScreen`) loads `GET /api/v1/timeline/nearby` through a status-driven `NearbyTimelineRepository` / `NearbyTimelineFlow` seam and renders read-only post cards (content, `city_name`, the shared `DistanceRenderer` distance, `created_at`, read-only `liked_by_viewer` + `reply_count`) in a Material 3 pull-to-refresh `LazyColumn` under `NearYouTheme`, mapping every fetch result to exactly one of six explicit states — loading / content / empty / error / rate-limit-hard / rate-limit-soft — with no generic fallthrough. PII discipline is enforced: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged, and the response DTOs mirror the SHIPPED mixed-case wire (`authorUserId`/`distanceM`/`createdAt`/`nextCursor` camelCase; `city_name`/`liked_by_viewer`/`reply_count` snake), not the spec's stale snake_case example. Device location is stubbed behind a `LocationProvider` seam (fixed Jakarta coordinate at the Free-tier 20 km radius — real GPS/permission is a tracked follow-up), and a per-process `SessionIdProvider` supplies the `X-Session-Id` header so the backend's per-session soft-cap accounting engages. This establishes the read-only post-card + list visual pattern that later product screens inherit.
## Requirements
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

### Requirement: Nearby fetch targets the canonical endpoint with fixed radius and session header

`NearbyTimelineApiClient` SHALL issue `GET /api/v1/timeline/nearby` (the canonical endpoint per `openspec/specs/nearby-timeline/spec.md`) with query parameters `lat` and `lng` from the `LocationProvider` and `radius_m` from a single named constant equal to `20000` (the Free-tier fixed 20 km radius per `docs/02-Product.md` § Nearby Timeline). The request SHALL carry the `X-Session-Id` header from `SessionIdProvider` (a value matching `^[A-Za-z0-9-]{1,64}$`). The first-page request SHALL omit the `cursor` parameter. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment).

#### Scenario: First-page request shape
- **GIVEN** a Ktor MockEngine capturing outbound requests AND a `StubLocationProvider` returning `LatLng(-6.2, 106.8)`
- **WHEN** `NearbyTimelineApiClient.fetchNearby(...)` runs for the first page
- **THEN** the captured request is `GET` with path `/api/v1/timeline/nearby` AND query `lat=-6.2`, `lng=106.8`, `radius_m=20000`, AND NO `cursor` parameter

#### Scenario: X-Session-Id header is sent and well-formed
- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** the Nearby fetch runs
- **THEN** the captured request carries an `X-Session-Id` header whose value matches the regex `^[A-Za-z0-9-]{1,64}$` (so the backend's per-session soft-cap bucket is used, not `no-session`)

### Requirement: SessionIdProvider yields a stable per-process session id

`SessionIdProvider` SHALL compute its session id EXACTLY ONCE — captured at construction into a `val`/field (e.g., `val id = Uuid.random().toString()`), NOT recomputed per access — so every read within a process returns the same id. Registering it as a Koin `single` makes the *provider* a singleton, but the value MUST be field-captured (a fresh `Uuid.random()` per call would open a new per-session Redis bucket on every request, so the backend's 50-posts/session soft cap would never engage). The id MUST match `^[A-Za-z0-9-]{1,64}$`.

#### Scenario: Two reads return the same session id
- **GIVEN** a single `SessionIdProvider` instance
- **WHEN** its session id is read twice (e.g., on two consecutive Nearby fetches in the same process)
- **THEN** both reads return the identical string (the id is captured once at construction, not regenerated per call)

### Requirement: Response DTOs mirror the SHIPPED wire casing and render distance via DistanceRenderer

`NearbyTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (`NearbyPostDto` / `NearbyResponse`) and `Upsell.kt` — which is **mixed-case, NOT uniformly snake_case**. The mobile DTOs MUST be generated from that shipped source, NOT from the `nearby-timeline` spec's snake_case JSON example (which is stale relative to the shipped code — see the casing-drift FOLLOW_UP). Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `content`, `latitude` (Double), `longitude` (Double), `distanceM` (Double), `createdAt` (String).
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null`.
- `UpsellDto`: bare `soft: Boolean = false`, `hard: Boolean = false`.

The optional `upsell` object and `nextCursor` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)` so absent → default). The user-facing distance string SHALL be produced by `DistanceRenderer.render(distanceM)` from `:shared:distance` (NOT reimplemented locally); `latitude`/`longitude` are display-only and MUST NOT be rendered as raw coordinates.

#### Scenario: Full post shape parses against the shipped mixed-case wire
- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED wire keys (`authorUserId`, `distanceM`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `distanceM`, `createdAt`, `likedByViewer`, and `replyCount` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption
- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `distance_m` / `created_at` / top-level `next_cursor` (the spec's JSON-example shape, NOT the shipped wire)
- **THEN** those four fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: Distance is rendered through the shared renderer at the card level
- **WHEN** a post card with `distanceM = 1234.5` is rendered AND a post card with `distanceM = 7600.0` is rendered
- **THEN** the rendered card tree contains a node whose text is `DistanceRenderer.render(1234.5)` = "5km" AND a node whose text is `DistanceRenderer.render(7600.0)` = "8km" respectively (asserted at the rendered-card level, NOT only via the `:shared:distance` module's own unit test — confirming the card consumes the shared renderer rather than a locally-reimplemented format)

#### Scenario: Empty city_name tolerated
- **WHEN** a post has `city_name = ""` (the backend's never-null empty-string convention)
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`NearbyTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `NearbyTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags on the `Loaded` outcome, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error` (the prior `else`/wildcard branch that produced `NetworkError` for an unenumerated 401 is removed). The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_request` / `location_out_of_bounds` / `radius_out_of_bounds` / `invalid_cursor` — not expected from the stub's always-valid params) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — `NetworkError` remains reserved for actual connectivity faults, distinct from the terminal-401 `SessionExpired` above.
- **Any other unenumerated non-2xx status** (e.g. an unexpected 403/404) → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix for the bug this change addresses is to branch `401` explicitly to `SessionExpired` ahead of this fallback, never to delete the fallback.

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

### Requirement: Repository, ApiClient, providers wired as Koin singletons behind a testable seam

`NearbyTimelineApiClient`, `NearbyTimelineRepository`, and `SessionIdProvider` SHALL be registered in the commonMain Koin `mobileModule`. The `LocationProvider` SHALL be bound to the real platform device-location provider in each `platformModule` (Android/iOS) in production — NOT to `StubLocationProvider` in `mobileModule`; `StubLocationProvider` is retained in `commonMain` as the test double (per the `mobile-location` capability). `NearbyTimelineRepository` SHALL be bound behind a `NearbyTimelineFlow` interface (`single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }`) so a `FakeNearbyTimelineFlow` can drive the screen tests, mirroring `mobile-auth-signin`'s `AuthFlow` seam.

#### Scenario: Koin registers the timeline graph behind the flow interface
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt` and each `platformModule`
- **THEN** `mobileModule` declares singletons for `NearbyTimelineApiClient`, `NearbyTimelineRepository`, and `SessionIdProvider` AND binds `single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }` AND the `LocationProvider` binding is provided by each `platformModule` (the real provider), NOT hardcoded to `StubLocationProvider` in `mobileModule`

### Requirement: No author identifier or coordinate is rendered or logged

`NearbyTimelineScreen` and its cards SHALL render only display fields returned by the API (`content`, `city_name`, the `DistanceRenderer` string, relative `created_at`, the read-only `liked_by_viewer` + `reply_count`). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree
- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` (only the `DistanceRenderer` string + `city_name` represent location)

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable the gesture is attached to is never torn down — the prior bug, where the in-flight state collapsed the list to a full-screen loader, is removed); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred to `mobile-nearby-timeline-infinite-scroll`.

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: Pull-to-refresh works from the empty / error state

- **GIVEN** the screen in the empty or error state (a non-`Content` post-load state) with a counting `FakeNearbyTimelineFlow`
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized, per `mobile-design-system` § "Canonical list loading and refresh pattern") AND the state remains that same non-`Content` state during the refresh

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change AND GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (label `follow-up`) tracks `mobile-nearby-timeline-infinite-scroll`

### Requirement: Test coverage for the screen, projection, and networking

The change SHALL ship: (1) a Robolectric `NearbyTimelineScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the initial render plus each of the six visual states via a `FakeNearbyTimelineFlow`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the established `*ScreenTest` convention); (2) a commonTest `NearbyTimelineUiStateTest` for the pure outcome→state projection; (3) MockEngine-backed `NearbyTimelineApiClient` / `NearbyTimelineRepository` tests verifying the endpoint path, mixed-case wire parsing (per § "Response DTOs mirror the SHIPPED wire casing" — fixtures use the shipped camelCase/`@SerialName` keys, plus the snake_case-only negative regression guard), the `X-Session-Id` header, `upsell` parsing, and the status→outcome mapping.

#### Scenario: Test classes exist and are discoverable
- **WHEN** running `./gradlew :mobile:app:testDebugUnitTest`
- **THEN** `NearbyTimelineScreenTest`, `NearbyTimelineUiStateTest`, and the `NearbyTimelineApiClient`/`Repository` MockEngine tests are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: Screen test is excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the `tasks.withType<Test>()` Release-variant exclude block lists `**/NearbyTimelineScreenTest*` alongside the existing `*ScreenTest` exclusions (the `ui-test-manifest` host activity is debug-only)

### Requirement: Nearby feed is gated on location permission with a denial fallback

The Nearby surface SHALL consult the `mobile-location` `LocationPermissionController` BEFORE fetching: when permission is granted it SHALL proceed to the existing `NearbyTimelineFlow.loadFirstPage()` fetch path; when permission is denied or unavailable it SHALL render a **pre-fetch** location-permission-denied state and SHALL NOT invoke the fetch. The denial state SHALL show `stringResource(Res.string.<nearby location denied>)` ("*Aktifkan lokasi untuk lihat postingan sekitar*") plus a "*Buka Pengaturan*" CTA that invokes `LocationPermissionController.openAppSettings()`. This denial state is a pre-fetch gate state, distinct from the six fetch-outcome states in the § "Screen state mapping covers loading, content, empty, error, and both rate-limit states" requirement (which is unchanged). When permission is GRANTED but a device coordinate cannot be acquired (GPS off / timeout / null fix), the surface SHALL render the **existing** retryable error state (network-error copy + retry control) and SHALL NOT introduce a new `NearbyTimelineOutcome` member — keeping `NearbyTimelineRepository`'s outcome enum unchanged.

#### Scenario: Denied permission renders the fallback and issues no fetch
- **GIVEN** a fake `LocationPermissionController` reporting `DENIED` AND a `FakeNearbyTimelineFlow` counting fetch invocations
- **WHEN** the Nearby surface is composed
- **THEN** the rendered tree contains a node whose text matches `stringResource` of the "Aktifkan lokasi…" denial copy AND a clickable "Buka Pengaturan" node AND the fetch invocation count is `0`

#### Scenario: Granted permission drives the existing fetch path
- **GIVEN** a fake `LocationPermissionController` reporting `GRANTED` AND a `FakeNearbyTimelineFlow`
- **WHEN** the Nearby surface is composed
- **THEN** `NearbyTimelineFlow.loadFirstPage()` is invoked (the existing fetch path runs) AND no denial copy is rendered

#### Scenario: Granted but no coordinate obtainable maps to the existing retryable error state
- **GIVEN** permission is `GRANTED` AND the location acquisition fails to yield a coordinate (GPS off / timeout / null fix)
- **WHEN** the Nearby surface attempts to load
- **THEN** the rendered tree shows the existing retryable error state (`stringResource(Res.string.signin_error_network)` + a `cta_retry` control) AND no new `NearbyTimelineOutcome` member is introduced (the repository's sealed outcome type is unchanged)

#### Scenario: Buka Pengaturan CTA deep-links to settings
- **GIVEN** the denial state is rendered
- **WHEN** the "Buka Pengaturan" CTA is activated
- **THEN** `LocationPermissionController.openAppSettings()` is invoked

### Requirement: Default LocationProvider binding is the real device provider; repository mapping unchanged

The default **production** `LocationProvider` Koin binding SHALL be the real platform device-location provider (per the `mobile-location` capability), NOT `StubLocationProvider`; `StubLocationProvider` SHALL be retained as the test double. The `LocationProvider` interface signature (`suspend fun current(): LatLng`) SHALL remain unchanged. `NearbyTimelineRepository`'s status-driven `NearbyTimelineOutcome` mapping (HTTP 200→`Loaded`, 400→retryable `Error`, 5xx/IO→`NetworkError`, 401 delegated to the shipped `Auth` plugin) SHALL remain byte-for-byte unchanged — location-permission denial is handled by the screen's pre-fetch gate, not by a new repository outcome.

#### Scenario: Real provider is the default production binding
- **WHEN** inspecting the production Koin modules and `commonTest`
- **THEN** the production `LocationProvider` is the real platform provider (not `StubLocationProvider`) AND `StubLocationProvider` returning `LatLng(-6.2, 106.8)` remains the test double

#### Scenario: Repository outcome mapping is unchanged
- **WHEN** comparing `NearbyTimelineRepository`'s status→`NearbyTimelineOutcome` mapping before and after this change
- **THEN** the mapping is unchanged (no new `NearbyTimelineOutcome` member is introduced for location denial; the gate lives in the screen layer)

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

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`) — and explicitly NOT `latitude`/`longitude`. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. The card gains NO inline like/reply control (those are deferred per `mobile-post-detail` § "Inline-card like and reply shortcuts are deferred").

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount` AND the payload contains no `latitude`/`longitude`

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own

### Requirement: Terminal 401 renders a neutral session-expired redirect state, not the connectivity error

When the fetch outcome is `SessionExpired` (terminal 401), `NearbyTimelineScreen` SHALL render a neutral redirect placeholder — a short notice via `stringResource` (e.g. `timeline_session_redirect` "Mengalihkan ke halaman masuk…") with **no** retry control — and SHALL NOT render `stringResource(Res.string.signin_error_network)` nor any "Coba lagi" retry. The connectivity-error state (`signin_error_network` + `cta_retry`) remains reserved exclusively for the `NetworkError` outcome (genuine transport failure). This is the in-screen complement to the reliable `SignInScreen` re-route (`mobile-auth-signin`): it ensures the sub-second window before navigation shows a correct message.

#### Scenario: SessionExpired renders the redirect placeholder, not the connectivity error
- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control

#### Scenario: NetworkError still shows the connectivity copy and retry
- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` (unchanged from the prior behavior)

### Requirement: Nearby repository diagnostic sink is wired to a real coordinate-free logger

The Koin binding for `NearbyTimelineRepository` SHALL pass a real `diagnosticLog` sink (not the no-op default) so non-user-facing diagnostics (`nearby_network_error`, the 400 `invalid_request` diagnostic) are observable. The sink SHALL remain coordinate-free and token-free by construction (it carries only pre-redacted status/message strings — no coordinate or token is passed to it), preserving the existing PII discipline and the HTTP-path `CoordinateMaskingLogger`.

#### Scenario: MobileModule wires a non-no-op diagnostic sink
- **WHEN** inspecting the `NearbyTimelineRepository` Koin registration in `MobileModule`
- **THEN** a real `diagnosticLog` argument is supplied (not omitted to the no-op default) AND the sink's call sites pass no coordinate and no token

