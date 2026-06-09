# mobile-notifications-list Specification

## Purpose
The mobile in-app notifications surface — the Notifikasi bottom-nav section's `NotificationsScreen` and its supporting seam (`NotificationsApiClient` / `NotificationsRepository` behind a `NotificationsFlow` interface / a shell-`NavEntry`-scoped `NotificationsViewModel` / a Compose-free `NotificationsUiState` projection) over the **shipped** `/api/v1/notifications` read API. It renders the authenticated caller's notification feed (loading / content / empty / error states; type-keyed generic-actor Bahasa Indonesia copy + `body_data` excerpts; the `actor_user_id` / `target_id` UUID is never rendered or logged), with optimistic mark-read / mark-all-read (per-id revert on transport failure) and pull-to-refresh, parsing the SHIPPED mixed-case wire (opaque base64url `next_cursor`, non-null `body_data`, `{count}`, `{marked_read}`, `204`/`404 not_found`) — guarded by a negative-regression test against the stale `in-app-notifications` spec. Deep-link tap-through, actor-username rendering, infinite scroll, and live unread-badge updates are explicitly deferred (each captured here as a negative-guard requirement + a deferred-work GitHub issue (label `follow-up`)).

## Requirements
### Requirement: NotificationsScreen renders the in-app notifications surface

The mobile app SHALL ship a composable `NotificationsScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt`) that renders the authenticated caller's notification feed. The screen is **navigation-free** — it holds no back-stack reference and is embedded by the shell as the Notifikasi section's content (mirroring how `GlobalTimelineScreen` is embedded by the Home tab host); it therefore has NO back affordance (the user leaves via the bottom-nav sections). The screen SHALL display: (a) a top-bar title via `stringResource(Res.string.notifications_title)` ("*Notifikasi*"); (b) a scrollable list of notification rows (per the § "Notification row renders type-keyed copy" requirement) wrapped in a pull-to-refresh container; (c) the loading / content / empty / error states per the § "Screen state mapping" requirement; (d) a "mark all read" action per the § "Mark-all-read action" requirement. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows the notifications title

- **WHEN** a test composes `NotificationsScreen` under `NearYouTheme` with a `FakeNotificationsFlow` emitting a loaded list
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.notifications_title)`

#### Scenario: No hardcoded UI strings in NotificationsScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / label call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Notifications fetch targets the canonical endpoint with Bearer auth and an opaque cursor

`NotificationsApiClient` SHALL issue `GET /api/v1/notifications` (the canonical endpoint; the path source of truth is the SHIPPED `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt`). The first-page request SHALL omit the `cursor` parameter and MAY set `limit`. When a subsequent page is requested, the `cursor` query parameter SHALL be the **opaque `next_cursor` token** returned by the prior response, passed back **verbatim** (the client MUST NOT parse, reformat, or treat it as a timestamp). The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment). The list `unread` filter, when used, SHALL be the query parameter `unread=true` (NOT `unread_only`).

#### Scenario: First-page request shape

- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `NotificationsApiClient.fetch(...)` runs for the first page
- **THEN** the captured request is `GET` with path `/api/v1/notifications` AND carries NO `cursor` query parameter

#### Scenario: Opaque cursor is passed back verbatim

- **GIVEN** a prior response returned `next_cursor = "eyJjIjoi..."` (an opaque base64url token)
- **WHEN** the client requests the next page with that cursor
- **THEN** the captured request carries `cursor=eyJjIjoi...` byte-for-byte (no decoding, no reformatting, no timestamp parsing)

### Requirement: Response DTOs mirror the SHIPPED notifications wire

`NotificationsApiClient` SHALL define `@Serializable` response DTOs whose wire field names match the **shipped** backend serialization in `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` (`NotificationListResponse` / `NotificationDto`) — which is **mixed-case, NOT uniformly snake_case** — and which the stale `in-app-notifications` spec prose does NOT accurately describe. The mobile DTOs MUST be generated from that shipped source. Specifically:
- `NotificationDto`: bare (camelCase, no `@SerialName`) `id` (String), `type` (String); `@SerialName` snake_case `actor_user_id` (String?, nullable), `target_type` (String?, nullable), `target_id` (String?, nullable), `body_data` (a non-null JSON object — the backend defaults absent/invalid to `{}`; the DTO field is non-nullable), `created_at` (String), `read_at` (String?, nullable).
- `NotificationListResponse`: `items: List<NotificationDto>`, `@SerialName("next_cursor") nextCursor: String? = null` where `next_cursor` is an **opaque base64url token** (NOT an ISO timestamp).
- The unread-count response DTO SHALL parse `{ "count": <Long> }` (NOT `unread_count`); the read-all response DTO SHALL parse `{ "marked_read": <Int> }` (NOT `marked`).

The shared `Json` (`ignoreUnknownKeys = true`, `explicitNulls = false`) SHALL be reused; `next_cursor` and the nullable fields MUST tolerate absence.

#### Scenario: Full notification shape parses against the shipped mixed-case wire

- **GIVEN** a MockEngine returning a 200 body whose item uses the SHIPPED wire keys (`id`, `type` bare; `actor_user_id`, `target_type`, `target_id`, `body_data`, `created_at`, `read_at` snake) plus top-level `next_cursor`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the parsed item exposes `type`, `actorUserId`, `bodyData`, `createdAt`, and `readAt` AND `nextCursor` is present

#### Scenario: Stale-spec field names would NOT populate — regression guard

- **GIVEN** a MockEngine returning an unread-count body `{ "unread_count": 3 }` and a read-all body `{ "marked": 5 }` (the stale-spec shapes, NOT the shipped wire)
- **THEN** the parsed unread-count DTO's `count` is absent/default (the `unread_count` key does not populate it) AND the parsed read-all DTO's `marked_read` is absent/default — a fixture MUST use the shipped keys (`count`, `marked_read`) so this regression cannot slip in

#### Scenario: body_data is non-null and absent next_cursor tolerated

- **GIVEN** a MockEngine returning an item with `body_data` present as `{}` and a top-level body with no `next_cursor`
- **THEN** parsing succeeds AND `bodyData` is a non-null empty JSON object AND `nextCursor` is null

### Requirement: Notification row renders type-keyed generic-actor copy, with no PII, tolerating every enum value

Each notification row SHALL render type-keyed Bahasa Indonesia copy via `stringResource`, derived from `NotificationDto.type` plus `body_data` excerpts (e.g. `post_excerpt`, `reply_excerpt`, `preview`). Because the shipped list endpoint returns only `actor_user_id` (a UUID) and NO actor username, rows SHALL render a **generic actor** ("Seseorang …") and SHALL NEVER render the raw `actor_user_id` or `target_id` UUID in any UI node, nor log it. (This includes `chat_message`, which `docs/03-UX-Design.md` renders as "Pesan baru dari {username}" — v1 drops to generic copy WITHOUT the `{username}`, e.g. "Pesan baru", pending the deferred actor-username enrichment. The `notif_chat_message` string is type-distinct but carries no actor handle.) The row SHALL map at minimum `post_liked`, `post_replied`, `followed`, `post_auto_hidden`, and `chat_message` to distinct copy, and SHALL render a safe generic fallback (e.g. "Notifikasi baru") for the remaining reserved `type` values AND for any unknown/future `type` value (no crash). A row's read/unread state (derived from `read_at`) SHALL be visually distinct. A row whose `body_data` is missing its expected excerpt key SHALL render the base type copy without crashing.

#### Scenario: actor_user_id and target_id UUIDs are not in the rendered tree

- **GIVEN** a loaded `post_liked` row with `actor_user_id = "11111111-1111-1111-1111-111111111111"`, `target_id = "22222222-2222-2222-2222-222222222222"`, and `body_data = {"post_excerpt":"halo dunia"}`
- **WHEN** the row is rendered
- **THEN** the rendered tree contains NO node whose text contains either UUID AND contains the generic `post_liked` copy AND the excerpt "halo dunia"

#### Scenario: Unknown type renders the generic fallback without crashing

- **WHEN** a row has `type = "some_future_type"` not in the known set
- **THEN** the row renders the generic fallback copy (`stringResource`) AND no exception is thrown

#### Scenario: Missing body_data excerpt renders base copy without crashing

- **WHEN** a `post_liked` row has `body_data = {}` (no `post_excerpt` key)
- **THEN** the row renders the base `post_liked` copy AND no exception is thrown

#### Scenario: Read vs unread rows are visually distinct

- **GIVEN** one row with `read_at = null` and one with `read_at` set
- **THEN** the two rows render with a distinguishable read/unread treatment (e.g. an unread indicator present on the first and absent on the second)

### Requirement: Fetch outcome mapping is HTTP-status-driven with no generic fallthrough

`NotificationsRepository` SHALL map each fetch result to exactly one member of a sealed `NotificationsOutcome`, keyed on the HTTP **status code** and transport-failure type (NOT on a parsed `error.code`), with no generic "load failed" fallthrough:
- **HTTP 200** → `Loaded(items, nextCursor)`.
- **HTTP 401** → handled upstream by the shipped Ktor `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` clears the store + re-routes to `SignInScreen`). The repository MUST NOT reimplement 401 refresh/retry.
- **HTTP 400** (`invalid_cursor` — not expected on the always-valid first page) → a retryable `Error` outcome with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash). The diagnostic MUST carry only the HTTP status / outcome type — NEVER `actor_user_id`, `target_id`, `body_data`, the response body, or any token.
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).

#### Scenario: 200 maps to Loaded carrying items and cursor

- **GIVEN** a MockEngine returning 200 with 3 items and top-level `next_cursor = "tok"`
- **WHEN** the repository processes the response
- **THEN** the outcome is `Loaded` with 3 items AND `nextCursor = "tok"`

#### Scenario: 5xx / network-IO maps to NetworkError

- **GIVEN** a MockEngine returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs

#### Scenario: Error diagnostics carry no PII or body

- **WHEN** the repository emits a diagnostic for an HTTP 400 (or any non-200) outcome
- **THEN** the logged diagnostic contains only the HTTP status and/or the `NotificationsOutcome` type AND contains NO `actor_user_id` / `target_id` / `body_data` / raw response body / token (mirroring the shipped `GlobalTimelineRepository` log discipline)

#### Scenario: Every fetch result maps to exactly one outcome

- **WHEN** inspecting the repository result mapping and the `NotificationsOutcome` sealed type
- **THEN** each of HTTP 200, 400, 5xx, and network/IO failure maps to exactly one `NotificationsOutcome` member; there is NO `else`/wildcard branch emitting a generic "load failed" copy (401 is delegated to the shipped `Auth` plugin)

### Requirement: Screen state mapping covers loading, content, empty, and error

The screen SHALL render one of four visual states, all copy via `stringResource`:
- **Loading** (fetch in-flight) → a placeholder/skeleton AND a node with `stringResource(Res.string.notifications_loading)`.
- **Content** (`Loaded` with non-empty items) → the notification-row list.
- **Empty** (`Loaded`, empty items) → a node with `stringResource(Res.string.notifications_empty)` ("*Belum ada notifikasi*").
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.

The screen state SHALL be modeled as a Compose-free `NotificationsUiState` (data class or sealed type) plus a pure projection `notificationsUiState(outcome: NotificationsOutcome?, inFlight: Boolean): NotificationsUiState` — mirroring `mobile-global-timeline`'s `globalTimelineUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no PII (no `actor_user_id`/`target_id` UUIDs). There are NO rate-limit states (the notifications read endpoint carries no per-endpoint rate limit / `upsell` on the wire).

#### Scenario: Projection maps each outcome to its state

- **WHEN** the projection is invoked for `inFlight = true`, for `Loaded(non-empty)`, for `Loaded(empty)`, and for `NetworkError`
- **THEN** each call returns the corresponding loading / content / empty / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

### Requirement: Tapping a row marks it read; deep-link navigation is deferred

Tapping a notification row SHALL issue `PATCH /api/v1/notifications/{id}/read` and optimistically flip that row to the read state. A `204 No Content` response SHALL confirm success; a `404` (code `not_found` — already-read, not-owned, or non-existent) SHALL be treated as a no-op (the row remains/flips to read; no error surfaced). On any OTHER failure (5xx / network-IO), the optimistic flip SHALL be reverted to unread and NO blocking error surfaced (the next refresh reconciles). This change SHALL NOT navigate to the notification's target post/reply/profile on tap — those destination screens do not exist yet (blocked on the in-flight `mobile-post-detail` screen AND a backend `GET /api/v1/posts/{id}` by-id endpoint). This deferral is captured as a negative-guard so the follow-up `mobile-notifications-deep-link-targets` has a requirement to MODIFY.

#### Scenario: Tapping an unread row marks it read

- **GIVEN** a `FakeNotificationsFlow`/MockEngine where `PATCH /api/v1/notifications/{id}/read` returns 204 AND a rendered unread row
- **WHEN** the row is tapped
- **THEN** a `PATCH /api/v1/notifications/{id}/read` request is issued for that row's `id` AND the row renders as read

#### Scenario: 404 on mark-read is a silent no-op

- **GIVEN** `PATCH /api/v1/notifications/{id}/read` returns `404 { "error": { "code": "not_found" } }`
- **WHEN** the row is tapped
- **THEN** no blocking error is surfaced AND the row renders as read (idempotent-looking)

#### Scenario: Transport failure reverts the optimistic flip

- **GIVEN** `PATCH /api/v1/notifications/{id}/read` returns HTTP 500 (or throws `IOException`) AND a rendered unread row
- **WHEN** the row is tapped (optimistically flipped to read)
- **THEN** the row reverts to the unread state AND no blocking error is surfaced

#### Scenario: Tapping a row wires no navigation to a post/reply/profile route (deferred)

- **WHEN** inspecting `NotificationsScreen` and its row tap handler
- **THEN** the tap handler issues only the mark-read call AND no navigation to a post-detail / reply / profile `NavKey` is wired from a notification row (deep-link tap-through is deferred)

### Requirement: Mark-all-read action

The screen SHALL provide a "mark all read" action (labelled `stringResource(Res.string.notifications_mark_all_read)`, "*Tandai semua dibaca*") that issues `PATCH /api/v1/notifications/read-all` and, on the `{ "marked_read": <Int> }` 200 response, flips all currently-loaded rows to read.

#### Scenario: Mark-all-read flips loaded rows to read

- **GIVEN** a rendered list with at least one unread row AND `PATCH /api/v1/notifications/read-all` returns 200 `{ "marked_read": 3 }`
- **WHEN** the mark-all-read action is activated
- **THEN** a `PATCH /api/v1/notifications/read-all` request is issued AND every loaded row renders as read

### Requirement: Pull-to-refresh re-fetches the first page; infinite scroll is deferred

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch. `next_cursor` SHALL be parsed and retained on the `Loaded` outcome, but cursor-based load-more (infinite scroll) is NOT implemented in this change and is deferred (tracked alongside the existing `mobile-nearby-timeline-infinite-scroll` follow-up, extended to cover notifications).

#### Scenario: Pull-to-refresh re-invokes the fetch

- **GIVEN** a `FakeNotificationsFlow` counting fetch invocations
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page

#### Scenario: next_cursor is parsed but no load-more is wired

- **WHEN** inspecting the repository/screen for cursor usage
- **THEN** `next_cursor` is parsed and retained on `Loaded` but is NOT consumed to issue a follow-up `cursor=`-bearing request in this change

### Requirement: ApiClient, Repository, ViewModel wired as Koin singletons behind a testable seam

`NotificationsApiClient` and `NotificationsRepository` SHALL be registered in the commonMain Koin `mobileModule`. `NotificationsRepository` SHALL be bound behind a `NotificationsFlow` interface (`single<NotificationsFlow> { get<NotificationsRepository>() }`) so a `FakeNotificationsFlow` can drive the screen tests, mirroring the Global/Nearby seam. The `NotificationsViewModel` SHALL be resolved via `viewModel { … }` scoped to the shell NavEntry (so it survives bottom-nav section switches without re-fetch, mirroring the `HomeRoute`-scoped feed ViewModels), loading the first page once on first composition of the Notifikasi section and re-fetching on pull-to-refresh / retry.

#### Scenario: Koin registers the notifications graph behind the flow interface

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `NotificationsApiClient` and `NotificationsRepository` AND binds `single<NotificationsFlow> { get<NotificationsRepository>() }`

#### Scenario: ViewModel loads once on construction and reloads on refresh/retry

- **GIVEN** a commonTest `NotificationsViewModel` over a `FakeNotificationsFlow`
- **WHEN** the ViewModel is constructed
- **THEN** `loadFirstPage()` is invoked exactly once AND a subsequent `reload()` invokes `loadFirstPage()` a second time

### Requirement: Test coverage for the screen, projection, and networking

The change SHALL ship: (1) a Robolectric `NotificationsScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the initial render plus each of the four visual states + the mark-read-on-tap (204 + 404 no-op + transport-failure revert) + mark-all-read + read/unread visual + the no-UUID-in-tree PII assertion + the unknown-`type` fallback + missing-`body_data` rendering via a `FakeNotificationsFlow`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention, since the `ui-test-manifest` host activity is debug-only); (2) a commonTest `NotificationsUiStateTest` for the pure outcome→state projection AND a `NotificationsViewModel` loads-once/reload test over `FakeNotificationsFlow`; (3) MockEngine-backed `NotificationsApiClient` / `NotificationsRepository` tests verifying the endpoint path, the opaque-cursor pass-back-verbatim, the shipped-wire parsing (with the stale-spec negative-regression guard + the no-PII-in-diagnostic assertion), and the status→outcome mapping (incl. the `204`/`404` mark-read paths); (4) an iOS flow test under `mobile/app/src/iosTest/...` (mirroring `NearbyTimelineFlowIosTest`) exercising the screen on the simulator, with Kotlin/Native-legal test function names. (The Notifikasi-section badge + section-render tests live in the `mobile-home-tab-host` shell tests.)

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `NotificationsScreenTest`, `NotificationsUiStateTest`, the `NotificationsViewModel` test, and the `NotificationsApiClient`/`Repository` MockEngine tests are discovered AND each documented state / mapping / row behavior corresponds to at least one `@Test`

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists `**/NotificationsScreenTest*` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

