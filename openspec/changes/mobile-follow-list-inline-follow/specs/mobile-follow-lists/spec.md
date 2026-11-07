## RENAMED Requirements

- FROM: `### Requirement: Inline follow/unfollow on rows is deferred`
- TO: `### Requirement: Rows carry an inline optimistic follow/unfollow toggle`

## MODIFIED Requirements

### Requirement: FollowListApiClient parses the SHIPPED camelCase list wire

`FollowListApiClient` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/followlist/`) SHALL issue `GET /api/v1/users/{user_id}/followers?cursor=` and `GET /api/v1/users/{user_id}/following?cursor=` and parse a `@Serializable` page DTO whose field names match the SHIPPED backend serialization in `follow-system` (`FollowRoutes.kt`), NOT a stale snake_case spec example. The page DTO SHALL be `{ users: List<FollowListUser>, nextCursor: String? }` and each `FollowListUser` SHALL be **bare camelCase**: `userId: String`, `username: String`, `displayName: String`, `isPremium: Boolean`, `followedByViewer: Boolean`, `isSelf: Boolean`, `createdAt: String`. `followedByViewer` / `isSelf` are the calling viewer's relation to the row user (`follow-system`) and are REQUIRED on the wire (no client default — a missing field is a contract failure, not "not followed"). `nextCursor` MUST be nullable-with-default (`null` on the last page; the app-wide `Json { explicitNulls = false }` omits it when null, so an absent key MUST decode to `null`, not throw). The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment); NO `X-Session-Id` header (these reads are not per-session soft-capped). `CancellationException` MUST be rethrown, never mapped to a failure.

#### Scenario: Parses the shipped camelCase page wire

- **GIVEN** a MockEngine returning `200` with `{ "users": [ { "userId": "...", "username": "raka.jkt", "displayName": "Raka Pratama", "isPremium": false, "followedByViewer": true, "isSelf": false, "createdAt": "2026-06-01T00:00:00Z" } ], "nextCursor": "eyJjIjoi..." }`
- **WHEN** the body is parsed
- **THEN** parsing succeeds AND the row has `username = "raka.jkt"`, `displayName = "Raka Pratama"`, `isPremium = false`, `followedByViewer = true`, `isSelf = false` AND `nextCursor` is the non-null cursor string

#### Scenario: A row missing the viewer-relative fields is a contract failure

- **GIVEN** a `200` body whose row omits `followedByViewer` / `isSelf` (a pre-change backend)
- **WHEN** the list is loaded through the repository
- **THEN** the outcome is the retryable `FollowListOutcome.NetworkError` (never a silent `followedByViewer = false` that would render a wrong "Ikuti")

#### Scenario: Omitted nextCursor decodes to null (last page)

- **GIVEN** a `200` body with a `users` array and no `nextCursor` key
- **WHEN** the body is parsed
- **THEN** parsing succeeds with `nextCursor = null` (no `SerializationException`)

#### Scenario: snake_case body does not bind (negative guard)

- **GIVEN** a `200` body using snake_case `user_id` / `display_name` / `is_premium` / `next_cursor` (a stale-spec JSON shape, NOT the shipped wire)
- **WHEN** the body is parsed into the page DTO
- **THEN** the camelCase fields are NOT populated from the snake_case keys (the casing-drift trap is guarded, per the PR #128 / `mobile-timeline-card-redesign` precedent)

### Requirement: Follow-list Koin wiring reuses the shared client

`FollowListApiClient` and `FollowListRepository` SHALL be registered as Koin singletons in `di/MobileModule.kt`, with `single<FollowListFlow> { get<FollowListRepository>() }`, reusing the shared `HttpClient` (no new client, no `X-Session-Id`). `FollowListViewModel` SHALL be obtained via androidx `viewModel { }` scoped to the Nav3 entry (resolving `FollowListFlow` and `ProfileFlow` through `koinInject`, the shipped screen pattern — `ProfileScreen` / the timeline screens) and SHALL talk only to those two repository seams — `FollowListFlow` for the list reads, the already-bound `ProfileFlow` (`ProfileRepository`) for the inline follow toggle and the return-from-profile re-read — never to an ApiClient directly (the `UI → ViewModel → Repository → ApiClient` dependency direction). No second client or repository for the follow `POST`/`DELETE` SHALL be introduced.

#### Scenario: ViewModel depends on the repository seams, not the ApiClients

- **WHEN** inspecting `FollowListViewModel` and `di/MobileModule.kt`
- **THEN** `FollowListViewModel` depends on `FollowListFlow` (bound to `FollowListRepository`) and `ProfileFlow` (bound to `ProfileRepository`) and holds no `FollowListApiClient` / `ProfileApiClient` reference AND the repositories + clients are Koin singletons reusing the shared `HttpClient`

### Requirement: Follow-list test trio

The change SHALL ship: (1) **commonTest** covering the `FollowListUiState` projection (initial-load/loading, loaded-with-rows + load-more gating, empty per tab, not-found, full-screen error, the load-more-failure-retains-rows case, the refresh-from-non-content no-skeleton case, and the mid-refresh `isRefreshing = true` transition via a suspend-from-call fake mirroring the `mobile-profile` precedent), the keyset pagination (cursor advance, append next page, stop on null `nextCursor`, single-page-no-load-more, no duplicate in-flight), the row → `ProfileRoute` navigation intent, the rendered-row-count-independent-of-profile-count guard (design D3), the page-DTO parse against the shipped camelCase wire (incl. omitted-`nextCursor`) + the snake_case negative guard, the constant-404 → single `NotFound` mapping with no generic fallthrough (incl. both-tabs-`NotFound` consistency), the `400 invalid_cursor` → `NetworkError`, the `CancellationException`-rethrow, the `FollowListRoute` polymorphic serialized round-trip, and the `initialTab` deep-link tab selection — via a `FakeFollowListFlow` + MockEngine + `runTest`; AND the inline toggle (`mobile-follow-list-inline-follow`): the `followedByViewer` / `isSelf` wire parse + the missing-field contract failure, the optimistic flip + settle for follow and unfollow, the revert on `NetworkError` / `RateLimited` (with the rate-limit message) / `TargetGone` (with the user-unavailable message), the both-tabs patch for a user present in both, the duplicate-tap no-op while in flight, a page load landing mid-toggle keeping the optimistic value, the self row exposing no toggle, the toggle write completing after the ViewModel is cleared mid-flight (`NonCancellable`), the shared settlement mapping (`FollowToggleOutcome.settle`), and the return-from-profile re-read (patch on `Loaded`, removal from both tabs on `NotFound`, no change on `NetworkError`, no read when no row was opened) — via `FakeFollowListFlow` + `FakeProfileFlow`; (2) a **Robolectric** `FollowListScreenTest` (`mobile/app/src/androidUnitTest/...`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list per the `*ScreenTest` convention) covering both tabs present, tab switch / pager sync, a row tap firing navigation, the two empty states, the no-UUID-in-tree assertion, the "Ikuti" / "Mengikuti" toggle labels + a toggle tap flipping the label without navigating, the self row rendering no toggle, and a 429 surfacing the rate-limit snackbar; (3) an **iosTest** flow test (`mobile/app/src/iosTest/...`, mirroring `NearbyTimelineFlowIosTest`, Kotlin/Native-legal function names) exercising the follow-list surface on the simulator, including the toggle flip.

#### Scenario: The three test layers exist and pass

- **WHEN** the test suite is run
- **THEN** the commonTest projection/parse/pagination/nav/serialization/toggle/re-read tests, the Robolectric `FollowListScreenTest`, and the iosTest flow test all exist and pass, AND `FollowListScreenTest` is in the Release-variant exclude block so `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Rows carry an inline optimistic follow/unfollow toggle

Every follower/following row whose user is NOT the calling viewer (`isSelf = false`) SHALL render an inline follow toggle at the row's trailing edge; a self row (`isSelf = true`) SHALL render no toggle. The toggle label is `profile_follow` ("Ikuti") when `followedByViewer = false` and `profile_unfollow` ("Mengikuti") when `true` — the SHIPPED profile-toggle copy (no new string key). Visually it follows mockup frame 7's inline identity-row "Ikuti" (`btn tonal sm`): an M3 `FilledTonalButton` for "Ikuti" and an `OutlinedButton` at the same compact metrics for "Mengikuti", keeping the M3 48 dp minimum touch target. Activating the toggle SHALL NOT navigate (the rest of the row remains the `ProfileRoute` tap target).

The toggle SHALL reuse the shipped `mobile-profile` follow semantics through the `ProfileFlow` seam (`follow` → `POST /api/v1/follows/{id}`, `unfollow` → `DELETE`), with the outcome settled by ONE shared mapping (`FollowToggleOutcome.settle`) that `ProfileViewModel` also uses, so the two surfaces cannot drift:
- Optimistic: on tap, `followedByViewer` flips immediately for EVERY loaded row of that user in BOTH tabs (the same user can appear in Pengikut and Mengikuti) and that user's toggles are disabled while the write is in flight; a second tap for the same user while in flight is a no-op.
- `Followed` / `Unfollowed` → the rows settle to `true` / `false`.
- `RateLimited` (429) → revert + the follow-churn message (`profile_follow_rate_limited`); `TargetGone` (the follow-`POST` constant 404) → revert + the neutral user-unavailable message (`profile_action_user_unavailable`); `NetworkError` → silent revert. Messages surface as a snackbar from a nullable UiState field cleared via `onMessageShown()` (no event bus).
- A list page load or refresh that lands while a toggle is in flight SHALL keep the in-flight optimistic value on that user's incoming rows (no flicker back).
- The write's network leg SHALL run under `withContext(NonCancellable)` with the in-flight claim taken before the launch (docs/11 § 2.2), so popping the screen mid-request does not abort a write the server may already have applied.
- The profile `followerCount` / `followingCount` are NOT mutated by the list (raw read snapshots; the parent `ProfileScreen` re-reads on resume).

#### Scenario: Non-self row shows the label matching followedByViewer

- **GIVEN** a loaded tab with row A (`followedByViewer = false`, `isSelf = false`) and row B (`followedByViewer = true`, `isSelf = false`)
- **WHEN** the rows render
- **THEN** row A shows "Ikuti" AND row B shows "Mengikuti"

#### Scenario: Self row exposes no toggle

- **GIVEN** a row with `isSelf = true`
- **WHEN** the row renders
- **THEN** it shows no follow toggle (only the identity and the row tap)

#### Scenario: Optimistic follow settles on success without navigating

- **GIVEN** row A showing "Ikuti" and a `ProfileFlow` whose `follow` returns `Followed`
- **WHEN** row A's toggle is activated
- **THEN** row A shows "Mengikuti" immediately AND `follow(A)` is called once AND no `ProfileRoute` navigation is emitted AND after the write resolves row A stays "Mengikuti" with its toggle re-enabled

#### Scenario: Unfollow from the own following list keeps the row

- **GIVEN** the viewer's own Mengikuti tab with row A (`followedByViewer = true`) and `unfollow` returning `Unfollowed`
- **WHEN** row A's toggle is activated
- **THEN** row A stays in the list AND shows "Ikuti"

#### Scenario: Failures revert, with the profile's messages

- **WHEN** a follow resolves `RateLimited`, then separately `TargetGone`, then separately `NetworkError`
- **THEN** each reverts the row to its pre-tap label AND the first surfaces the follow-churn message, the second the user-unavailable message, the third no message

#### Scenario: A user present in both tabs flips in both

- **GIVEN** user A appears in the loaded Pengikut AND Mengikuti tabs
- **WHEN** A's toggle is activated in either tab
- **THEN** A's row in both tabs reflects the optimistic value AND, after settle, the settled value

#### Scenario: Duplicate tap while in flight is a no-op

- **GIVEN** a follow write for A is in flight
- **WHEN** A's toggle is activated again
- **THEN** no second `follow`/`unfollow` call is issued

#### Scenario: The write completes when the screen is popped mid-flight

- **GIVEN** a follow write for A is in flight
- **WHEN** the ViewModel is cleared (the entry is popped) before the write resolves
- **THEN** the `ProfileFlow.follow(A)` call still runs to completion (it is not cancelled)

## ADDED Requirements

### Requirement: Returning from a row's profile re-reads that row

The profile reached by a row tap can change the row's displayed follow state (the user follows / unfollows there) or remove the row's user from the list's visible set (the viewer blocks them). The follow-list SHALL therefore re-read the opened user when the screen resumes — the docs/11 § 2.3 silent `ON_RESUME` re-read for "data the overlay itself edited": the row tap records the opened `userId` in the ViewModel before navigating; on the next `ON_RESUME` the ViewModel issues ONE `ProfileFlow.loadProfile(userId)` (only when a row was opened and that user has no toggle in flight) and clears the marker. On `Loaded` it patches `followedByViewer` on every loaded row of that user in both tabs; on `NotFound` (the constant 404 — blocked either direction / hidden / gone) it removes that user's rows from both tabs (what a refresh would return: the lists INNER JOIN `visible_users` and block-filter), and a tab left with no rows renders its empty state; on `NetworkError` it changes nothing. No other page is re-fetched (pagination and scroll position are preserved), and no read is issued on a resume with no opened row (e.g. the first composition).

#### Scenario: Resume after following on the profile patches the row

- **GIVEN** row A showing "Ikuti" was tapped (opening A's profile) AND `loadProfile(A)` now returns `followedByViewer = true`
- **WHEN** the list resumes
- **THEN** `loadProfile(A)` is called exactly once AND row A shows "Mengikuti" in every tab it appears in

#### Scenario: Resume after blocking on the profile removes the row

- **GIVEN** row A was tapped AND `loadProfile(A)` now returns `NotFound`
- **WHEN** the list resumes
- **THEN** A's rows are removed from both tabs AND the other rows are unchanged

#### Scenario: Re-read failure changes nothing

- **GIVEN** row A was tapped AND `loadProfile(A)` returns `NetworkError`
- **WHEN** the list resumes
- **THEN** the rows are unchanged

#### Scenario: No re-read without an opened row

- **WHEN** the list resumes without any row having been tapped since the last re-read (including the first composition)
- **THEN** no `loadProfile` call is issued
