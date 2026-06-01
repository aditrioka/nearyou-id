## ADDED Requirements

### Requirement: NearbyTimelineScreen renders the Nearby feed surface

The mobile app SHALL ship a Voyager `Screen` implementation `NearbyTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`) that renders the authenticated Nearby feed. The screen SHALL display: (a) a top-bar title via `stringResource(Res.string.timeline_nearby_title)` ("*Post dari lokasi ini*"); (b) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields" requirement) wrapped in a pull-to-refresh container; (c) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows the Nearby title
- **WHEN** a test composes `NearbyTimelineScreen().Content()` under `NearYouTheme` with a fake that emits a loaded list
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.timeline_nearby_title)`

#### Scenario: No hardcoded UI strings in NearbyTimelineScreen source
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: HomeScreen hosts NearbyTimelineScreen and routing is unchanged

The existing `HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL be repurposed from the wizard placeholder to a host whose `Content()` renders `NearbyTimelineScreen` as its content. `RootRouterScreen` SHALL continue to route the authenticated path to `HomeScreen` — this change MUST NOT modify the `mobile-auth-signin` § "RootRouterScreen routes based on token presence" requirement. `HomeScreen` SHALL NO LONGER render `home_placeholder_title` or `home_placeholder_version` (those strings are retained in the catalog but unreferenced by `HomeScreen`).

#### Scenario: HomeScreen renders the Nearby timeline content
- **WHEN** a test composes `HomeScreen().Content()` under `NearYouTheme` with the timeline fake emitting a loaded list
- **THEN** the rendered tree contains the `timeline_nearby_title` node (i.e., `HomeScreen` delegates to `NearbyTimelineScreen`) AND contains NO node whose text matches `stringResource(Res.string.home_placeholder_title)`

#### Scenario: RootRouterScreen still routes to HomeScreen
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/RootRouterScreen.kt`
- **THEN** the authenticated branch routes to `HomeScreen` (unchanged from `mobile-auth-signin`); this change introduces no edit to `RootRouterScreen`'s routing targets

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

### Requirement: LocationProvider stub supplies a fixed coordinate; real location is deferred

The mobile app SHALL declare a commonMain `LocationProvider` interface exposing a suspend function returning a `LatLng` (reusing `id.nearyou.distance.LatLng`). The default Koin binding SHALL be a `StubLocationProvider` returning the fixed coordinate `LatLng(-6.2, 106.8)` (Jakarta). This change MUST NOT request any runtime location permission and MUST NOT call any platform location API (`FusedLocationProviderClient`, `CLLocationManager`, etc.). The real device-location provider, runtime permission request, UU-PDP consent modal, and permission-denial fallback are deferred to a follow-up `mobile-location-permission-flow`, which will replace the Koin binding without modifying `NearbyTimelineRepository` or `NearbyTimelineScreen`.

#### Scenario: Stub returns the fixed Jakarta coordinate
- **WHEN** the default `LocationProvider` binding is resolved and its coordinate is read
- **THEN** the returned value equals `LatLng(-6.2, 106.8)`

#### Scenario: No platform location or permission API is referenced
- **WHEN** searching `mobile/app/src/commonMain`, `mobile/app/src/androidMain`, and `mobile/app/src/iosMain` for `FusedLocationProviderClient`, `CLLocationManager`, `ACCESS_FINE_LOCATION`, or a runtime location-permission request
- **THEN** no match is found (location acquisition is stubbed; the permission flow is the `mobile-location-permission-flow` follow-up)

#### Scenario: FOLLOW_UPS tracks the location-permission follow-up
- **WHEN** inspecting `FOLLOW_UPS.md` after this change is applied
- **THEN** the file contains an entry `mobile-location-permission-flow` referencing this stub as the trigger and `docs/03-UX-Design.md` § Location Permission / § Permission Denial Fallback as the spec source

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
- **Empty** (`Loaded`, empty posts, no `upsell`) → a node with `stringResource(Res.string.timeline_empty_nearby)`.
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty-area copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

#### Scenario: Loading shows the loading copy
- **WHEN** the screen is in the in-flight state
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_loading)`

#### Scenario: Empty area shows the sparse-area copy
- **WHEN** the outcome is `Loaded` with empty posts and no `upsell`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_empty_nearby)` AND does NOT contain `stringResource(Res.string.timeline_limit_hard)`

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

`NearbyTimelineApiClient`, `NearbyTimelineRepository`, `LocationProvider` (→ `StubLocationProvider`), and `SessionIdProvider` SHALL be registered in the commonMain Koin `mobileModule`. `NearbyTimelineRepository` SHALL be bound behind a `NearbyTimelineFlow` interface (`single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }`) so a `FakeNearbyTimelineFlow` can drive the screen tests, mirroring `mobile-auth-signin`'s `AuthFlow` seam.

#### Scenario: Koin registers the timeline graph behind the flow interface
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** the module declares singletons for `NearbyTimelineApiClient`, `NearbyTimelineRepository`, `LocationProvider`, and `SessionIdProvider` AND binds `single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }`

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
