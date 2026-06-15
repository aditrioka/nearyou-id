# mobile-follow-lists Specification

## Purpose
The `mobile-follow-lists` capability is the `:mobile:app` follower/following member-list surface — the tappable consumer of the profile screen's follower/following counts (`mobile-profile`). It ships `FollowListScreen`: a single root-stack overlay with **Pengikut** / **Mengikuti** tabs over a `HorizontalPager`, reached via `FollowListRoute(userId, initialTab)`, rendering keyset-paginated rows from the already-shipped `GET /api/v1/users/{id}/followers` + `/following` (`follow-system`, enriched with embedded profile summaries by `social-list-profile-summaries`). Each row reuses the `mobile-post-card` identity treatment (letter avatar + display name + `@handle` + Premium badge) and taps through to `ProfileRoute`. It is a pure consumer of shipped endpoints — no Flyway migration, no backend code. Hidden members (shadow-banned / soft-deleted / viewer-blocked) are excluded server-side, so the lists render only visible rows with no `akun_dihapus` placeholder. Inline per-row follow/unfollow is deferred (the row tap reaches the destination profile's follow toggle).

## Requirements
### Requirement: FollowListScreen renders a tabbed Pengikut / Mengikuti surface

The mobile app SHALL ship a composable `FollowListScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/followlist/FollowListScreen.kt`) that renders, under `NearYouTheme` (light/dark), a **single screen with two tabs** — **Pengikut** (followers) and **Mengikuti** (following) — as a Material 3 `PrimaryTabRow` (text-only, the underline indicator; NO icon, NO dot — per `mobile-design-system`) whose body is a **swipeable `HorizontalPager`** synced to the tab row (the shipped Home feed-tabs idiom). Each pager page hosts the paginated member list for its side (`mobile-post-card`-style identity rows, per § "FollowList rows render the embedded profile summary and tap through to ProfileRoute"). The screen is a **root-stack overlay** reached via `FollowListRoute` (per § "FollowListRoute is a serializable NavKey carrying userId and initialTab"): it owns its own back-bar chrome (a `TopAppBar` with a back affordance + a `stringResource` title), mirroring the other-user `ProfileScreen` / `PostDetailScreen` overlay treatment — it is NOT an inset-free shell section. No hardcoded UI string literal SHALL appear in the screen source.

#### Scenario: Screen renders two labelled feed tabs over a pager

- **WHEN** `FollowListScreen` is composed under `NearYouTheme` (with fakes for both lists)
- **THEN** the tree contains a `PrimaryTabRow` with exactly two selectable text-only tabs whose labels resolve from `stringResource` (Pengikut / Mengikuti) AND a `HorizontalPager` body, AND a back affordance in the overlay's own top bar

#### Scenario: Swiping the pager moves the selected tab and vice versa

- **GIVEN** `FollowListScreen` composed with the Pengikut tab selected
- **WHEN** the pager is swiped to the second page (and separately, when the Mengikuti tab is activated)
- **THEN** the selected tab and the visible page stay in sync (swiping selects Mengikuti; tapping Mengikuti scrolls the pager to its page)

### Requirement: FollowListRoute is a serializable NavKey carrying userId and initialTab

The change SHALL introduce a `FollowListRoute` `NavKey` (in `screens/routing/NavKeys.kt`) carrying exactly `userId: String` and `initialTab` (an enum or boolean distinguishing Followers vs Following). It SHALL be `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold`). `FollowListRoute` MUST NOT declare any `latitude`/`longitude` (or other raw-coordinate) property and MUST NOT carry any token. The `userId` SHALL be used only as the API path param and SHALL NOT be rendered as a UI string. `FollowListRoute` SHALL be mapped to `FollowListScreen` in `appEntryProvider` and pushed onto the **root** back stack by the `mobile-home-tab-host` mechanism (the same path `ProfileRoute` uses).

#### Scenario: FollowListRoute declares only userId and initialTab, no coordinates or token

- **WHEN** inspecting the `FollowListRoute` declaration
- **THEN** it declares `userId` and `initialTab` AND declares NO `latitude`/`longitude` (or any raw-coordinate) property AND no token property

#### Scenario: FollowListRoute round-trips through the polymorphic serializer

- **GIVEN** a `FollowListRoute("11111111-1111-1111-1111-111111111111", initialTab = Followers)`
- **WHEN** it is serialized + deserialized via the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS saved-state path)
- **THEN** decoding succeeds and yields an equal `FollowListRoute`, with no `SerializationException`

#### Scenario: initialTab selects the opening tab

- **GIVEN** the screen opened via `FollowListRoute(userId, initialTab = Following)`, and separately via `initialTab = Followers`
- **THEN** the first opens with the Mengikuti tab/page selected AND the second opens with the Pengikut tab/page selected

### Requirement: FollowListApiClient parses the SHIPPED camelCase list wire

`FollowListApiClient` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/followlist/`) SHALL issue `GET /api/v1/users/{user_id}/followers?cursor=` and `GET /api/v1/users/{user_id}/following?cursor=` and parse a `@Serializable` page DTO whose field names match the SHIPPED backend serialization in `follow-system` (`FollowRoutes.kt`), NOT a stale snake_case spec example. The page DTO SHALL be `{ users: List<FollowListUser>, nextCursor: String? }` and each `FollowListUser` SHALL be **bare camelCase**: `userId: String`, `username: String`, `displayName: String`, `isPremium: Boolean`, `createdAt: String`. `nextCursor` MUST be nullable-with-default (`null` on the last page; the app-wide `Json { explicitNulls = false }` omits it when null, so an absent key MUST decode to `null`, not throw). The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment); NO `X-Session-Id` header (these reads are not per-session soft-capped). `CancellationException` MUST be rethrown, never mapped to a failure.

#### Scenario: Parses the shipped camelCase page wire

- **GIVEN** a MockEngine returning `200` with `{ "users": [ { "userId": "...", "username": "raka.jkt", "displayName": "Raka Pratama", "isPremium": false, "createdAt": "2026-06-01T00:00:00Z" } ], "nextCursor": "eyJjIjoi..." }`
- **WHEN** the body is parsed
- **THEN** parsing succeeds AND the row has `username = "raka.jkt"`, `displayName = "Raka Pratama"`, `isPremium = false` AND `nextCursor` is the non-null cursor string

#### Scenario: Omitted nextCursor decodes to null (last page)

- **GIVEN** a `200` body with a `users` array and no `nextCursor` key
- **WHEN** the body is parsed
- **THEN** parsing succeeds with `nextCursor = null` (no `SerializationException`)

#### Scenario: snake_case body does not bind (negative guard)

- **GIVEN** a `200` body using snake_case `user_id` / `display_name` / `is_premium` / `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **WHEN** the body is parsed into the page DTO
- **THEN** the camelCase fields are NOT populated from the snake_case keys (the casing-drift trap is guarded, per the PR #128 / `mobile-timeline-card-redesign` precedent)

### Requirement: The list read maps to a sealed FollowListOutcome with a constant not-found

`FollowListRepository` (behind a `FollowListFlow` seam) SHALL map each list read to a sealed `FollowListOutcome`: `Loaded(users, nextCursor)` (200), `NotFound` (404 `user_not_found`), and `NetworkError` (5xx / transport / parse failure, AND the unreachable-from-UI `400 invalid_cursor`). The `404 user_not_found` body is **constant and byte-identical** across unknown / shadow-banned / soft-deleted / blocked-either-direction targets (`follow-system` § profile-target resolution) — the repository MUST map all of them to the SINGLE `NotFound` member and MUST NOT attempt to distinguish the cause. The mapping SHALL have no generic `else`/wildcard branch; `401` is delegated to the `Auth` plugin; `CancellationException` is rethrown. A `400 invalid_cursor` is unreachable from the UI (the screen only replays a `nextCursor` it received from the wire) but, if returned, maps to `NetworkError` (a non-actionable failure), NOT a separate state.

#### Scenario: 404 maps to the single NotFound outcome regardless of cause

- **GIVEN** a MockEngine returning `404` with `{ "error": { "code": "user_not_found" } }`
- **WHEN** `loadFollowers(userId)` (and `loadFollowing(userId)`) run
- **THEN** the outcome is `FollowListOutcome.NotFound` (the same member for every 404 cause — no direction hint, no per-cause branch)

#### Scenario: 5xx, transport, and invalid_cursor map to NetworkError; cancellation rethrows

- **WHEN** the read returns `503`, again when the transport throws an `IOException`, and again when it returns `400 invalid_cursor`
- **THEN** all three map to `FollowListOutcome.NetworkError` AND a thrown `CancellationException` is rethrown, NOT mapped to `NetworkError`

#### Scenario: Both tabs map to NotFound when the shared target is gone

- **GIVEN** the profile target became unresolvable (blocked / soft-deleted / shadow-banned) after the screen opened, so both endpoints answer the constant `404 user_not_found`
- **WHEN** the initial tab loads and then the other tab is revealed
- **THEN** both tabs map to the single `NotFound` state (the constant 404 is target-consistent across tabs; no per-tab and no per-cause divergence)

### Requirement: Each tab paginates by keyset cursor with load-more on scroll-to-end

Each tab SHALL request its first page on first display (no `cursor`), then append subsequent pages by passing the prior page's `nextCursor` as the `cursor` query param when the list is scrolled near its end, stopping when `nextCursor` is `null` (the last page). The per-page cap is the backend's 30; the client MUST NOT assume a different size. A load-more request MUST NOT be issued while one is already in flight for that tab, and MUST NOT be re-issued once `nextCursor` is `null`. Each tab fetches independently (the initial tab fetches immediately; the other tab fetches on first reveal) and retains its own loaded pages across tab switches / pager swipes for the lifetime of the screen.

#### Scenario: Load-more appends the next page and stops at the end

- **GIVEN** a tab whose first page returns 30 rows and a non-null `nextCursor`, and whose second page returns 10 rows and a `null` `nextCursor`
- **WHEN** the list is scrolled to its end twice
- **THEN** after the first scroll the second page is requested with `cursor = <first nextCursor>` and its rows are appended (40 total) AND after reaching the null `nextCursor` no further page request is issued

#### Scenario: No duplicate in-flight load-more

- **GIVEN** a tab with a load-more request in flight
- **WHEN** another scroll-to-end occurs before it resolves
- **THEN** no second concurrent request for the same cursor is issued

#### Scenario: Single-page list offers no load-more

- **GIVEN** a tab whose first page returns fewer than 30 rows and a `null` `nextCursor`
- **WHEN** the list is scrolled to its end
- **THEN** the load-more affordance is never offered AND no second page request is issued

### Requirement: A pure Compose-free FollowListUiState projection

The mobile app SHALL model each tab's state as a Compose-free `FollowListUiState` (a `data class` or sealed type) produced by a **pure** projection function mapping the `FollowListOutcome` (+ an `isInitialLoad` flag, an `isRefreshing` flag, the accumulated rows, and the current `nextCursor`) to the rendered state — mirroring `NearbyTimelineUiState` / `ProfileUiState` — so the mapping is deterministically unit-testable in commonTest without composing UI. Initial-load vs refresh SHALL be **separate fields** (per `mobile-design-system`): `isInitialLoad = true` with no rows maps to a loading/skeleton state; a `Loaded` with zero rows maps to an empty state; a `Loaded` with rows maps to a content state carrying the rows + a load-more affordance gated on a non-null `nextCursor`; a `NotFound` maps to a not-found state; a `NetworkError` **with no prior rows** maps to a full-screen error state with a retry control; a `NetworkError` that occurs **during load-more (prior rows already present)** MUST retain the content state with its loaded rows and surface a **non-blocking, retryable load-more-failed affordance** (e.g. a footer), and MUST NOT discard the loaded rows to a full-screen error. The projection MUST carry no PII beyond the display fields rendered (no raw coordinates; the row `userId` is carried only as the navigation key, never as rendered text).

#### Scenario: Initial load maps to loading; empty Loaded maps to empty; NotFound maps to not-found

- **WHEN** the projection runs with `isInitialLoad = true` and no rows, then with a `Loaded` of zero rows, then with a `NotFound` outcome
- **THEN** the first yields a loading state, the second yields an empty state, and the third yields a not-found state (a single not-found state, no per-cause variation)

#### Scenario: Loaded with rows carries the rows and a cursor-gated load-more

- **WHEN** the projection runs with a `Loaded` of 30 rows and a non-null `nextCursor`, and again with a `null` `nextCursor`
- **THEN** both yield a content state listing the rows AND the load-more affordance is enabled only when `nextCursor` is non-null

#### Scenario: Load-more failure retains the already-loaded rows

- **GIVEN** a tab showing a loaded page of rows (prior rows present)
- **WHEN** the load-more request for the next page fails with a `NetworkError`
- **THEN** the projection yields a content state still listing the already-loaded rows AND a non-blocking, retryable load-more-failed affordance — NOT a full-screen error that discards the loaded rows

### Requirement: FollowList tabs honor the canonical list loading and refresh pattern

Each tab SHALL follow the canonical list loading/refresh contract (`mobile-design-system` / `docs/03-UX-Design.md` § "Canonical list loading and refresh pattern"): on **initial load** (no rows yet) a skeleton/placeholder with at most one in-content indicator and NO pull-to-refresh spinner; on **refresh** of existing content a `PullToRefreshBox` indicator over the **retained** row list (the scrollable stays mounted, the in-content initial-load indicator is NOT shown); the **empty / not-found / error** states are each rendered inside a scrollable container (a single-item `LazyColumn`) so the pull-to-refresh gesture is recognized from them too, and a refresh from a non-content state does not flip back to the initial-load skeleton. The two empty states SHALL use distinct copy: an empty followers tab renders the "no followers yet" message and an empty following tab renders the "not following anyone yet" message (both `stringResource`).

#### Scenario: Initial load shows a skeleton, not the pull-to-refresh spinner

- **GIVEN** a tab in its initial load with no rows yet
- **THEN** a skeleton/placeholder with a single in-content indicator is shown AND the `PullToRefreshBox` spinner is NOT shown

#### Scenario: Refresh shows the spinner over retained rows

- **GIVEN** a tab already showing a loaded row list
- **WHEN** a pull-to-refresh is triggered
- **THEN** the `PullToRefreshBox` indicator shows over the retained rows (the list stays mounted) AND no second in-content initial-load indicator appears

#### Scenario: Distinct empty copy per tab

- **WHEN** the followers tab loads zero rows, and the following tab loads zero rows
- **THEN** the followers empty state renders the "no followers yet" copy AND the following empty state renders the "not following anyone yet" copy (each via `stringResource`)

#### Scenario: Refresh from a non-content state does not flip to the skeleton

- **GIVEN** a tab in its empty state (and separately, in its error state)
- **WHEN** a pull-to-refresh is triggered from that state
- **THEN** the state's scrollable container stays mounted and the initial-load skeleton is NOT shown (the refresh drives `isRefreshing`, never `isInitialLoad`), and the state is retained until the refresh resolves

### Requirement: FollowList rows render the embedded profile summary and tap through to ProfileRoute

Each list row SHALL render the embedded profile summary using the identity treatment **reused** from `mobile-post-card` / `mobile-profile` (so it cannot drift): the **letter avatar + deterministic-color mapping**, the **display name**, the **`@username` handle** via the shared `stringResource` handle format, and an actively-**Premium badge** when `isPremium = true` (an M3 icon carrying a `stringResource` **content description** — the badge is conveyed by the icon shape + its accessible label, never by color alone; a compact list row omits the visible "Premium" text label the larger profile header shows). Tapping a row SHALL push `ProfileRoute(rowUserId)` onto the **root** back stack (the established other-user-profile entry). The row's `userId` (a UUID) MUST NOT be rendered in any UI node — it is carried only as the navigation key.

#### Scenario: Row renders identity and Premium badge

- **GIVEN** a row with `username = "sari.bdg"`, `displayName = "Sari Lestari"`, `isPremium = true`
- **WHEN** the row is rendered
- **THEN** the tree contains the letter avatar, "Sari Lestari", the handle format applied to "sari.bdg" (rendering "@sari.bdg"), and the Premium badge — an M3 icon whose `stringResource` content description is present (carried by more than color alone)

#### Scenario: Tapping a row navigates to that user's profile

- **GIVEN** a loaded list with a row for `userId = "22222222-2222-2222-2222-222222222222"`
- **WHEN** the row is activated
- **THEN** navigation to `ProfileRoute("22222222-2222-2222-2222-222222222222")` is emitted onto the root stack

#### Scenario: The row userId UUID is never rendered

- **GIVEN** a row reached for `userId = "22222222-2222-2222-2222-222222222222"` with `username = "sari.bdg"`
- **WHEN** the row is rendered
- **THEN** no UI node contains the UUID string (only the display identity is shown)

### Requirement: Hidden members are excluded by the backend, never placeheld in the list

The follower/following lists SHALL render **only the rows the endpoints return**. Because `/followers` and `/following` source rows via INNER JOIN `visible_users` (`follow-system` / the archived `social-list-profile-summaries`, now folded into `follow-system`), shadow-banned / soft-deleted members and viewer-blocked members (either direction) are excluded server-side and never reach the client. The list rendering therefore SHALL NOT implement any `akun_dihapus` / "Akun Dihapus" placeholder or COALESCE-style masking for these lists (that masking is a `GET /blocks`-only behavior, not present on these endpoints), and SHALL NOT special-case null `username` / `displayName` (both are NOT NULL on the wire). A row's identity fields are rendered verbatim from the embedded summary.

The rendered row count is **independent of** the profile's `followerCount` / `followingCount` (which are raw public aggregates per `user-profile-read` § "Follower and following counts are raw totals" — NOT viewer-block-filtered and NOT visibility-filtered). A profile showing e.g. `followerCount = 12` MAY legitimately render fewer than 12 rows when some followers are viewer-hidden; the client MUST NOT pad the list to the count, reconcile the count to the list length, or recompute either (per design D3 — the asymmetry is deliberate and prevents block-state leakage via count deltas; the count lives on `ProfileScreen` and is unaffected by this surface).

#### Scenario: No placeholder row logic is present

- **WHEN** inspecting the `screens/followlist/` row composable and the `followlist/` DTO/projection sources
- **THEN** no `akun_dihapus` / "Akun Dihapus" placeholder string, COALESCE-style masking, or null-`username`/`displayName` fallback is implemented for the follower/following rows (the lists render exactly the rows the endpoint returns)

#### Scenario: Rows render the embedded fields verbatim

- **GIVEN** a `200` page whose rows carry non-null `username` and `displayName`
- **WHEN** the list is rendered
- **THEN** each row shows its own `displayName` and `@username` exactly as received (no substitution)

#### Scenario: Rendered row count is independent of the profile count

- **GIVEN** a profile whose `followerCount` is 12 but whose `/followers` page returns 10 visible rows (2 followers are viewer-hidden server-side)
- **WHEN** the followers tab is rendered
- **THEN** the tab renders exactly the 10 returned rows AND the client neither pads to 12 nor reconciles/recomputes the count (the count is unaffected — it lives on `ProfileScreen`)

### Requirement: Inline follow/unfollow on rows is deferred

`FollowListScreen` rows SHALL be **navigational only** — a row exposes no inline follow/unfollow toggle in this change. The follow action lives on the destination `ProfileScreen` (`mobile-profile` § "Follow toggle is optimistic …"), reached by tapping the row. An inline per-row follow/unfollow affordance is explicitly **deferred** and SHALL be tracked by a `follow-up` GitHub issue, so a future change has a concrete requirement to MODIFY rather than an unstated gap.

#### Scenario: Rows expose no inline follow control

- **WHEN** a follower/following row is inspected
- **THEN** the row contains no follow/unfollow toggle (the only affordance is the row tap → `ProfileRoute`)

#### Scenario: The deferral is tracked

- **WHEN** the change is delivered
- **THEN** a `follow-up` GitHub issue exists capturing the deferred inline-row follow/unfollow action

### Requirement: All follow-list copy is sourced via shared resources

Every user-facing string on the follow-list surface (the two tab labels Pengikut / Mengikuti, the overlay back-bar title, the two empty-state messages, the loading / error / retry copy, the row handle format reuse, any count/section labels) SHALL be sourced via `:shared:resources` `stringResource(Res.string.<name>)` in single-language Bahasa Indonesia. No hardcoded UI string literal SHALL appear in the follow-list source. `SharedStringsCatalogTest` SHALL reference each new accessor and bump its declared-count assertion.

#### Scenario: No hardcoded UI strings on the follow-list surface

- **WHEN** the `screens/followlist/` + `followlist/` sources are inspected
- **THEN** no hardcoded UI string literal appears AND every user-facing string resolves from a `Res.string` accessor AND `SharedStringsCatalogTest` references the new keys with an updated count

### Requirement: Follow-list rendering carries no PII and does not widen logging

`FollowListScreen` and its data layer SHALL render only display fields (display name, `@handle`, the Premium badge). The row `userId` (a UUID) and the profile `userId` MUST NOT be rendered in any UI node. No raw coordinates exist on this surface. Tokens and response bodies MUST NOT be logged (`HttpClientFactory` stays at `LogLevel.HEADERS` with `Authorization` sanitization; this capability MUST NOT widen logging).

#### Scenario: No UUID in the rendered tree and logging not widened

- **GIVEN** a follow-list opened via `FollowListRoute("11111111-1111-1111-1111-111111111111", …)` with a row for a user `"sari.bdg"`
- **WHEN** the screen is rendered
- **THEN** no UI node contains either UUID (only display identities are shown) AND `HttpClientFactory` remains at `LogLevel.HEADERS` (bodies not logged)

### Requirement: Follow-list Koin wiring reuses the shared client

`FollowListApiClient` and `FollowListRepository` SHALL be registered as Koin singletons in `di/MobileModule.kt`, with `single<FollowListFlow> { get<FollowListRepository>() }`, reusing the shared `HttpClient` (no new client, no `X-Session-Id`). `FollowListViewModel` SHALL be obtained via androidx `viewModel { }` scoped to the Nav3 entry (resolving `FollowListFlow` through `koinInject`, the shipped screen pattern — `ProfileScreen` / the timeline screens) and SHALL talk to `FollowListFlow`, never to the ApiClient directly (the `UI → ViewModel → Repository → ApiClient` dependency direction).

#### Scenario: ViewModel depends on the repository seam, not the ApiClient

- **WHEN** inspecting `FollowListViewModel` and `di/MobileModule.kt`
- **THEN** `FollowListViewModel` depends on `FollowListFlow` (bound to `FollowListRepository`) and holds no `FollowListApiClient` reference AND the repository + client are Koin singletons reusing the shared `HttpClient`

### Requirement: Follow-list test trio

The change SHALL ship: (1) **commonTest** covering the `FollowListUiState` projection (initial-load/loading, loaded-with-rows + load-more gating, empty per tab, not-found, full-screen error, the load-more-failure-retains-rows case, the refresh-from-non-content no-skeleton case, and the mid-refresh `isRefreshing = true` transition via a suspend-from-call fake mirroring the `mobile-profile` precedent), the keyset pagination (cursor advance, append next page, stop on null `nextCursor`, single-page-no-load-more, no duplicate in-flight), the row → `ProfileRoute` navigation intent, the rendered-row-count-independent-of-profile-count guard (design D3), the page-DTO parse against the shipped camelCase wire (incl. omitted-`nextCursor`) + the snake_case negative guard, the constant-404 → single `NotFound` mapping with no generic fallthrough (incl. both-tabs-`NotFound` consistency), the `400 invalid_cursor` → `NetworkError`, the `CancellationException`-rethrow, the `FollowListRoute` polymorphic serialized round-trip, and the `initialTab` deep-link tab selection — via a `FakeFollowListFlow` + MockEngine + `runTest`; (2) a **Robolectric** `FollowListScreenTest` (`mobile/app/src/androidUnitTest/...`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list per the `*ScreenTest` convention) covering both tabs present, tab switch / pager sync, a row tap firing navigation, the two empty states, and the no-UUID-in-tree assertion; (3) an **iosTest** flow test (`mobile/app/src/iosTest/...`, mirroring `NearbyTimelineFlowIosTest`, Kotlin/Native-legal function names) exercising the follow-list surface on the simulator.

#### Scenario: The three test layers exist and pass

- **WHEN** the test suite is run
- **THEN** the commonTest projection/parse/pagination/nav/serialization tests, the Robolectric `FollowListScreenTest`, and the iosTest flow test all exist and pass, AND `FollowListScreenTest` is in the Release-variant exclude block so `:mobile:app:testDevReleaseUnitTest` passes

