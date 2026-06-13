# mobile-search — Delta Specification

## ADDED Requirements

### Requirement: SearchRoute is a parameterless serializable NavKey pushed onto the root back stack

The change SHALL introduce a `SearchRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) that is a parameterless `@Serializable data object` (the search query is entered IN the screen, so — unlike `PostDetailRoute` — the route carries NO payload and declares no properties). It SHALL be registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold` § "Back stack uses serializable NavKey routes"). `SearchRoute` SHALL be reached by appending it to the **root** navigation back stack (above `HomeRoute`, overlaying the section `NavigationBar`) — mirroring the post-composer FAB's and `PostDetailRoute`'s root-stack push, and deliberately NOT using a per-tab `NavDisplay` back stack.

#### Scenario: SearchRoute is registered and survives a serialized back-stack round-trip

- **GIVEN** a `SearchRoute` instance encoded + decoded via the `navSavedStateConfiguration` polymorphic serializer (the iOS-safe saved-state path)
- **THEN** decoding succeeds and yields a `SearchRoute` AND `SearchRoute` appears in the polymorphic `SerializersModule` registration alongside the other `NavKey` routes

#### Scenario: SearchRoute carries no payload

- **WHEN** inspecting the `SearchRoute` declaration in `NavKeys.kt`
- **THEN** it is a parameterless `data object` (no `postId`/`query`/coordinate properties) — the search input is owned by the screen, not the route

### Requirement: SearchScreen renders the Cari surface and is navigation-free

The mobile app SHALL ship a composable `SearchScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/search/SearchScreen.kt`), mapped from the `SearchRoute` `NavKey` by the `appEntryProvider`, that renders the search surface. As a pushed full-screen route (overlaying the section `NavigationBar`, like `PostDetailScreen`), it SHALL own a minimal top bar carrying: (a) a back affordance invoking a hoisted `onBack` lambda; (b) an M3 single-line search text field with a hint via `stringResource(Res.string.search_hint)` ("Cari postingan"); (c) a clear affordance (visible while the field is non-empty) that empties the field and returns the screen to the Idle state. Below the bar, the screen SHALL render the result list / state surface filling the remaining space, mapping to exactly one `SearchUiState` per the § "Screen state mapping" requirement. `SearchScreen` SHALL be navigation-free: it holds no back-stack reference; its back affordance invokes the hoisted `onBack`, and a result tap invokes the hoisted `onOpenPost(...)` (per the § "Result tap opens PostDetailRoute" requirement). No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource`). The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: SearchScreen renders the search input and is navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/search/SearchScreen.kt`
- **THEN** the screen renders a search text field (hint via `stringResource(Res.string.search_hint)`), a back affordance bound to the hoisted `onBack`, and a clear affordance AND holds no back-stack reference (navigation is delivered via the hoisted `onBack` / `onOpenPost` lambdas only)

#### Scenario: No hardcoded UI strings in SearchScreen source

- **WHEN** inspecting `SearchScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

#### Scenario: Back affordance invokes onBack and returns to the prior surface

- **GIVEN** `SearchScreen` composed over a test root back stack (or with a recording `onBack` callback) with `SearchRoute` as the current entry
- **WHEN** the back affordance is activated
- **THEN** the `SearchRoute` entry is removed from the root back stack (`removeLastOrNull`) / the recording `onBack` fires, and the prior surface becomes current again

### Requirement: Search fetch targets the canonical endpoint with q and offset parameters

`SearchApiClient` SHALL issue `GET /api/v1/search` (the canonical endpoint per `openspec/specs/premium-search/spec.md`) with a `q` query parameter (the trimmed, NFKC-eligible query string) and an `offset` query parameter. The FIRST-page request SHALL send `offset=0` (or omit it). A load-more request SHALL send `offset=<retained next_offset>`. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment). The client MUST NOT add `lat`/`lng`/`radius_m` or any spatial parameter (search is global — no location filter, per `docs/02-Product.md` § Search).

#### Scenario: First-page request shape

- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `SearchApiClient.search(query = "jakarta", offset = 0)` runs
- **THEN** the captured request is `GET` with path `/api/v1/search` AND carries `q=jakarta` AND `offset=0` (or no `offset`) AND carries NO `lat`/`lng`/`radius_m` parameter

#### Scenario: Load-more request carries the retained offset

- **GIVEN** a MockEngine capturing outbound requests
- **WHEN** a load-more fetch runs with the retained `next_offset = 20`
- **THEN** the captured request carries `offset=20`

### Requirement: Response DTOs mirror the SHIPPED snake_case search wire

`SearchApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/search/SearchRoutes.kt` (`SearchResponse` / `SearchResultDto`), which is **snake_case** (NOT the timelines' camelCase `nextCursor`). The mobile DTOs MUST be generated from that shipped source, NOT from any spec's JSON example. Specifically:

- `SearchResponse`: bare `results: List<SearchResultDto>`, `@SerialName("next_offset") nextOffset: Int?`.
- `SearchResultDto`: `@SerialName("post_id") postId: String`, `@SerialName("author_id") authorId: String`, `@SerialName("author_username") authorUsername: String`, `@SerialName("author_display_name") authorDisplayName: String`, bare `content: String`, `@SerialName("created_at") createdAt: String`, bare `rank: Float`.

The shared `Json` already sets `ignoreUnknownKeys` + `explicitNulls = false`. The `authorId` UUID and `rank` are parsed but NEVER rendered (PII / internal-ranking discipline per the § "Search result card" requirement).

#### Scenario: Full search hit parses against the shipped snake_case wire

- **GIVEN** a MockEngine returning a 200 body whose result object uses the SHIPPED wire keys (`post_id`, `author_id`, `author_username`, `author_display_name`, `created_at` snake; bare `content`, bare `rank`) plus top-level `next_offset`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed hit exposes `postId`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, `rank` AND `nextOffset` is present

#### Scenario: camelCase-only body would fail — guards against the casing-drift assumption

- **GIVEN** a MockEngine returning a result object using camelCase `postId` / `authorUsername` / `authorDisplayName` / `createdAt` and a top-level `nextOffset` (a stale-spec / timeline-casing shape, NOT the shipped search wire)
- **THEN** those fields do NOT populate the mobile DTO (they are absent under the shipped `@SerialName` snake_case names) — a test fixture MUST use the shipped snake_case keys so this regression cannot slip in

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`SearchRepository` SHALL map each fetch result to exactly one member of a sealed `SearchOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:

- **HTTP 200** → `Results(hits, nextOffset)`.
- **HTTP 403** (`premium_required`) → a dedicated `PremiumGate` outcome (the Free-tier gate; the screen renders the upsell panel per the § "Premium gate" requirement).
- **HTTP 429** (`rate_limited`) → `RateLimited(retryAfterSeconds)` where `retryAfterSeconds` is parsed from the `Retry-After` response header (seconds). An absent, stripped, or unparseable `Retry-After` (e.g. proxy-rewritten to an HTTP-date) SHALL map to `RateLimited(0)` (the screen floors a non-positive value to one minute).
- **HTTP 503** (`search_disabled`) → a dedicated `Disabled` outcome (the kill switch).
- **HTTP 400** (`invalid_query_length` / `invalid_offset` — not expected given the client-side guard) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
- **HTTP 401** (terminal — survived the shipped Ktor `Auth` `refreshTokens` because the refresh itself failed) → a dedicated `SessionExpired` outcome. It MUST NOT map to `NetworkError` or `Error`. The shipped `Auth` plugin still owns the refresh attempt and `SessionInvalidator` still owns the re-route to `SignInScreen`; this mapping only guarantees the brief pre-re-route render is a neutral redirect, never the connectivity copy.
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).
- **Any other unenumerated non-2xx status** → the defined `NetworkError` fallback (retryable). Because the mapping is over an `Int` status, a defined fallback MUST remain — the "no generic fallthrough" rule bans a generic "load failed" *copy*, NOT a `when` `else`/fallback branch. Terminal `401` branches to `SessionExpired` ahead of this fallback.

#### Scenario: Each status maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `SearchOutcome` sealed type
- **THEN** each of HTTP 200, 403, 429, 503, 400, terminal 401, 5xx, and network/IO failure maps to exactly one `SearchOutcome` member (`Results` / `PremiumGate` / `RateLimited` / `Disabled` / `Error` / `SessionExpired` / `NetworkError`) AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback AND there is NO branch emitting a generic "load failed" copy

#### Scenario: 403 maps to PremiumGate, not Error

- **GIVEN** a MockEngine returning `403 {"error":"premium_required","upsell":true}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `PremiumGate` AND it is NOT `Error` AND NOT `NetworkError`

#### Scenario: 429 maps to RateLimited carrying the Retry-After seconds

- **GIVEN** a MockEngine returning `429 {"error":"rate_limited"}` with a `Retry-After: 1740` header
- **WHEN** the repository processes the response
- **THEN** the outcome is `RateLimited(retryAfterSeconds = 1740)`

#### Scenario: 429 with an absent/unparseable Retry-After floors to RateLimited(0)

- **GIVEN** a MockEngine returning `429 {"error":"rate_limited"}` with no `Retry-After` header (or an HTTP-date value)
- **WHEN** the repository processes the response
- **THEN** the outcome is `RateLimited(0)` (the screen later floors it to one minute) AND no crash occurs

#### Scenario: 503 maps to Disabled

- **GIVEN** a MockEngine returning `503 {"error":"search_disabled"}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Disabled`

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError

- **GIVEN** a MockEngine that responds 401 to the search fetch AND responds 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT `Error`

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs AND the outcome is NOT `SessionExpired`

### Requirement: Screen state mapping covers idle, loading, results, empty, error, gate, rate-limit, and disabled states

`SearchScreen` state SHALL be modeled as a Compose-free `SearchUiState` (data class or sealed type) plus a pure projection `searchUiState(query: String, outcome: SearchOutcome?, isLoading: Boolean, isLoadingMore: Boolean): SearchUiState` — mirroring `mobile-global-timeline`'s `globalTimelineUiState(...)` — so the mapping is deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no PII (no `author_id`, no `rank`). The screen SHALL render exactly one of these states, all copy via `stringResource`, following the `mobile-design-system` loading-state contract (never two simultaneous progress indicators):

- **Idle** (the post-trim query length is `< 2`, including the empty initial state) → a directive prompt via `stringResource(Res.string.search_idle_prompt)`; no request is issued and no result/error surface is shown.
- **Loading** (a first-page query in flight, no prior results) → a single loading indicator via `stringResource(Res.string.timeline_loading)`; the load-more affordance is NOT shown.
- **Results** (`Results` with non-empty `hits`) → the search-result-card list; a "Lihat lebih banyak" load-more control is shown when `nextOffset != null` (per the § "Pagination" requirement).
- **EmptyResults** (`Results` with empty `hits`) → a node with `stringResource(Res.string.search_empty_results)` formatted with the current query (the `docs/03-UX-Design.md:244` copy "Tidak ada hasil untuk '{query}'. Coba kata kunci lain.").
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` that re-issues the current query.
- **PremiumGate** (`PremiumGate`) → the Free-tier upsell panel (per the § "Premium gate" requirement).
- **RateLimited** (`RateLimited`) → the rate-limit modal (per the § "Rate-limit modal" requirement).
- **Disabled** (`Disabled`) → a node with `stringResource(Res.string.search_disabled)` (the kill-switch state; no retry control, since retrying cannot help while the flag is off).
- **SessionExpired** (`SessionExpired`) → a neutral redirect placeholder via `stringResource` (e.g. `timeline_session_redirect`) with NO retry control and NOT the connectivity copy (the in-screen complement to the reliable `SignInScreen` re-route).

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked for a `< 2`-char query (Idle), an in-flight first-page query (Loading), `Results(non-empty, nextOffset != null)`, `Results(non-empty, nextOffset = null)`, `Results(empty)`, `PremiumGate`, `RateLimited(...)`, `Disabled`, `NetworkError`, and `SessionExpired`
- **THEN** it returns Idle / Loading / Results(with load-more) / Results(without load-more) / EmptyResults / PremiumGate / RateLimited / Disabled / Error / SessionExpired respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: EmptyResults renders the query-formatted copy

- **WHEN** the outcome is `Results` with empty `hits` and the query is `qwerty`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.search_empty_results)` formatted with `qwerty` AND renders zero result cards

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Disabled renders the kill-switch copy with no retry and not the connectivity error

- **WHEN** the outcome is `Disabled`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.search_disabled)` AND renders no retry control AND does NOT contain `stringResource(Res.string.signin_error_network)` (the kill-switch state is distinct from the connectivity error)

#### Scenario: SessionExpired renders the neutral redirect, not the connectivity error

- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice (`stringResource(Res.string.timeline_session_redirect)`) AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control (the connectivity-error state is reserved for `NetworkError` / `Error`)

### Requirement: A client-side query guard mirrors the backend 2..100 bound and debounces requests

`SearchScreen` SHALL NOT issue a request until the query, after trimming leading/trailing Unicode whitespace, is between `2` and `100` Unicode code points (mirroring the backend `premium-search` § "Query length guard 2..100"). A below-2 query (including empty) keeps the screen in the Idle state and issues NO request. The text field SHALL cap input at `100` code points. A valid query SHALL be issued on a **500 ms** debounce after the last keystroke AND immediately on the keyboard submit action (`docs/03-UX-Design.md:242`). The trim + code-point counting SHALL be a pure commonMain helper, unit-testable without composing UI. This is a UX optimization; the backend guard remains authoritative — a `400 invalid_query_length` (should the bounds ever diverge) maps to `Error`, never a crash (per the § "Fetch outcome mapping" requirement).

#### Scenario: Below-2 query issues no request and stays Idle

- **GIVEN** `SearchScreen` over a counting `FakeSearchFlow`
- **WHEN** the query field holds `a` (post-trim length 1) or `   ` (whitespace, post-trim length 0)
- **THEN** no fetch is issued (the fake's invocation count stays 0) AND the screen renders the Idle prompt

#### Scenario: 2-char and 100-char boundaries are accepted; 101 is capped

- **WHEN** the query guard helper evaluates a 2-code-point query, a 100-code-point query, and a 101-code-point input
- **THEN** the 2- and 100-code-point queries are eligible to fetch AND the field caps the 101-code-point input at 100 code points

#### Scenario: A valid query fires on debounce and on submit

- **GIVEN** `SearchScreen` over a counting `FakeSearchFlow`
- **WHEN** the user types a valid query and pauses (500 ms) — and separately, types and presses the keyboard submit action
- **THEN** a fetch is issued in each case for the current query (the fake's invocation count increases)

### Requirement: Pagination is a "Lihat lebih banyak" load-more that appends pages

When the current `Results` outcome carries a non-null `nextOffset`, `SearchScreen` SHALL render a "Lihat lebih banyak" control via `stringResource(Res.string.search_load_more)` below the result list. Activating it SHALL issue a fetch with `offset = nextOffset`, **append** the returned hits to the retained list (NOT replace it), and update the retained `nextOffset`. A `nextOffset == null` SHALL hide the control (terminal). A returned empty page SHALL be treated as terminal (the control is hidden) even if a non-null `nextOffset` was returned — clients treat `results = []` as terminal per `premium-search` § "Pagination via OFFSET". During a load-more fetch the existing results SHALL remain rendered (the list is never torn down) with at most one in-list progress indicator; the screen state stays `Results`, NOT `Loading`.

#### Scenario: Load-more appends the next page and keeps the list mounted

- **GIVEN** `SearchScreen` in the `Results` state with a first page of 20 hits and `nextOffset = 20`, over a `FakeSearchFlow` whose load-more returns 5 more hits with `nextOffset = null`
- **WHEN** the "Lihat lebih banyak" control is activated
- **THEN** the list renders 25 hits (the 5 appended to the original 20) AND the existing 20 stayed rendered during the fetch AND the load-more control is now hidden (`nextOffset == null`)

#### Scenario: A null nextOffset hides the load-more control

- **WHEN** the `Results` outcome carries `nextOffset = null`
- **THEN** no "Lihat lebih banyak" control is rendered

### Requirement: The search result card renders only API display fields and no PII

`SearchScreen` result cards (a search-specific card composable; the shipped search wire carries NO city, distance, or like/reply state) SHALL render only: the author **display identity** (the letter avatar + `authorDisplayName` + the `@authorUsername` handle, reusing the `mobile-post-card` avatar/identity sub-treatments so they cannot drift), the post `content`, and the `created_at` date treatment (the existing `postDateLabel` ISO-date helper — true relative formatting stays deferred to the `mobile-timeline-relative-timestamp` follow-up). The card SHALL render NO engagement action row (no like/reply affordance — the wire returns no such state), NO city label, and NO distance. The `author_id` (a UUID) and the `rank` score MUST NOT be rendered in any UI node. Tokens, the `author_id`, the `rank`, and response bodies MUST NOT be logged: the shipped `HttpClientFactory` `LogLevel.HEADERS` already excludes response bodies, and this capability MUST NOT widen logging. (Precision note: `LogLevel.HEADERS` logs the request line, so the user-typed `q=<query>` term travels in the logged URL in debug builds — this is the documented `LogLevel.HEADERS` posture inherited from `mobile-global-timeline`, NOT a regression this change introduces; the query term is user-typed content, not a token/`author_id`/`rank`/coordinate, and the existing coordinate-masking is unaffected. No `q`-masking is added here.)

#### Scenario: author_id and rank are not in the rendered tree while display identity is

- **GIVEN** a search hit with `author_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "dewi.kuliner"`, `authorDisplayName = "Dewi Lestari"`, `rank = 0.83`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"0.83"` AND contains the "Dewi Lestari" display-name node and the "@dewi.kuliner" handle node AND renders no like/reply action row and no city/distance

### Requirement: A result tap opens PostDetailRoute with documented default fields

A search result card SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the hit's non-PII display fields. The lambda is a host-level callback (wired by the `appEntryProvider` call site to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `SearchScreen` SHALL remain navigation-free. Because the shipped search wire carries no `cityName`/`distanceM`/`likedByViewer`/`replyCount`, the pushed `PostDetailRoute` SHALL use documented defaults for those fields — `cityName = ""` (the empty-city convention the detail header tolerates), `distanceM = null` (no spatial origin), `likedByViewer = false`, `replyCount = 0` — together with `postId`, `content`, `createdAtIso` (the hit's `createdAt`), `authorUsername`, and `authorDisplayName` from the hit; never `latitude`/`longitude`, never the `author_id` UUID. This is an explicit, accepted v1 limitation: no per-viewer like-status endpoint and no by-id post GET exist, and `PostDetailScreen` renders its header solely from nav args, so the like toggle's initial `likedByViewer` and the header `replyCount` MAY be cosmetically stale until the detail screen's authoritative `/likes/count` + `/replies` sub-resource fetches resolve. The like endpoints are idempotent, so a stale-`false` initial state cannot corrupt server state. A `follow-up` GitHub issue SHALL track endpoint-backed enrichment of these fields.

#### Scenario: Tapping a result pushes PostDetailRoute with the hit fields and documented defaults, no PII

- **GIVEN** the search surface composed with a loaded hit (`postId`, `content`, `createdAt`, `authorUsername`, `authorDisplayName`) and a recording `onOpenPost` callback (or the `appEntryProvider` call site over a test root back stack)
- **WHEN** the result card is tapped
- **THEN** a `PostDetailRoute` is appended to the root back stack carrying `postId`/`content`/`createdAtIso`/`authorUsername`/`authorDisplayName` from the hit AND `cityName = ""`, `distanceM = null`, `likedByViewer = false`, `replyCount = 0` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: The default-fields limitation is tracked

- **WHEN** inspecting `tasks.md` and the change's follow-up issues
- **THEN** the search-origin `PostDetailRoute` default-fields limitation is recorded with a `follow-up` GitHub issue for endpoint-backed enrichment (NOT a silent default)

### Requirement: The Premium gate renders the Free-tier upsell panel reactively on 403

While the search outcome is `PremiumGate` (the reactive `403 premium_required` gate), `SearchScreen` SHALL render a Free-tier upsell panel: an explanatory body via `stringResource` (e.g. `search_premium_gate_body`) describing that search is a Premium feature, and a primary CTA via `stringResource` (e.g. `search_premium_gate_cta`, "Aktifkan Premium"). The CTA is a v1 informational placeholder: activating it invokes a hoisted callback whose v1 host wiring performs no navigation (no paywall screen exists — Phase 4 / DESIGN-status billing); a `follow-up` GitHub issue SHALL track routing it to the paywall. The gate panel SHALL NOT issue any further search request while shown.

#### Scenario: 403 renders the upsell panel with the Premium CTA

- **GIVEN** a `FakeSearchFlow` returning `SearchOutcome.PremiumGate` for a valid query
- **WHEN** `SearchScreen` renders
- **THEN** the rendered tree contains the upsell body (`search_premium_gate_body`) AND a CTA labelled `stringResource(Res.string.search_premium_gate_cta)`

#### Scenario: The upsell CTA performs no navigation in v1

- **GIVEN** the upsell panel composed over a test root back stack (or with a recording CTA callback)
- **WHEN** the "Aktifkan Premium" CTA is activated
- **THEN** no route is appended to the back stack (the v1 placeholder is a no-op / dismiss) AND a `follow-up` issue tracks the paywall destination

### Requirement: The rate-limit state renders the docs/03 modal with a live countdown

While the search outcome is `RateLimited(retryAfterSeconds)`, `SearchScreen` SHALL render the rate-limit surface with the `docs/03-UX-Design.md:245` copy via `stringResource(Res.string.search_rate_limited)` ("Kamu sudah mencapai batas pencarian. Reset dalam %1$d menit.") formatted with a live countdown derived from `retryAfterSeconds`. (The shipped resource takes the minute count as an integer `%1$d` with the "menit" unit in the resource — reusing the `mobile-cap-upsell-dialog` `capCountdownMinutes` formatter directly — rather than a pre-formatted `%1$s` countdown string; a cosmetically-equivalent simplification.) The minute count SHALL be computed by the pure commonMain `capCountdownMinutes` formatter (minutes rounded up) and decremented via monotonic coroutine `delay` (NO wall-clock platform API). A non-positive `retryAfterSeconds` SHALL be floored to one minute (the shipped floor precedent — never a flash-clear on entry). When the countdown reaches zero the cap has reset: the countdown SHALL be replaced by a retry control (`stringResource(Res.string.search_rate_limit_reset)` + a `stringResource(Res.string.cta_retry)` button) so the user MAY re-issue the query — a deliberate user action, NOT an auto-fetch (which would silently re-consume the hourly quota).

#### Scenario: A 429 renders the rate-limit copy with the countdown

- **GIVEN** a `FakeSearchFlow` returning `SearchOutcome.RateLimited(retryAfterSeconds = 1740)` for a valid query
- **WHEN** `SearchScreen` renders
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.search_rate_limited)` formatted with the countdown ("29 menit") derived from `1740`

#### Scenario: A non-positive Retry-After floors to one minute and does not flash-clear

- **GIVEN** `SearchOutcome.RateLimited(retryAfterSeconds = 0)`
- **WHEN** the rate-limit surface renders
- **THEN** it shows the one-minute countdown ("1 menit") AND does NOT immediately clear on entry

#### Scenario: The rate-limit surface shows a retry control when the countdown reaches zero

- **GIVEN** the rate-limit surface shown with a small `retryAfterSeconds` and a test clock advancing the monotonic countdown
- **WHEN** the countdown reaches zero
- **THEN** the countdown is replaced by a retry control (`search_rate_limit_reset` copy + a `cta_retry` button) AND activating it re-issues the query (the user MAY re-issue; it is NOT an auto-fetch)

### Requirement: SearchApiClient and SearchRepository are Koin singletons behind a testable seam

`SearchApiClient` and `SearchRepository` SHALL be registered in the commonMain Koin `mobileModule`. `SearchRepository` SHALL be bound behind a `SearchFlow` interface (`single<SearchFlow> { get<SearchRepository>() }`) so a `FakeSearchFlow` can drive the screen + ViewModel tests, mirroring the timeline seams. The `SearchViewModel` SHALL be scoped to the `SearchRoute` NavEntry (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `SearchRoute`, the pushed-route precedent), holding the query, the in-flight flags, the retained outcome, and the retained `nextOffset`; it SHALL issue the search via the `SearchFlow` seam (debounced + on submit) and expose the `searchUiState(...)` projection inputs. The query/results state is owned by the ViewModel, NOT composition-scoped `remember`.

#### Scenario: Koin registers the search graph behind the flow interface

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `SearchApiClient` and `SearchRepository` AND binds `single<SearchFlow> { get<SearchRepository>() }`

#### Scenario: SearchViewModel issues the query through the SearchFlow seam

- **GIVEN** a commonTest `SearchViewModel` over a `FakeSearchFlow`
- **WHEN** a valid query is submitted and, separately, a load-more is requested
- **THEN** the ViewModel invokes `SearchFlow.search(query, offset = 0)` for the query and `SearchFlow.search(query, offset = <nextOffset>)` for the load-more, exposing the resulting outcome + retained `nextOffset`

### Requirement: Test coverage for the screen, projection, query guard, networking, and route

The change SHALL ship: (1) a Robolectric `SearchScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the search input + clear, each visual state (Idle / Loading / Results / EmptyResults / Error / PremiumGate / RateLimited / Disabled / SessionExpired) via a `FakeSearchFlow`, the "Lihat lebih banyak" append, and the result-tap `onOpenPost` payload — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention); (2) a commonTest `SearchUiStateTest` for the pure outcome→state projection; (3) commonTest for the query guard (trim, 2/100 boundaries, below-2 no-fetch) and the `SearchRoute` serialized round-trip; (4) MockEngine-backed `SearchApiClient` / `SearchRepository` tests verifying the endpoint path + `q`/`offset` params, the shipped snake_case wire parse, the camelCase negative-guard, the `next_offset` parse, the `Retry-After` parse on 429 (including the absent→`RateLimited(0)` floor), and each status→outcome mapping; (5) an `iosTest` flow test (`SearchFlowIosTest`) mirroring `NearbyTimelineFlowIosTest` / `PostDetailFlowIosTest` — exercising the search flow on the iOS/Native target via a `FakeSearchFlow` so the new `SearchRoute` + data seam compile and run on Kotlin/Native (the universal per-screen `*FlowIosTest` convention).

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `SearchScreenTest`, `SearchUiStateTest`, the query-guard + `SearchRoute` round-trip tests, and the `SearchApiClient`/`SearchRepository` MockEngine tests are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: The iOS flow test exists for the Native target

- **WHEN** inspecting `mobile/app/src/iosTest/...`
- **THEN** a `SearchFlowIosTest` exists (mirroring `NearbyTimelineFlowIosTest`) exercising the search flow over a `FakeSearchFlow` on the iOS/Native target

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant exclude block lists `**/SearchScreenTest*` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Autocomplete, proactive upsell, and paywall navigation are explicitly deferred

This change SHALL NOT implement: (a) username autocomplete / typeahead (`docs/03-UX-Design.md:241` — requires a NEW backend autocomplete endpoint that is not shipped); (b) a proactive "upsell on tap before typing" surface (`docs/03-UX-Design.md:240` — requires a client-held `subscription_status`; the reactive-on-`403` `PremiumGate` is the v1 surface); (c) routing the Premium-gate / rate-limit upsell CTA to a real paywall screen (Phase 4 / DESIGN-status billing). Each deferral SHALL be recorded as a `tasks.md` note AND a `follow-up` GitHub issue (NOT silently dropped).

#### Scenario: The deferrals are tracked, not silent

- **WHEN** inspecting `tasks.md` and the change's follow-up issues
- **THEN** the autocomplete, proactive-upsell, and paywall-navigation deferrals are each recorded with a `follow-up` GitHub issue reference AND the v1 search surface functions without them (reactive gate, submit/debounce search, informational upsell)
