# mobile-global-timeline Specification

## Purpose

The mobile Global-timeline feed surface in `:mobile:app` — the all-Indonesia chronological feed and the onboarding entry-point surface ("*Global is the entry point*", `docs/02-Product.md` § Timeline Features). `GlobalTimelineScreen` (hosted in the Global tab of `mobile-home-tab-host`) loads `GET /api/v1/timeline/global` through a status-driven `GlobalTimelineRepository` / `GlobalTimelineFlow` seam and renders read-only post cards (content, `city_name` under the author, `created_at`, read-only `liked_by_viewer` + `reply_count`) in a Material 3 pull-to-refresh `LazyColumn` under `NearYouTheme`, mapping every fetch result to exactly one of six explicit states — loading / content / empty / error / rate-limit-hard / rate-limit-soft — with no generic fallthrough. The Global feed has **no spatial filter**, so the request carries **no `lat`/`lng`/`radius_m`** and the card renders **no distance** (the response DTO has no `distanceM`). The response DTOs mirror the SHIPPED mixed-case wire (`GlobalPostDto`/`GlobalResponse` in `TimelineRoutes.kt`: `authorUserId`/`createdAt`/`nextCursor` camelCase; `city_name`/`liked_by_viewer`/`reply_count` snake) — NOT the stale snake_case spec example. PII discipline is enforced: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged. The feed is authenticated-only (guest Global is deferred upstream) and reuses the existing per-process singleton `SessionIdProvider` for the `X-Session-Id` soft-cap header. This mirrors the layering of `mobile-nearby-timeline`, reusing its proven seam.
## Requirements
### Requirement: GlobalTimelineScreen renders the Global feed surface

The mobile app SHALL ship a composable `GlobalTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`) that renders the authenticated Global feed. The screen is navigation-free (it holds no back-stack reference; it is embedded by the tab host as the Global tab's content). The screen SHALL display: (a) a top-bar title via `stringResource(Res.string.timeline_global_title)` ("*Seluruh Indonesia*"); (b) a scrollable list of read-only post cards (per the § "Post card renders only API-returned display fields, no distance" requirement) wrapped in a pull-to-refresh container; (c) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows the Global title

- **WHEN** a test composes the `GlobalTimelineScreen` composable under `NearYouTheme` with a fake that emits a loaded list
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.timeline_global_title)`

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
- **HTTP 401** → handled upstream by the shipped Ktor `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` clears the store + re-routes to `SignInScreen`). The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_cursor` — not expected on the always-valid first page) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell

- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"`, and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error

- **GIVEN** a MockEngine returning 200 with `{ posts: [], nextCursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs

#### Scenario: Every fetch result maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `GlobalTimelineOutcome` sealed type
- **THEN** each of HTTP 200, 400, 5xx, and network/IO failure maps to exactly one `GlobalTimelineOutcome` member; there is NO `else`/wildcard branch emitting a generic "load failed" copy (401 is delegated to the shipped `Auth` plugin)

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`:
- **Loading** (fetch in-flight) → a placeholder/skeleton list AND a node with `stringResource(Res.string.timeline_loading)`.
- **Content** (`Loaded` with non-empty posts) → the post-card list.
- **Empty** (`Loaded`, empty posts, no `upsell`) → the **loading-skeleton presentation** reusing `stringResource(Res.string.timeline_loading)` ("*Sedang memuat postingan…*"). The existing `timeline_loading` key already holds the exact copy `docs/03-UX-Design.md` § Empty State prescribes for the Global-empty edge case (which it frames as a loading skeleton because Global is effectively never empty), so NO new `timeline_empty_global` key is added (it would be a verbatim duplicate of `timeline_loading`). The Empty `GlobalTimelineUiState` member remains distinct from Loading at the projection level even though both render the same skeleton + copy.
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

The screen state SHALL be modeled as a Compose-free `GlobalTimelineUiState` data class (or sealed type) plus a pure projection (`globalTimelineUiState(outcome: GlobalTimelineOutcome?, inFlight: Boolean): GlobalTimelineUiState`) — mirroring `mobile-nearby-timeline`'s `NearbyTimelineUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no PII (no `author_user_id`, no coordinates).

#### Scenario: Projection maps each outcome to its state

- **WHEN** the projection is invoked for `inFlight = true`, for `Loaded(non-empty, no upsell)`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** each call returns the corresponding loading / content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

### Requirement: Repository, ApiClient wired as Koin singletons behind a testable seam, reusing SessionIdProvider

`GlobalTimelineApiClient` and `GlobalTimelineRepository` SHALL be registered in the commonMain Koin `mobileModule`. `GlobalTimelineRepository` SHALL be bound behind a `GlobalTimelineFlow` interface (`single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }`) so a `FakeGlobalTimelineFlow` can drive the screen tests, mirroring the Nearby seam. The Global graph SHALL reuse the existing `SessionIdProvider` singleton (no new session-id provider is registered).

#### Scenario: Koin registers the Global graph behind the flow interface and reuses SessionIdProvider

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `GlobalTimelineApiClient` and `GlobalTimelineRepository` AND binds `single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }` AND the Global client resolves the **existing** `SessionIdProvider` single (no second `SessionIdProvider` registration is added)

### Requirement: Global feed load state is scoped to the HomeRoute NavEntry and survives tab switch and the composer round-trip

The Global feed's first-page load state (the fetched outcome + the in-flight flag + the reload trigger) SHALL be held in a `HomeRoute`-scoped `GlobalTimelineViewModel` (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `HomeRoute` — `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction (the first time the Global tab is shown); pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` (which survives both tab switches and the composer being pushed above it on the root back stack), switching away from the Global tab and back, or opening the composer and returning, SHALL NOT re-fetch the Global feed.

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a commonTest `GlobalTimelineViewModel` over a `FakeGlobalTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: A load failure maps to the existing retryable error

- **GIVEN** a `FakeGlobalTimelineFlow` whose `loadFirstPage()` throws
- **WHEN** the `GlobalTimelineViewModel` loads
- **THEN** its exposed outcome is `GlobalTimelineOutcome.NetworkError` (the retryable state — no special outcome member for this)

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch. `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred (tracked alongside the existing `mobile-nearby-timeline-infinite-scroll` follow-up, extended to cover Global).

#### Scenario: Pull-to-refresh re-invokes the fetch

- **GIVEN** a `FakeGlobalTimelineFlow` counting fetch invocations
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change

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

