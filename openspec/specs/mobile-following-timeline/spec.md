# mobile-following-timeline Specification

## Purpose

The mobile Following-timeline feed surface in `:mobile:app` — the chronological feed of posts from users the viewer follows (`docs/02-Product.md` § Following Timeline). `FollowingTimelineScreen` (hosted in the Following tab of `mobile-home-tab-host`, replacing the retired `FollowingPlaceholderScreen`) loads `GET /api/v1/timeline/following` through a status-driven `FollowingTimelineRepository` / `FollowingTimelineFlow` seam and renders post cards (the shared `mobile-post-card`: author identity, content, `city_name` under the author, `created_at`, and the interactive `liked_by_viewer` + `reply_count` action row — inline like + reply shortcut) in a Material 3 pull-to-refresh `LazyColumn` under `NearYouTheme`, mapping every fetch result to exactly one of six explicit states — loading / content / empty / error / rate-limit-hard / rate-limit-soft — with no generic fallthrough. Following has **no spatial filter**, so the request carries **no `lat`/`lng`/`radius_m`** and the card renders **no distance** (the response DTO has no `distanceM`). The one divergence from Global is the **directive empty state**: when the caller follows nobody (or has no eligible posts), the screen shows the `timeline_following_placeholder` copy + a `cta_see_global` ("Lihat Global") control that animates the Home pager to the Global tab via a hoisted `onSeeGlobal` lambda (per `docs/03-UX-Design.md` § Empty State "Following empty → direct user to Nearby/Global"), NOT the loading skeleton Global-empty reuses. The response DTOs mirror the SHIPPED mixed-case wire (`FollowingPostDto`/`FollowingResponse` in `TimelineRoutes.kt`) — NOT a stale snake_case example. PII discipline is enforced: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged (the 400 diagnostic is status/exception-type only, load-bearing since Following's coordinates arrive in the response body). The feed reuses the existing per-process singleton `SessionIdProvider` for the `X-Session-Id` soft-cap header and the shared `InlineLikeController` + `LikeFlow` seam. This mirrors the layering of `mobile-global-timeline`, reusing its proven seam minus the spatial filter.

## Requirements

### Requirement: FollowingTimelineScreen renders the Following feed surface

The mobile app SHALL ship a composable `FollowingTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/FollowingTimelineScreen.kt`) that renders the authenticated Following feed, replacing the retired `FollowingPlaceholderScreen`. The screen is navigation-free (it holds no back-stack reference; it is embedded by the tab host as the Following pager page). The screen SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` or `TopAppBar` (the app section shell owns the single inset-owning `Scaffold` per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"). The screen SHALL display: (a) a scrollable list of post cards — the shared `mobile-post-card` composable, whose action row is interactive (reused inline like + reply shortcut); field discipline per § "Post card renders only API-returned display fields, no distance, no PII" — wrapped in a pull-to-refresh container that **fills the available space** between the tab row and the bottom navigation; (b) the loading / content / empty / error / rate-limit states per § "Screen state mapping covers loading, content, empty, error, and both rate-limit states". The screen SHALL NOT render a redundant in-screen header duplicating the selected section/tab (the Following tab label already identifies the surface). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Screen renders inset-free with no redundant header

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/FollowingTimelineScreen.kt`
- **THEN** the screen declares no `Scaffold` and no `TopAppBar` AND renders no in-screen header band duplicating the tab label

#### Scenario: The post list fills the available space

- **GIVEN** `FollowingTimelineScreen` composed under `NearYouTheme` with a fake emitting a loaded list, inside the shell's padded body
- **THEN** the pull-to-refresh list occupies the full height between the tab row and the bottom navigation (the list is `fillMaxSize` under the shell-provided padding, with no extra header band or unfilled gap)

#### Scenario: No hardcoded UI strings in FollowingTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/FollowingTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

#### Scenario: The placeholder screen is removed

- **WHEN** inspecting the mobile source tree after this change
- **THEN** `FollowingPlaceholderScreen.kt` no longer exists AND the Following pager page renders `FollowingTimelineScreen`

### Requirement: Following fetch targets the canonical endpoint with no spatial params and a session header

`FollowingTimelineApiClient` SHALL issue `GET /api/v1/timeline/following` (the canonical endpoint per `openspec/specs/following-timeline/spec.md`) with NO `lat`/`lng`/`radius_m` query parameters (Following has no spatial filter). The first-page request SHALL omit the `cursor` parameter. The request SHALL carry the `X-Session-Id` header from the existing singleton `SessionIdProvider` (reused, not a new instance; value matching `^[A-Za-z0-9-]{1,64}$`) so the backend's per-session soft-cap accounting engages. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment).

#### Scenario: First-page request shape

- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `FollowingTimelineApiClient.fetchFollowing(...)` runs for the first page
- **THEN** the captured request is `GET` with path `/api/v1/timeline/following` AND carries NO `lat`, `lng`, `radius_m`, or `cursor` query parameter

#### Scenario: X-Session-Id header reuses the shared provider

- **GIVEN** a Ktor MockEngine capturing outbound requests AND the same singleton `SessionIdProvider` used by the Nearby/Global feeds
- **WHEN** the Following fetch runs
- **THEN** the captured request carries an `X-Session-Id` header whose value matches `^[A-Za-z0-9-]{1,64}$` AND equals the id the shared `SessionIdProvider` yields (a new provider instance is NOT constructed for Following)

### Requirement: Response DTOs mirror the SHIPPED Following wire and carry no distance

`FollowingTimelineApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt` (the Following handler's `FollowingPostDto` / `FollowingResponse`) and `Upsell` — which is **mixed-case, NOT uniformly snake_case**, and which has **NO `distanceM`** (Following has no spatial filter). The mobile DTOs MUST be generated from that shipped source, NOT from any spec's snake_case JSON example. Specifically:
- Per-post bare (camelCase) wire names, no `@SerialName`: `id`, `authorUserId`, `authorUsername` (String), `authorDisplayName` (String), `content`, `latitude` (Double), `longitude` (Double), `createdAt` (String). There SHALL be NO `distanceM` field.
- Per-post `@SerialName` snake_case: `@SerialName("city_name") cityName` (String), `@SerialName("liked_by_viewer") likedByViewer` (Boolean), `@SerialName("reply_count") replyCount` (Int).
- Top-level: `posts: List<…>`, bare `nextCursor: String? = null` (camelCase on the wire), `upsell: UpsellDto? = null` (bare `soft: Boolean = false`, `hard: Boolean = false`).

The `authorUsername` / `authorDisplayName` fields are required non-null `String`s — the backend sends them on every post (NOT NULL since V2, added to this endpoint by `mobile-timeline-card-redesign`). The optional `upsell` object and `nextCursor` MUST tolerate absence/null (the shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`; the backend uses `@EncodeDefault(NEVER)`). `latitude`/`longitude` are NOT rendered as raw coordinates and, since Following has no distance, the card renders no distance string and `:shared:distance` `DistanceRenderer` is NOT invoked on this surface.

#### Scenario: Full Following post shape parses against the shipped mixed-case, distance-less wire

- **GIVEN** a MockEngine returning a 200 body whose post object uses the SHIPPED Following wire keys (`authorUserId`, `authorUsername`, `authorDisplayName`, `createdAt` camelCase; `city_name`, `liked_by_viewer`, `reply_count` snake; NO `distanceM`) plus top-level `nextCursor` and no `upsell`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed post exposes `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount`, `authorUsername`, and `authorDisplayName` AND `nextCursor` is present AND `upsell` is null (absent tolerated)

#### Scenario: snake_case-only body would fail — guards against the stale-spec assumption

- **GIVEN** a MockEngine returning a post object using snake_case `author_user_id` / `author_username` / `author_display_name` / `created_at` / top-level `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped wire names) — a test fixture MUST use the shipped mixed-case keys so this regression cannot slip in

#### Scenario: No distance field is defined or rendered

- **WHEN** inspecting the `FollowingPostDto` definition and the `FollowingTimelineScreen` card
- **THEN** `FollowingPostDto` declares no `distanceM` field AND the card invokes no `DistanceRenderer` and renders no distance string

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`FollowingTimelineRepository` SHALL map each fetch result to exactly one member of a sealed `FollowingTimelineOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(posts, nextCursor, upsell)`. Because the rate-limit hard cap is also a 200 (empty `posts` + `upsell.hard = true`), the hard/soft presentation is derived from the parsed `upsell` flags, NOT from a distinct status.
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error`. The shipped `Auth` plugin still owns the refresh attempt, and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect placeholder, never the connectivity copy. The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_cursor` — not expected on the always-valid first page) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash). The diagnostic MUST log the **status + exception-type only** (e.g. `status=400` / `cause::class.simpleName`) and MUST NOT interpolate `cause.message`, any response-body field, or a coordinate. This is load-bearing on this surface specifically: Following's `latitude`/`longitude` arrive in the **response body** (not the URL), which the shipped `LogLevel.HEADERS` already excludes — so the diagnostic sink is the one remaining place a body value could leak. Mirror the coord-safe shipped `GlobalTimelineRepository` diagnostic.
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable). A genuine transport failure (caught `IOException` / timeout / host-unreachable) keeps mapping here — distinct from the terminal-401 `SessionExpired`.
- **Any other unenumerated non-2xx status** → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. The fix is to branch `401` explicitly to `SessionExpired` ahead of this fallback.

#### Scenario: 200 maps to Loaded carrying posts, cursor, and upsell

- **GIVEN** a MockEngine returning 200 with 3 posts, top-level `nextCursor = "tok"`, and `upsell.soft = true`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 posts AND `nextCursor = "tok"` AND the parsed `upsell.soft = true`

#### Scenario: Hard-cap 200 (empty + upsell.hard) maps to Loaded, not Error

- **GIVEN** a MockEngine returning 200 with `{ posts: [], nextCursor: null, upsell: { hard: true } }`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with empty posts AND `upsell.hard = true` (the screen renders the hard-limit state; this is NOT mapped to `Error`/`NetworkError`)

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError

- **GIVEN** a MockEngine that responds 401 to the Following fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT the retryable `Error` AND the `signin_error_network` (connectivity) copy is not the selected state

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

#### Scenario: 400 invalid_cursor maps to retryable Error with a coord-safe diagnostic

- **GIVEN** a MockEngine returning HTTP 400 with `error.code = "invalid_cursor"`
- **WHEN** the repository processes the result
- **THEN** the outcome is the retryable `Error` AND a diagnostic is emitted AND the diagnostic contains the status and/or exception type only — it contains NO post-body field, NO `cause.message` interpolation, and NO coordinate value

#### Scenario: Every fetch result maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `FollowingTimelineOutcome` sealed type
- **THEN** each of HTTP 200, terminal 401, 400, 5xx, and network/IO failure maps to exactly one `FollowingTimelineOutcome` member (`Loaded` / `SessionExpired` / `Error` / `NetworkError`); terminal 401 maps to `SessionExpired` AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback. There is NO branch emitting a generic "load failed" copy — but the `NetworkError` fallback itself is a DEFINED branch (required because the match is over an `Int`), not a generic-copy fallthrough

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → the **directive** empty state: a node with `stringResource(Res.string.timeline_following_placeholder)` ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu.*") AND a control labelled `stringResource(Res.string.cta_see_global)` ("*Lihat Global*") that invokes the hoisted `onSeeGlobal` lambda (per § "The empty-state CTA switches the Home pager to the Global tab"). This is the deliberate divergence from Global-empty (which reuses the loading-skeleton copy): Following-empty is a real expected state that, per `docs/03-UX-Design.md` § Empty State, MUST direct the user to Nearby/Global. The empty state SHALL be rendered inside a scrollable so pull-to-refresh is recognized from it. NO new string key is added (both keys already exist in `:shared:resources`).
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty copy).
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND a non-blocking banner with `stringResource(Res.string.timeline_limit_soft)`.

The screen state SHALL be modeled as a Compose-free `FollowingTimelineUiState` data class (or sealed type) plus a pure projection (`followingTimelineUiState(outcome: FollowingTimelineOutcome?, isInitialLoad: Boolean): FollowingTimelineUiState`) — mirroring `mobile-global-timeline`'s projection — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL map `isInitialLoad = true` to `Loading`, and otherwise map the `outcome` to its state — so that during a refresh (`isInitialLoad = false`, a previous `Loaded` retained) it returns `Content`, NOT `Loading`. The pull-to-refresh `isRefreshing` value is carried separately (passed to `PullToRefreshBox`), NOT folded into this projection. The projection MUST carry no PII (no `author_user_id`, no coordinates).

#### Scenario: Projection maps each outcome to its state with the initial-vs-refresh distinction

- **WHEN** the projection is invoked for `isInitialLoad = true` (any outcome), for `Loaded(non-empty, no upsell)` with `isInitialLoad = false`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** the `isInitialLoad = true` call returns `Loading`; each non-initial call returns the corresponding content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Empty Following renders the directive copy and the Lihat-Global CTA, not the loading skeleton

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell` (`isInitialLoad = false`) — the `Empty` `FollowingTimelineUiState` member
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_following_placeholder)` AND a control whose text matches `stringResource(Res.string.cta_see_global)` AND renders zero post cards AND does NOT render the loading-skeleton `timeline_loading` copy

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Hard cap shows the limit copy distinct from the empty directive

- **WHEN** the outcome is `Loaded` with empty posts and `upsell.hard = true`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_limit_hard)` AND does NOT render the `timeline_following_placeholder` directive copy

### Requirement: The empty-state CTA switches the Home pager to the Global tab

`FollowingTimelineScreen` SHALL hoist an `onSeeGlobal: () -> Unit` lambda invoked by the empty-state "*Lihat Global*" (`cta_see_global`) control. The screen MUST NOT hold a reference to the pager or the back stack — the pager scroll is owned by the host (`mobile-home-tab-host` wires `onSeeGlobal` to `pagerState.animateScrollToPage(<Global page index>)`). When the feed is non-empty, no such control is rendered.

#### Scenario: Tapping "Lihat Global" invokes the hoisted callback

- **GIVEN** `FollowingTimelineScreen` composed in the `Empty` state with a recording `onSeeGlobal` callback
- **WHEN** the "*Lihat Global*" control is activated
- **THEN** `onSeeGlobal` fires exactly once AND the screen issues no back-stack push of its own

#### Scenario: The CTA is absent when the feed has content

- **GIVEN** `FollowingTimelineScreen` composed in the `Content` state
- **THEN** no `cta_see_global` control is rendered

### Requirement: Post card renders only API-returned display fields, no distance, no PII

`FollowingTimelineScreen` and its cards (the shared `mobile-post-card` composable) SHALL render only display fields returned by the API: the author **display identity** (`authorDisplayName`, the `authorUsername` handle), `content`, `city_name`, the `created_at` value, and the `liked_by_viewer` + `reply_count` engagement state (interactive — the like affordance and reply shortcut). No distance is rendered (Following has no spatial filter — the shared card receives `distanceM = null` on this surface). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging — the inline-like path inherits the same discipline). An empty `city_name = ""` SHALL render without the city label (no crash, no literal `""`).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree while display identity is

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "dewi.kuliner"`, `authorDisplayName = "Dewi Lestari"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND NO distance string AND contains the "Dewi Lestari" display-name node and the "@dewi.kuliner" handle node

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""`
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)

### Requirement: Repository, ApiClient wired as Koin singletons behind a testable seam, reusing SessionIdProvider

`FollowingTimelineApiClient` and `FollowingTimelineRepository` SHALL be registered in the commonMain Koin `mobileModule`. `FollowingTimelineRepository` SHALL be bound behind a `FollowingTimelineFlow` interface (`single<FollowingTimelineFlow> { get<FollowingTimelineRepository>() }`) so a `FakeFollowingTimelineFlow` can drive the screen tests, mirroring the Nearby/Global seam. The Following graph SHALL reuse the existing `SessionIdProvider` singleton (no new session-id provider is registered).

#### Scenario: Koin registers the Following graph behind the flow interface and reuses SessionIdProvider

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `FollowingTimelineApiClient` and `FollowingTimelineRepository` AND binds `single<FollowingTimelineFlow> { get<FollowingTimelineRepository>() }` AND the Following client resolves the **existing** `SessionIdProvider` single (no second `SessionIdProvider` registration is added)

#### Scenario: The Following graph resolves at runtime and shares the session provider

- **GIVEN** a Koin test that loads `mobileModule`
- **WHEN** `FollowingTimelineApiClient`, `FollowingTimelineRepository`, and `FollowingTimelineFlow` are resolved
- **THEN** each resolves without error AND the `FollowingTimelineFlow` binding returns the same instance as `FollowingTimelineRepository` AND the `SessionIdProvider` resolved is the same singleton the Global graph resolves

### Requirement: Following feed load state is scoped to the HomeRoute NavEntry and survives tab switch and the composer round-trip

The Following feed's first-page load state (the fetched outcome + the **initial-load flag** + the **refreshing flag** + the reload trigger) SHALL be held in a `HomeRoute`-scoped `FollowingTimelineViewModel` (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `HomeRoute` — `mobile-app-scaffold` § "NavDisplay scopes per-entry saveable state and ViewModels via entry decorators"), NOT in composition-scoped `remember` and NOT in a per-tab NavEntry store. The first page SHALL load exactly once on the ViewModel's construction (the first time the Following tab/page is shown). The ViewModel SHALL expose two distinct booleans — `isInitialLoad` (true only until the first outcome arrives) and `isRefreshing` (true during a reload while a prior outcome is retained). On `reload()` it SHALL keep the existing outcome and set `isRefreshing = true` (so the screen keeps rendering `Content`), then swap the outcome and clear `isRefreshing` on completion. Pull-to-refresh and the error-retry control SHALL re-fetch page 1 via the ViewModel. Because the ViewModel is scoped to `HomeRoute` (which survives both feed swipes/tab switches and the composer being pushed above it), switching/swiping away from the Following feed and back, or opening the composer and returning, SHALL NOT re-fetch the Following feed.

#### Scenario: ViewModel loads once on construction and reloads on pull-to-refresh / retry

- **GIVEN** a commonTest `FollowingTimelineViewModel` over a `FakeFollowingTimelineFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once and the outcome is exposed; AND a subsequent `reload()` invokes `loadFirstPage()` a second time

#### Scenario: reload keeps the prior outcome and toggles isRefreshing, not isInitialLoad

- **GIVEN** a `FollowingTimelineViewModel` that has loaded a `Loaded` outcome (so `isInitialLoad = false`)
- **WHEN** `reload()` is invoked and is in flight
- **THEN** `isRefreshing` is `true` AND `isInitialLoad` remains `false` AND the previously exposed `Loaded` outcome is retained (not nulled) so the screen keeps rendering `Content`; on completion `isRefreshing` returns to `false` and the outcome is swapped

#### Scenario: A load failure maps to the existing retryable error

- **GIVEN** a `FakeFollowingTimelineFlow` whose `loadFirstPage()` throws
- **WHEN** the `FollowingTimelineViewModel` loads
- **THEN** its exposed outcome is `FollowingTimelineOutcome.NetworkError` (the retryable state — no special outcome member for this)

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch via the ViewModel's `reload()`. During a refresh the already-loaded content SHALL remain mounted (the scrollable is never torn down); the `PullToRefreshBox` `isRefreshing` argument SHALL reflect the **refresh-of-existing-content** state only, NOT the initial load (per `mobile-design-system` § "Canonical list loading and refresh pattern"). `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred (tracked by GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) `mobile-nearby-timeline-infinite-scroll`, extended to cover Following).

#### Scenario: Pull-to-refresh re-invokes the fetch and keeps content visible

- **GIVEN** a `FakeFollowingTimelineFlow` counting fetch invocations, the screen in the `Content` state
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page AND the existing post-card list remains rendered during the refresh (the list is not replaced by the loading skeleton)

#### Scenario: Initial load does not show the pull-to-refresh spinner

- **WHEN** the screen is in its initial-load state
- **THEN** the `PullToRefreshBox` `isRefreshing` argument is `false` (only the skeleton/initial indicator shows) — exactly one progress indicator total

#### Scenario: Pull-to-refresh works from the empty / error state

- **GIVEN** the screen in the empty or error state (a non-`Content` post-load state) with a counting `FakeFollowingTimelineFlow`
- **WHEN** the pull-to-refresh gesture is performed
- **THEN** the reload fetch is invoked (the empty/error state is rendered inside a scrollable so the gesture is recognized) AND the state remains that same non-`Content` state during the refresh

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change AND GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (label `follow-up`) tracks `mobile-nearby-timeline-infinite-scroll` (extended to cover Following)

### Requirement: Following post card opens post detail via a hoisted onOpenPost lambda

The Following post card (the shared `mobile-post-card` composable) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, and `distanceM = null` since Following has no spatial filter) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `FollowingTimelineScreen` SHALL remain navigation-free. The card's action row is wired on this surface: the like affordance routes to the shared inline-like path (§ "Inline like on Following cards reuses the shared controller and like seam") and the reply affordance invokes a hoisted `onOpenPostReply(...)` lambda carrying the SAME non-PII display fields with `distanceM = null` (wired by `mobile-home-tab-host` to push `PostDetailRoute` with `focusReplyComposer = true`); the whole-card `onOpenPost` keeps pushing with the default `focusReplyComposer = false`. NO distance is rendered or passed; the author identity is NOT a separate tap target (per `mobile-post-card` § "Whole-card tap opens the detail and identity is not separately tappable").

#### Scenario: Tapping a Following card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Following feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped (outside the action row)
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` with `distanceM = null` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: Tapping the reply affordance on a Following card invokes the reply-shortcut callback

- **GIVEN** the Following feed composed with a loaded post and recording `onOpenPost` + `onOpenPostReply` callbacks
- **WHEN** the card's reply affordance is tapped
- **THEN** `onOpenPostReply` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` with `distanceM = null` (and no `latitude`/`longitude`, no author UUID) AND `onOpenPost` does NOT fire

#### Scenario: FollowingTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/FollowingTimelineScreen.kt`
- **THEN** the card-tap and reply-shortcut are delivered via the hoisted `onOpenPost` / `onOpenPostReply` lambdas only; the screen holds no back-stack reference and performs no back-stack push of its own

### Requirement: Terminal 401 renders a neutral session-expired redirect state, not the connectivity error

When the fetch outcome is `SessionExpired` (terminal 401), `FollowingTimelineScreen` SHALL render a neutral redirect placeholder — a short notice via `stringResource(Res.string.timeline_session_redirect)` ("*Mengalihkan ke halaman masuk…*") with **no** retry control — and SHALL NOT render `stringResource(Res.string.signin_error_network)` nor any "Coba lagi" retry. The connectivity-error state (`signin_error_network` + `cta_retry`) remains reserved exclusively for the `NetworkError` outcome (genuine transport failure). This is the in-screen complement to the reliable `SignInScreen` re-route (`mobile-auth-signin`).

#### Scenario: SessionExpired renders the redirect placeholder, not the connectivity error

- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control

#### Scenario: NetworkError still shows the connectivity copy and retry

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`

### Requirement: Inline like on Following cards reuses the shared controller and like seam

Activating a Following card's like affordance SHALL run the SAME inline-like lifecycle as Nearby/Global — the shared, Compose-free commonMain inline-like controller (`ui/timeline/InlineLikeController`, per `mobile-nearby-timeline` § "Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam") driving the `LikeFlow` Koin singleton (the existing `PostDetailRepository`). `FollowingTimelineViewModel` SHALL delegate to that controller; this surface MUST NOT introduce its own copy of the optimistic/revert/in-flight/cap lifecycle, a second like ApiClient/repository, or a duplicate status→`LikeOutcome` mapping. Behavior on this surface is identical: optimistic flip of the tapped post's `likedByViewer` inside the retained `Loaded` outcome; per-post in-flight guard (re-taps ignored while in flight); `Liked`/`Unliked` → state stands; `RateLimited(retryAfterSeconds)` → revert + set the one-shot cap-dialog state (nullable state cleared via an `onLikeCapDialogDismissed()`-style callback per docs/11 § 2.2 — no `Channel`/`SharedFlow`); `PostGone` → revert + trigger the existing `reload()`; `NetworkError` → revert with NO error surface in v1 (the same spec-recorded deferral as Nearby/Global).

#### Scenario: Following like tap optimistically flips through the shared seam

- **GIVEN** the Following feed in the `Content` state with a post whose `likedByViewer = false` AND a fake `LikeFlow` returning `LikeOutcome.Liked`
- **WHEN** the post's like affordance is activated
- **THEN** the card reflects the liked treatment immediately AND `toggleLike` was invoked exactly once with (that post's id, `currentlyLiked = false`)

#### Scenario: RateLimited on Following reverts and raises the same one-shot cap state

- **GIVEN** a fake `LikeFlow` returning `LikeOutcome.RateLimited(retryAfterSeconds = 1140)`
- **WHEN** a not-liked Following post's like affordance is activated
- **THEN** the flip is reverted AND the Following surface's cap-dialog state carries `1140` AND the dismiss callback clears it to null

#### Scenario: PostGone and NetworkError mirror the Nearby/Global handling

- **WHEN** the toggle outcome is `PostGone`, and separately `NetworkError`, on a Following post
- **THEN** the `PostGone` case reverts the flip AND re-invokes the Following `loadFirstPage()` (reload), AND the `NetworkError` case reverts the flip with no error node, dialog, or banner added (the declared v1 posture)

#### Scenario: Following delegates to the shared controller — no per-feed duplicate

- **WHEN** inspecting `FollowingTimelineViewModel` and the inline-like controller
- **THEN** `FollowingTimelineViewModel` delegates the like lifecycle to the SAME shared controller class `NearbyTimelineViewModel` / `GlobalTimelineViewModel` use AND no Following-specific copy of the optimistic/revert/in-flight/cap logic and no second like client/repository exists

### Requirement: A rate-limited inline like opens the Free like-cap dialog on the Following surface

While the Following inline-like cap state is set, the Following surface SHALL render the shared `mobile-cap-upsell-dialog` component with the like body copy — `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the live countdown derived from the carried `retryAfterSeconds`. Dismissing (the "Tutup" button, the scrim, or back) SHALL clear the one-shot state; the dialog SHALL NOT re-show until a new `RateLimited` like sets it again.

#### Scenario: A 429 like on Following shows the dialog with the verbatim body copy

- **GIVEN** the Following cap-dialog state is set with a `retryAfterSeconds` value
- **WHEN** the Following surface renders
- **THEN** the cap-upsell dialog is visible AND contains a node whose text matches `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the countdown string

#### Scenario: Dismiss clears the Following one-shot state

- **WHEN** the "Tutup" control is activated
- **THEN** the dialog is gone AND the Following cap state is null AND recomposition does not re-show it

### Requirement: Test coverage for the screen, projection, and networking

The change SHALL ship: (1) a Robolectric `FollowingTimelineScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the initial render plus each of the six visual states (including the directive empty state with the "*Lihat Global*" CTA) via a `FakeFollowingTimelineFlow`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention); (2) a commonTest `FollowingTimelineUiStateTest` for the pure outcome→state projection (including the Following-specific directive empty state); (3) MockEngine-backed `FollowingTimelineApiClient` / `FollowingTimelineRepository` tests verifying the endpoint path (no spatial params), mixed-case + distance-less wire parsing (fixtures use the shipped keys, plus the snake_case-only negative regression guard and a "no distanceM" assertion), the reused `X-Session-Id` header, `upsell` parsing, and the status→outcome mapping; (4) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest` / the Global iOS flow test) exercising the Following feed on the simulator, with Kotlin/Native-legal test function names; (5) a commonTest `FollowingTimelineViewModelTest` covering load-once-on-construction, `reload()` toggling `isRefreshing` (not `isInitialLoad`) while retaining the prior outcome, load-failure → `NetworkError`, and the shared-controller inline-like delegation (optimistic flip / `RateLimited` revert + cap state / `PostGone` revert + reload / `NetworkError` silent revert); (6) a `FollowingTimelineKoinResolutionTest` (parity with the shipped `GlobalTimelineKoinResolutionTest`) verifying the graph resolves at runtime and reuses the shared `SessionIdProvider`.

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `FollowingTimelineScreenTest`, `FollowingTimelineUiStateTest`, `FollowingTimelineViewModelTest`, the `FollowingTimelineApiClient`/`Repository` MockEngine tests, and `FollowingTimelineKoinResolutionTest` are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant exclude block lists `**/FollowingTimelineScreenTest*` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

#### Scenario: iOS flow test exists with Kotlin/Native-legal names

- **WHEN** running `./gradlew :mobile:app:iosSimulatorArm64Test`
- **THEN** a Following-feed iOS flow test is discovered (mirroring `NearbyTimelineFlowIosTest`) with K/N-legal test function names AND it exercises the Following feed load path
