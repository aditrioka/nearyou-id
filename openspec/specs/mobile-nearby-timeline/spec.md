# mobile-nearby-timeline Specification

## Purpose
The mobile Nearby-timeline surface — the first authenticated product screen in `:mobile:app`. `NearbyTimelineScreen` (hosted by the repurposed `HomeScreen`) loads `GET /api/v1/timeline/nearby` through a status-driven `NearbyTimelineRepository` / `NearbyTimelineFlow` seam and renders read-only post cards (content, `city_name`, the shared `DistanceRenderer` distance, `created_at`, read-only `liked_by_viewer` + `reply_count`) in a Material 3 pull-to-refresh `LazyColumn` under `NearYouTheme`, mapping every fetch result to exactly one of six explicit states — loading / content / empty / error / rate-limit-hard / rate-limit-soft — with no generic fallthrough. PII discipline is enforced: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged, and the response DTOs mirror the SHIPPED mixed-case wire (`authorUserId`/`distanceM`/`createdAt`/`nextCursor` camelCase; `city_name`/`liked_by_viewer`/`reply_count` snake), not the spec's stale snake_case example. Device location is stubbed behind a `LocationProvider` seam (fixed Jakarta coordinate at the Free-tier 20 km radius — real GPS/permission is a tracked follow-up), and a per-process `SessionIdProvider` supplies the `X-Session-Id` header so the backend's per-session soft-cap accounting engages. This establishes the read-only post-card + list visual pattern that later product screens inherit.
## Requirements
### Requirement: NearbyTimelineScreen renders the Nearby feed surface

The mobile app SHALL ship a composable `NearbyTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`) that renders the authenticated Nearby feed. The screen is navigation-free (it holds no back-stack reference and is embedded directly by `HomeScreen`). The screen SHALL display: (a) a top-bar title via `stringResource(Res.string.timeline_nearby_title)` ("*Post dari lokasi ini*"); (b) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields" requirement) wrapped in a pull-to-refresh container; (c) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows the Nearby title

- **WHEN** a test composes the `NearbyTimelineScreen` composable under `NearYouTheme` with a fake that emits a loaded list
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.timeline_nearby_title)`

#### Scenario: No hardcoded UI strings in NearbyTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: HomeScreen hosts NearbyTimelineScreen and routing is unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`, mapped from the `HomeRoute` `NavKey` by the `entryProvider`) SHALL be the Nearby/Following/Global **tab host** (per the `mobile-home-tab-host` capability) rather than a direct single-feed host. `NearbyTimelineScreen` SHALL be rendered as the **Nearby tab's** content within that host (Nearby is the default authenticated tab). `RootRouterScreen` SHALL continue to route the authenticated path to `HomeRoute` — the authenticated routing **target** (Home) is unchanged; only `HomeScreen`'s internal body changes from "renders `NearbyTimelineScreen` directly" to "renders the selected tab, which for Nearby is `NearbyTimelineScreen`". `HomeScreen` SHALL NOT render `home_placeholder_title` or `home_placeholder_version` (those strings are retained in the catalog but unreferenced by `HomeScreen`).

#### Scenario: HomeScreen's Nearby tab renders the Nearby timeline content

- **WHEN** a test composes the `HomeScreen` composable under `NearYouTheme` with the timeline fake emitting a loaded list (the Nearby tab is selected by default)
- **THEN** the rendered tree contains the `timeline_nearby_title` node (i.e., the Nearby tab delegates to `NearbyTimelineScreen`) AND contains NO node whose text matches `stringResource(Res.string.home_placeholder_title)`

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
- **HTTP 401** → handled upstream by the shipped Ktor `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` clears the store + re-routes to `SignInScreen`). The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_request` / `location_out_of_bounds` / `radius_out_of_bounds` / `invalid_cursor` — not expected from the stub's always-valid params) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell
- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"` (shipped camelCase wire key), and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error
- **GIVEN** a MockEngine returning 200 with `{ posts: [], next_cursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: 5xx / network-IO maps to NetworkError
- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs

#### Scenario: Unexpected 400 maps to retryable Error with a logged diagnostic
- **GIVEN** a MockEngine returning HTTP 400 `{"error":{"code":"invalid_request"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is the retryable `Error` AND a diagnostic is emitted to logs (NOT a silent no-op, NOT a crash)

#### Scenario: Every fetch result maps to exactly one outcome
- **WHEN** inspecting the repository result mapping and the `NearbyTimelineOutcome` sealed type
- **THEN** each of HTTP 200, 400, 5xx, and network/IO failure maps to exactly one `NearbyTimelineOutcome` member; there is NO `else`/wildcard branch emitting a generic "load failed" copy (401 is delegated to the shipped `Auth` plugin, not mapped here)

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

### Requirement: Pure NearbyTimelineUiState plus a unit-testable projection

The mobile app SHALL model the screen state as a Compose-free `NearbyTimelineUiState` data class (or sealed type) and a pure projection function (e.g., `nearbyTimelineUiState(outcome: NearbyTimelineOutcome?, inFlight: Boolean): NearbyTimelineUiState`) — mirroring `mobile-age-gate`'s `AgeGateUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection MUST NOT carry any PII (no `author_user_id`, no coordinates) beyond the display fields the cards render.

#### Scenario: Projection maps each outcome to its state
- **WHEN** the projection is invoked for `inFlight = true`, for `Loaded(non-empty, no upsell)`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** each call returns the corresponding loading / content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

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

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch. `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred to `mobile-nearby-timeline-infinite-scroll`.

#### Scenario: Pull-to-refresh re-invokes the fetch
- **GIVEN** a `FakeNearbyTimelineFlow` counting fetch invocations
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page

#### Scenario: next_cursor is parsed but no load-more is wired
- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change AND `FOLLOW_UPS.md` contains an entry `mobile-nearby-timeline-infinite-scroll`

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

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`) — and explicitly NOT `latitude`/`longitude`. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. The card gains NO inline like/reply control (those are deferred per `mobile-post-detail` § "Inline-card like and reply shortcuts are deferred").

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount` AND the payload contains no `latitude`/`longitude`

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap is delivered via the hoisted `onOpenPost` lambda only; the screen holds no back-stack reference and performs no back-stack push of its own

