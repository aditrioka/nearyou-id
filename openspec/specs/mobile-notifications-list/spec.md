# mobile-notifications-list Specification

## Purpose
The mobile in-app notifications surface — the Notifikasi bottom-nav section's `NotificationsScreen` and its supporting seam (`NotificationsApiClient` / `NotificationsRepository` behind a `NotificationsFlow` interface / a shell-`NavEntry`-scoped `NotificationsViewModel` / a Compose-free `NotificationsUiState` projection) over the **shipped** `/api/v1/notifications` read API. It renders the authenticated caller's notification feed (loading / content / empty / error states; type-keyed generic-actor Bahasa Indonesia copy + `body_data` excerpts; the `actor_user_id` / `target_id` UUID is never rendered or logged), with optimistic mark-read / mark-all-read (per-id revert on transport failure) and pull-to-refresh, parsing the SHIPPED mixed-case wire (opaque base64url `next_cursor`, non-null `body_data`, `{count}`, `{marked_read}`, `204`/`404 not_found`) — guarded by a negative-regression test against the stale `in-app-notifications` spec. Cursor load-more (infinite scroll) is wired (the list appends pages on scroll-to-end via the shared `LoadMoreController`). Deep-link tap-through, actor-username rendering, and live unread-badge updates remain explicitly deferred (each captured here as a negative-guard requirement + a deferred-work GitHub issue (label `follow-up`)).
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
- **Loading** (INITIAL load only — `isInitialLoad`, true until the first outcome arrives) → a placeholder/skeleton AND a node with `stringResource(Res.string.notifications_loading)`. A REFRESH does NOT map to Loading: the prior outcome stays mounted and only the pull-to-refresh indicator (driven by a separate `isRefreshing` flag) spins — the canonical `mobile-design-system` "list loading and refresh" split. (Amended 2026-06-10, holistic audit finding 05-#2: this requirement previously encoded the pre-split single `inFlight` flag, which tore Content down to the skeleton mid-refresh and showed two progress indicators at once.)
- **Content** (`Loaded` with non-empty items) → the notification-row list.
- **Empty** (`Loaded`, empty items) → a node with `stringResource(Res.string.notifications_empty)` ("*Belum ada notifikasi*").
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.

Every non-Content state SHALL render inside a scrollable container so the pull-to-refresh gesture remains available from Loading/Empty/Error (per `mobile-design-system` § "Pull-to-refresh is available from a non-Content state"; audit finding 05-#3).

The screen state SHALL be modeled as a Compose-free `NotificationsUiState` (data class or sealed type) plus a pure projection `notificationsUiState(outcome: NotificationsOutcome?, isInitialLoad: Boolean): NotificationsUiState` — mirroring `mobile-global-timeline`'s `globalTimelineUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no PII (no `actor_user_id`/`target_id` UUIDs). There are NO rate-limit states (the notifications read endpoint carries no per-endpoint rate limit / `upsell` on the wire).

#### Scenario: Projection maps each outcome to its state

- **WHEN** the projection is invoked for `inFlight = true`, for `Loaded(non-empty)`, for `Loaded(empty)`, and for `NetworkError`
- **THEN** each call returns the corresponding loading / content / empty / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

### Requirement: Mark-all-read action

The screen SHALL provide a "mark all read" action (labelled `stringResource(Res.string.notifications_mark_all_read)`, "*Tandai semua dibaca*") that issues `PATCH /api/v1/notifications/read-all` and, on the `{ "marked_read": <Int> }` 200 response, flips all currently-loaded rows to read.

#### Scenario: Mark-all-read flips loaded rows to read

- **GIVEN** a rendered list with at least one unread row AND `PATCH /api/v1/notifications/read-all` returns 200 `{ "marked_read": 3 }`
- **WHEN** the mark-all-read action is activated
- **THEN** a `PATCH /api/v1/notifications/read-all` request is issued AND every loaded row renders as read

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

### Requirement: Pull-to-refresh re-fetches the first page; cursor load-more is wired

The screen SHALL provide pull-to-refresh (Material 3 `PullToRefreshBox` or equivalent) that re-invokes the first-page fetch. `next_cursor` SHALL be parsed and retained on the `Loaded` outcome AND SHALL drive cursor-based load-more per the § "Notifications list wires cursor load-more" requirement (which follows `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern"). A pull-to-refresh (or retry) reload re-fetches the first page and SHALL reset paging state — any appended later pages are dropped and the end-reached flag is cleared.

#### Scenario: Pull-to-refresh re-invokes the fetch

- **GIVEN** a `FakeNotificationsFlow` counting fetch invocations
- **WHEN** the pull-to-refresh gesture is triggered after the initial load
- **THEN** the fetch is invoked again (invocation count increases) for the first page

#### Scenario: Refresh resets paging to the first page

- **GIVEN** the notifications list with a first page plus at least one appended load-more page
- **WHEN** a pull-to-refresh reload completes
- **THEN** the list shows a fresh first page (the previously appended later pages are dropped) AND the end-reached flag is cleared so load-more can run again

### Requirement: Notifications list wires cursor load-more

`NotificationsViewModel` SHALL append subsequent notification pages via the shared load-more controller following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern": scrolling near the end of the notifications `LazyColumn` (tag `notificationsList`) issues a follow-up `GET /api/v1/notifications` carrying the retained `cursor` (and the same `unread` filter the first page used), appends the page's notification rows below the existing list, advances the cursor, stops at a null cursor (end-reached), and shows the load-more footer / non-destructive retry-on-error per the canonical pattern. The in-place **mark-read** and **mark-all-read** optimistic mutations SHALL continue to operate over the GROWN list (a row from any appended page can be marked read, and mark-all-read flips every loaded row including appended ones). The unread-badge count remains the shell's separate one-shot concern (not recomputed here). The PII discipline (no `actor_user_id` / `target_id` / `body_data` rendered beyond the existing row copy; none logged) is unchanged on appended rows.

#### Scenario: Scrolling near the end issues a cursor-bearing follow-up

- **GIVEN** the notifications list loaded with a first page whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the list
- **THEN** exactly one follow-up `GET /api/v1/notifications` is issued carrying `cursor=c1`

#### Scenario: Load-more preserves the first-page unread filter

- **GIVEN** the notifications first page was fetched with a specific `unread` filter value (the default unfiltered request in the shipped UI)
- **WHEN** load-more issues the follow-up `GET /api/v1/notifications`
- **THEN** the follow-up carries the SAME `unread` filter value the first page used — a regression that drops or changes `unread` on a later page (silently mixing read rows into an unread-filtered list) is rejected by an explicit assertion

#### Scenario: The second page appends below the first and advances the cursor

- **GIVEN** a fake returning a second page of notification rows with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** load-more completes
- **THEN** the second page's rows are appended below the first page (page-1 rows retained) AND the list's current cursor is `"c2"`

#### Scenario: Mark-read works on an appended row and survives append

- **GIVEN** the notifications list with a first page and an appended second page (both holding unread rows)
- **WHEN** an unread row from the appended second page is tapped (mark-read) AND, separately, mark-all-read is invoked
- **THEN** the tapped appended row flips to read in place AND mark-all-read flips every loaded row (first- and second-page) to read AND no appended row is lost or reordered by the mutation

#### Scenario: A null cursor stops further load-more

- **GIVEN** the notifications list whose latest page returned `nextCursor = null`
- **WHEN** the user scrolls to the end again
- **THEN** no further `GET /api/v1/notifications` request is issued AND no load-more footer spinner is shown (end-reached)

#### Scenario: A load-more failure keeps the loaded rows and offers retry

- **GIVEN** the notifications list with a loaded first page AND a load-more fetch that fails (network/5xx)
- **THEN** the first-page rows remain rendered AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor

### Requirement: Tapping a row marks it read and deep-links to its target

Tapping a notification row SHALL issue `PATCH /api/v1/notifications/{id}/read` and optimistically flip that row to the read state. A `204 No Content` response SHALL confirm success; a `404` (code `not_found` — already-read, not-owned, or non-existent) SHALL be treated as a no-op (the row remains/flips to read; no error surfaced). On any OTHER failure (5xx / network-IO), the optimistic flip SHALL be reverted to unread and NO blocking error surfaced (the next refresh reconciles).

In ADDITION, tapping SHALL navigate to the row's resolved deep-link destination per the § "Notification tap resolves a deep-link destination from (type, target_type, target_id, actor)" requirement. Mark-read and navigation are INDEPENDENT: a tap ALWAYS issues the mark-read call (its success/failure handling above is unchanged), and navigation never blocks on mark-read nor mark-read on navigation. A `post`-target tap whose by-id fetch fails to resolve a visible post (the endpoint's single `404 post_not_found`) SHALL still mark the row read AND surface a non-blocking "Postingan tidak tersedia" affordance (a transient message — NOT a modal, NOT a full-screen error) with NO navigation. A row whose type has no in-app destination — the no-target informational types and the `reply`-target case (see the resolution requirement) — SHALL navigate nowhere and remain mark-read-only.

The navigation SHALL be delivered as a consumed-once signal (a nullable field on the screen's state, cleared after first delivery — NOT a `Channel`/`SharedFlow` event bus, per docs/11 § 2.2), so it does NOT re-fire on recomposition or configuration change. No `actor_user_id` / `target_id` / `conversation_id` SHALL be rendered in any UI node nor logged as part of resolving or performing the navigation (they are route payload / path params only).

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

#### Scenario: A post-target tap marks read and navigates to post detail

- **GIVEN** a rendered unread `post_liked` row with `target_type = "post"`, `target_id = "<P>"` AND a fetch seam where `GET /api/v1/posts/<P>` resolves to a visible post
- **WHEN** the row is tapped
- **THEN** the mark-read call is issued for the row AND the resolved post-detail destination (`onOpenPost`) is invoked exactly once AND no post `target_id` UUID appears in any rendered UI node

#### Scenario: A post-target whose fetch is unavailable marks read with no navigation

- **GIVEN** a rendered unread `post_liked` row whose `GET /api/v1/posts/{target_id}` returns `404 post_not_found` (or any non-200 / IO failure)
- **WHEN** the row is tapped
- **THEN** the row is marked read AND a non-blocking "Postingan tidak tersedia" affordance is shown AND NO navigation destination is invoked

### Requirement: Notification tap resolves a deep-link destination from (type, target_type, target_id, actor)

The mobile app SHALL resolve a tapped notification to a deep-link destination as a pure function of its `(type, target_type, target_id, actor_user_id, body_data)` fields, following the canonical addressing model in `docs/05-Implementation.md` § Notifications (the outer `(target_type, target_id)` pair is the deep-link address; `body_data` supplies only what that pair cannot). The resolution SHALL map:

- `target_type = "post"` (the `post_liked`, `post_replied`, and `post_auto_hidden`-on-a-post cases) → fetch the post by `target_id` and, on a visible result, the post-detail destination (`onOpenPost`).
- `followed` (`target_type` absent, `actor_user_id` present) → the actor's profile destination (`onOpenProfile(actor_user_id)`), with NO fetch (the profile screen fetches its own data).
- `chat_message` (`target_type = "message"`, `actor_user_id` present) → the chat-thread destination, addressed by `body_data.conversation_id`. Because the notifications wire carries no actor display name, the resolution SHALL fetch the partner's display identity via the SHIPPED `user-profile-read` read (`GET /api/v1/users/{actor_user_id}` — the sender of a 1:1 chat message IS the partner) and invoke `onOpenChatThread(conversation_id, partnerUsername, partnerDisplayName)`. If that profile fetch fails (`404`/IO), the resolution SHALL still invoke `onOpenChatThread(conversation_id, "", "")` — the conversation (messages) is independently valid; the thread top bar degrades to its existing blank-name placeholder rather than blocking a reachable conversation.
- `chat_message_redacted` (`target_type = "message"`, `actor_user_id` = NULL) → NO destination (non-navigating): with no actor there is no partner to resolve for the thread top bar; deferred with the reply-target case (see § "Actor-less and reply-target deep-linking is deferred").
- `target_type = "reply"` (the dynamic reply case of `post_auto_hidden`) → NO destination (non-navigating): there is no reply-by-id → parent-post endpoint to build a post-detail route. Deferred (same § as above).
- every no-target informational type (`subscription_billing_issue`, `subscription_expired`, `account_action_applied`, `data_export_ready`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`) → NO destination (non-navigating).

An unknown/future `type`, or a row missing the field its mapping requires (e.g. a `message` row without `body_data.conversation_id`), SHALL resolve to NO destination (no crash). The resolution SHALL use `actor_user_id` / `target_id` / `conversation_id` ONLY as destination payload or fetch path params — never rendering or logging them (the resolved `partnerUsername` / `partnerDisplayName` are display strings, NOT UUIDs).

#### Scenario: followed resolves to the actor's profile with no fetch

- **GIVEN** a `followed` row with `actor_user_id = "<A>"` and no `target_id`
- **WHEN** the row is tapped
- **THEN** the profile destination is invoked with `<A>` AND no `GET /api/v1/posts/...` fetch is issued AND `<A>` is not rendered in any UI node

#### Scenario: chat_message resolves the partner profile then navigates to the thread

- **GIVEN** a `chat_message` row with `target_type = "message"`, `actor_user_id = "<A>"`, `body_data = {"conversation_id":"<C>"}` AND a `GET /api/v1/users/<A>` returning `username`/`displayName`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` and the fetched `partnerUsername`/`partnerDisplayName` AND neither `<A>` nor `<C>` is rendered in any UI node

#### Scenario: a chat_message whose partner fetch fails still opens the thread

- **GIVEN** a `chat_message` row whose `GET /api/v1/users/{actor_user_id}` returns `404` (or IO failure) and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the chat-thread destination is invoked with conversation `<C>` and empty partner display fields (the conversation is reachable; the thread top bar renders its existing blank-name placeholder) AND no blocking error is surfaced

#### Scenario: chat_message_redacted (no actor) does not navigate

- **GIVEN** a `chat_message_redacted` row with `target_type = "message"`, `actor_user_id = NULL`, and `body_data = {"conversation_id":"<C>"}`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked (with no actor, the partner top-bar identity cannot be resolved; deferred)

#### Scenario: an informational no-target row navigates nowhere

- **GIVEN** a `subscription_expired` row with no `target_type` and no actionable target
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked

#### Scenario: an unknown type or a message row missing conversation_id navigates nowhere

- **GIVEN** a row whose `type` is an unrecognized/future value, OR a `target_type = "message"` row whose `body_data` has no `conversation_id`
- **WHEN** the row is tapped
- **THEN** no navigation destination is invoked AND no crash occurs (the tap still marks read)

#### Scenario: a second tap supersedes an in-flight resolution

- **GIVEN** a tapped post-target row A whose by-id fetch is still in flight
- **WHEN** a second post-target row B is tapped before A resolves
- **THEN** A's resolution is superseded/cancelled (its `CancellationException` is swallowed, never surfaced) AND only B's resolved destination is invoked (no double-navigation)

### Requirement: A post-target notification resolves to a PostDetailTarget via the full-projection single-post fetch

The mobile app SHALL resolve a `post`-target notification to a `PostDetailTarget` by fetching `GET /api/v1/posts/{target_id}` (the shipped `single-post-read` capability) through the existing `SinglePostApiClient`, extended with a full-projection read that decodes the deployed `SinglePostResponse` wire's **MIXED case** exactly: bare camelCase `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, and `@SerialName` **snake_case** `city_name` (→ `cityName`), `liked_by_viewer` (→ `likedByViewer`), `reply_count` (→ `replyCount`). It MUST NOT decode those three as bare camelCase — an all-camelCase DTO silently parses `cityName = ""`, `likedByViewer = false`, `replyCount = 0` on the real wire (the timeline-DTO mixed-case footgun). The read maps these to a `PostDetailTarget` with `distanceM = null` (the by-id projection omits coordinates). A `200` SHALL yield a `Success` carrying the mapped `PostDetailTarget`; a `404 post_not_found`, any other non-`200`, or a transport/IO failure SHALL yield the graceful `Unavailable` (mirroring the existing `SinglePostApiResult` discipline). `CancellationException` SHALL be rethrown (never mapped to a failure); `401` is owned by the `Auth` plugin. The full-projection read SHALL decode NO author UUID and NO coordinate field (the projection carries none — no-PII), and SHALL NOT alter the existing minimal `content`/`editedAt`/`isAuthor` projection the post-detail refresh consumes.

#### Scenario: a 200 mixed-case response maps to a PostDetailTarget with null distance

- **GIVEN** a MockEngine returning `200` for `GET /api/v1/posts/<P>` with the deployed mixed-case body — bare camelCase `id`/`authorUsername`/`authorDisplayName`/`content`/`createdAt` AND snake_case `"city_name"`/`"liked_by_viewer"`/`"reply_count"` keys
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns a `Success` whose `PostDetailTarget` carries the parsed `content` / `cityName` (from `city_name`) / `createdAt` / `likedByViewer` (from `liked_by_viewer`) / `replyCount` (from `reply_count`) / `authorUsername` / `authorDisplayName` AND `distanceM` is `null`

#### Scenario: an all-camelCase body does NOT bind the snake_case fields (regression guard)

- **GIVEN** a MockEngine returning `200` with an all-camelCase body that uses `cityName`/`likedByViewer`/`replyCount` keys (the wrong shape)
- **WHEN** the full-projection fetch runs
- **THEN** the test asserts those three fields do NOT populate from the camelCase keys (proving the DTO binds the snake_case `@SerialName`, so a regression to an all-camelCase DTO would fail this test rather than silently yielding `cityName=""`/`likedByViewer=false`/`replyCount=0`)

#### Scenario: a 404 (or non-200 / IO) yields Unavailable

- **GIVEN** a MockEngine returning `404 post_not_found` (or `500`, or throwing `IOException`) for `GET /api/v1/posts/<P>`
- **WHEN** the full-projection fetch runs for `<P>`
- **THEN** it returns `Unavailable` AND no exception propagates to the caller

#### Scenario: the minimal post-detail projection is undisturbed

- **WHEN** inspecting `SinglePostApiClient`
- **THEN** the existing minimal `content`/`editedAt`/`isAuthor` read used by post-detail refresh still exists and is unchanged AND the new full-projection read is a distinct method/result (the two do not share a decoded type that would force coordinate/UUID fields onto either)

### Requirement: NotificationsScreen exposes hoisted deep-link callbacks wired through the shell

`NotificationsScreen` SHALL expose hoisted navigation callbacks — `onOpenPost: (PostDetailTarget) -> Unit`, `onOpenProfile: (userId: String) -> Unit`, and `onOpenChatThread: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit` — and SHALL invoke them by consuming the `NotificationsViewModel`'s consumed-once nav signal; the screen itself SHALL remain navigation-free (it holds no back-stack reference). `AppShellScreen` SHALL stop invoking `NotificationsScreen()` bare and instead forward its already-hoisted `onOpenPost` / `onOpenProfile` callbacks plus a `onOpenChatThread` callback wired (via `appEntryProvider`) to a `ChatThreadRoute(conversationId, partnerUsername, partnerDisplayName)` push onto the root back stack. This change SHALL NOT declare any new `NavKey` — it reuses the shipped `PostDetailRoute`, `ProfileRoute`, and `ChatThreadRoute`.

#### Scenario: the shell no longer invokes NotificationsScreen bare

- **WHEN** inspecting `AppShellScreen`'s Notifikasi section
- **THEN** `NotificationsScreen` is invoked WITH the `onOpenPost` / `onOpenProfile` / `onOpenChatThread` callbacks (not bare) AND each callback is wired to a root-stack push of the corresponding existing route

#### Scenario: navigation is a consumed-once signal

- **GIVEN** a notification whose tap resolves to a destination
- **WHEN** the row is tapped once AND the screen subsequently recomposes (or the configuration changes)
- **THEN** the destination callback is invoked exactly once (the consumed-once nav signal is cleared after first delivery, not re-emitted on recomposition)

#### Scenario: no new NavKey is introduced

- **WHEN** inspecting the change's NavKey declarations
- **THEN** no new `NavKey` type is added (the deep-links reuse the shipped `PostDetailRoute`, `ProfileRoute`, and `ChatThreadRoute`)

### Requirement: Actor-less and reply-target deep-linking is deferred

Two deep-link cases SHALL be deferred (the tap marks the row read and performs NO navigation), each captured here as a negative-guard so a follow-up change has a requirement to MODIFY once the enabling path ships:

- a `target_type = "reply"` notification (the dynamic reply case of `post_auto_hidden`) — no reply-by-id → parent-post endpoint exists to build a `PostDetailRoute`.
- a `chat_message_redacted` notification (`target_type = "message"`, `actor_user_id = NULL`) — with no actor, the partner top-bar identity for the thread cannot be resolved via `user-profile-read`; navigating would land a misleading blank-name top bar on a live conversation.

#### Scenario: a reply-target auto-hidden notification does not navigate

- **GIVEN** a `post_auto_hidden` row whose `target_type = "reply"` and `target_id = "<R>"`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked AND `<R>` is not rendered in any UI node

#### Scenario: an actor-less chat_message_redacted notification does not navigate

- **GIVEN** a `chat_message_redacted` row with `actor_user_id = NULL`
- **WHEN** the row is tapped
- **THEN** the row is marked read AND no navigation destination is invoked

