# mobile-profile Specification

## Purpose
The `mobile-profile` capability is the `:mobile:app` profile surface — the keystone of the mobile critical path that turns the social-graph half of the product from a visible dead-end into a usable loop. It ships `ProfileScreen`, which renders a user's profile from the shipped `GET /api/v1/users/{user_id}` (`user-profile-read`) read, reachable both as the **self** profile in the Profil bottom-nav section and as an **other-user** root-stack overlay via the feed-card author-identity tap, and it adds the app's **first follow/unfollow action** plus user-level **block** and **report**. It is a pure consumer of already-shipped backend endpoints (`user-profile-read`, `follow-system`, `user-blocking`, `reports`) — no Flyway migration, no backend code — and it unblocks the live Following feed (which needs a follow action to be meaningful).

## Requirements
### Requirement: ProfileScreen renders a user's profile from the shipped profile read

The mobile app SHALL ship a composable `ProfileScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/profile/ProfileScreen.kt`, replacing `ProfilePlaceholderScreen.kt`) that renders a user's profile from `GET /api/v1/users/{user_id}` (`user-profile-read`). It SHALL render, under `NearYouTheme` (light/dark): the **letter avatar + display name + `@username` handle** (reusing the `mobile-post-card` avatar derivation + deterministic-color mapping + @-handle `stringResource` format so the identity treatment cannot drift), the **bio** when non-null (omitted with no empty row when null), an actively-**Premium badge** when `isPremium = true` (an M3 icon + a `stringResource` label — never a color-only signal), and the **follower and following counts** as static numbers (per § "Follower and following counts render as static numbers"). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL be usable for both the **self** read (rendered in the shell's Profil section, inset-free, no own `Scaffold`/`TopAppBar`) and an **other-user** read (a root-stack overlay owning its own back-bar chrome) — the same composable parameterized by the resolved profile and the endpoint's `isSelf`.

#### Scenario: Profile renders identity, bio, and counts

- **GIVEN** a loaded profile with `displayName = "Raka Pratama"`, `username = "raka.jkt"`, `bio = "Penyuka kopi"`, `followerCount = 12`, `followingCount = 34`
- **WHEN** `ProfileScreen` is rendered
- **THEN** the tree contains "Raka Pratama", the handle format applied to "raka.jkt" (rendering "@raka.jkt"), "Penyuka kopi", and the follower/following count numbers 12 and 34

#### Scenario: Null bio renders no bio row

- **GIVEN** a loaded profile whose `bio` is null (absent on the wire)
- **WHEN** `ProfileScreen` is rendered
- **THEN** no empty bio row is rendered (no blank text node, no crash)

#### Scenario: Premium badge reflects isPremium

- **WHEN** `ProfileScreen` is rendered with `isPremium = true` and again with `isPremium = false`
- **THEN** the Premium badge (M3 icon + `stringResource` label) is present in the first render and absent in the second, AND the badge state is carried by more than color alone

### Requirement: ProfileApiClient parses the SHIPPED camelCase profile wire with null tolerance

`ProfileApiClient` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/profile/`) SHALL issue `GET /api/v1/users/{user_id}` and parse a `@Serializable UserProfileResponse` whose field names match the SHIPPED backend serialization in `backend/ktor/.../user/` (`user-profile-read`), NOT a stale spec JSON example. The DTO SHALL be **camelCase**: `userId: String`, `username: String`, `displayName: String`, `bio: String? = null`, `followerCount: Int`, `followingCount: Int`, `isSelf: Boolean`, `followedByViewer: Boolean`, `isPremium: Boolean`, `isPrivate: Boolean? = null`. `bio` and `isPrivate` MUST be nullable-with-default because the app-wide `Json { explicitNulls = false }` OMITS them from the JSON when null (an absent key MUST decode to `null`, not throw). The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment). No `X-Session-Id` header (the profile read is not per-session soft-capped). `CancellationException` MUST be rethrown, never mapped to a failure.

#### Scenario: Parses the shipped camelCase wire with bio present and isPrivate omitted

- **GIVEN** a MockEngine returning `200` with `{ "userId": "...", "username": "raka.jkt", "displayName": "Raka Pratama", "bio": "halo", "followerCount": 3, "followingCount": 5, "isSelf": false, "followedByViewer": true, "isPremium": false }` (no `isPrivate` key)
- **WHEN** the body is parsed
- **THEN** parsing succeeds AND `bio = "halo"`, `followedByViewer = true`, AND `isPrivate` decodes to `null` (absent tolerated)

#### Scenario: Omitted bio decodes to null

- **GIVEN** a `200` body with no `bio` key and all required fields present
- **WHEN** the body is parsed
- **THEN** parsing succeeds with `bio = null` (no `SerializationException`)

#### Scenario: snake_case body does not bind (negative guard)

- **GIVEN** a `200` body using snake_case `user_id` / `display_name` / `follower_count` (a stale-spec JSON shape, NOT the shipped wire)
- **WHEN** the body is parsed into `UserProfileResponse`
- **THEN** the camelCase fields are NOT populated from the snake_case keys (the casing-drift trap is guarded, per the PR #128 precedent)

### Requirement: The profile read maps to a sealed ProfileOutcome with a constant not-found

`ProfileRepository` (behind a `ProfileFlow` seam) SHALL map the profile read to a sealed `ProfileOutcome`: `Loaded(profile)` (200), `NotFound` (404 `user_not_found`), and `NetworkError` (5xx / transport / parse failure). The `404 user_not_found` body is **constant and byte-identical** across unknown / shadow-banned / soft-deleted / blocked-either-direction targets (`user-profile-read` § leak-safety) — the repository MUST map all of them to the SINGLE `NotFound` member and MUST NOT attempt to distinguish the cause. The mapping SHALL have no generic `else`/wildcard branch; `401` is delegated to the `Auth` plugin; `CancellationException` is rethrown. A malformed-UUID `400 invalid_request` is unreachable from the UI (the screen only navigates to ids it received from the wire) but, if returned, maps to `NetworkError` (a non-actionable failure), NOT a separate state.

#### Scenario: 404 maps to the single NotFound outcome regardless of cause

- **GIVEN** a MockEngine returning `404` with `{ "error": { "code": "user_not_found" } }`
- **WHEN** `loadProfile(userId)` runs
- **THEN** the outcome is `ProfileOutcome.NotFound` (the same member for every 404 cause — no direction hint, no per-cause branch)

#### Scenario: 5xx and transport failures map to NetworkError

- **WHEN** the read returns `503`, and again when the transport throws an `IOException`
- **THEN** both map to `ProfileOutcome.NetworkError` AND a thrown `CancellationException` is rethrown, NOT mapped to `NetworkError`

### Requirement: Self vs other-user rendering is driven by isSelf

`ProfileScreen` SHALL show the follow/unfollow toggle and the kebab (Blokir / Laporkan) **only when `isSelf = false`**. When `isSelf = true` (the self read) it SHALL render NO follow toggle and NO block/report kebab (a user cannot follow, block, or report themselves; the backend rejects self-follow/self-block/self-report anyway). The self profile is reached via the Profil bottom-nav section (its `userId` resolved from the session, per `mobile-home-tab-host`); an other-user profile is reached via `ProfileRoute(userId)`.

#### Scenario: Self read shows no actions

- **GIVEN** a loaded profile with `isSelf = true`
- **WHEN** `ProfileScreen` is rendered
- **THEN** no follow/unfollow control and no Blokir/Laporkan kebab are present in the tree

#### Scenario: Other-user read shows the actions

- **GIVEN** a loaded profile with `isSelf = false`
- **WHEN** `ProfileScreen` is rendered
- **THEN** a follow/unfollow control AND a kebab exposing Blokir + Laporkan are present

### Requirement: ProfileRoute is a serializable NavKey carrying only the userId resource key

The change SHALL introduce a `ProfileRoute` `NavKey` (in `screens/routing/NavKeys.kt`) carrying exactly `userId: String` — the resource key the keyed read requires. It SHALL be `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold`). `ProfileRoute` MUST NOT declare any `latitude`/`longitude` (or other raw-coordinate) property and MUST NOT carry any token. The `userId` SHALL NOT be rendered as a UI string anywhere on the screen (it is used only as the API path param). `ProfileRoute` is mapped to `ProfileScreen` (other-user mode) in `appEntryProvider` and pushed onto the **root** back stack by `mobile-home-tab-host`.

#### Scenario: ProfileRoute declares userId only, no coordinates or token

- **WHEN** inspecting the `ProfileRoute` declaration
- **THEN** it declares `userId` AND declares NO `latitude`/`longitude` (or any raw-coordinate) property AND no token property

#### Scenario: ProfileRoute round-trips through the polymorphic serializer

- **GIVEN** a `ProfileRoute("11111111-1111-1111-1111-111111111111")`
- **WHEN** it is serialized + deserialized via the `navSavedStateConfiguration` polymorphic `SerializersModule` (the iOS saved-state path)
- **THEN** decoding succeeds and yields an equal `ProfileRoute`, with no `SerializationException`

#### Scenario: The userId is not rendered

- **GIVEN** an other-user profile reached via `ProfileRoute("11111111-1111-1111-1111-111111111111")`
- **WHEN** the screen is rendered
- **THEN** no UI node contains the userId UUID string (only the display identity is shown)

### Requirement: Follow toggle is optimistic, seeded by followedByViewer, status-driven

The follow control's initial state SHALL be the profile read's `followedByViewer`. A tap SHALL flip the state optimistically and issue `POST /api/v1/follows/{user_id}` (follow) or `DELETE /api/v1/follows/{user_id}` (unfollow). The `DELETE` returns `204` idempotently (a no-op when no edge exists, never `404`). The `POST` returns `204` on success OR — unlike the `DELETE` — a **constant `404 user_not_found`** when the target is unresolvable (unknown / soft-deleted / shadow-banned / blocked-either-direction; per `follow-system` § "Follow target user must exist", which resolves the target through the same visibility gate as the profile read and answers the byte-identical constant 404). The repository SHALL map results to `FollowToggleOutcome` (`Followed` / `Unfollowed` / `RateLimited(retryAfterSeconds)` / `TargetGone` (the follow-`POST` constant 404) / `NetworkError`), preserving `Retry-After` for `429`. On a non-204 result the optimistic flip SHALL revert; a `429` SHALL revert AND surface the follow-churn rate-limit message (`stringResource`); a `TargetGone` SHALL revert AND surface a neutral, direction-less "user unavailable" message (`stringResource`) — the constant 404 carries no cause, so the message MUST NOT hint at a block/shadow-ban/deletion — with NO forced navigation. The displayed `followerCount` SHALL NOT be mutated locally on toggle (it is a read snapshot of a raw public aggregate). The follow control is never shown on the self read.

#### Scenario: Follow flips optimistically and persists on 204

- **GIVEN** an other-user profile with `followedByViewer = false`
- **WHEN** the follow control is tapped and the `POST` returns `204`
- **THEN** the control immediately shows the followed state AND remains followed after the outcome resolves AND `followerCount` is unchanged locally

#### Scenario: Failure reverts the optimistic flip

- **GIVEN** an other-user profile with `followedByViewer = false`
- **WHEN** the follow control is tapped and the `POST` returns `503` (or the transport fails)
- **THEN** the control reverts to the not-followed state

#### Scenario: 429 reverts and surfaces the churn limit

- **WHEN** the follow `POST` returns `429` with a `Retry-After`
- **THEN** the optimistic flip reverts AND a follow-churn rate-limit message (sourced via `stringResource`) is surfaced

#### Scenario: Follow POST on a now-unresolvable target maps to TargetGone and reverts

- **GIVEN** an other-user profile with `followedByViewer = false`
- **WHEN** the follow control is tapped and the `POST /api/v1/follows/{id}` returns the constant `404 user_not_found` (the target was shadow-banned / blocked / soft-deleted since the read)
- **THEN** the outcome is `FollowToggleOutcome.TargetGone` AND the optimistic flip reverts AND a neutral "user unavailable" message (sourced via `stringResource`, with no block/shadow-ban/deletion hint) is surfaced AND the screen does NOT force-navigate

### Requirement: Block confirms, calls the block endpoint, then pops back

The other-user kebab SHALL expose "Blokir @{username}" which opens a confirmation modal (`docs/03-UX-Design.md` § Block User UX): the prompt copy via `stringResource`, a red "Blokir" confirm and a "Batal" dismiss. Confirming SHALL issue `POST /api/v1/blocks/{user_id}`; the repository maps results to `BlockOutcome` (`Blocked` / `RateLimited(retryAfterSeconds)` / `NetworkError`). On `Blocked` (204) the screen SHALL surface the success toast (`stringResource`) and **pop back** (the just-blocked profile would `404` on any re-read and the backend has removed follows in both directions) — modeled as a nullable one-shot state field consumed via an `onXxxShown()` callback, NOT a `Channel`/`SharedFlow`. A `429` SHALL surface the block rate-limit message and NOT pop. Block is impossible on the self read (no kebab).

#### Scenario: Confirming a block calls the endpoint and pops back

- **GIVEN** an other-user profile, the kebab open, "Blokir" tapped, the confirmation modal shown
- **WHEN** the modal's red "Blokir" is confirmed and the `POST /api/v1/blocks/{id}` returns `204`
- **THEN** a success toast is surfaced AND a one-shot "navigate back" state is emitted (consumed via its `onXxxShown()` callback), popping the overlay

#### Scenario: Cancelling the modal makes no network call

- **GIVEN** the block confirmation modal shown
- **WHEN** "Batal" is tapped
- **THEN** no `POST /api/v1/blocks` request is issued AND the screen stays on the profile

### Requirement: Report opens a 6-category reason picker mapped to the wire enum

The other-user kebab SHALL expose "Laporkan" which opens a reason picker showing the six `docs/03-UX-Design.md` § Report UX categories — Spam / Ujaran kebencian (SARA) / Pelecehan / Konten dewasa / Misinformasi / Lainnya (each a `:shared:resources` string) — plus an optional note field of ≤200 Unicode code points (the submit disabled past 200 cp, matching the server bound; the note is omitted from the body when blank). Submitting SHALL issue `POST /api/v1/reports` with body `{ "target_type": "user", "target_id": "<userId>", "reason_category": "<mapped>", "reason_note"?: "<note>" }` (snake_case wire). A pure, exhaustively-tested commonMain mapping SHALL map each picker category to its wire `reason_category` value: Spam→`spam`, Ujaran kebencian (SARA)→`hate_speech_sara`, Pelecehan→`harassment`, Konten dewasa→`adult_content`, Misinformasi→`misinformation`, Lainnya→`other`. The wire values `self_harm` and `csam_suspected` are internal/automated classifications and MUST NOT appear in the picker. The repository maps results to `ReportOutcome` (`Submitted` (204) / `Duplicate` (409 `duplicate_report` — the shipped `ReportRoutes.kt` + `reports` spec requirement code; NOT the stale `reports.duplicate` in that spec's purpose line) / `RateLimited(retryAfterSeconds)` (429) / `NetworkError`). `Submitted` surfaces the success toast; `Duplicate` surfaces an "already reported" message; `429` surfaces the report rate-limit message.

#### Scenario: Report submits with the mapped wire enum

- **GIVEN** an other-user profile, the reason picker open, "Pelecehan" selected, a 10-character note entered
- **WHEN** submit is tapped and the `POST /api/v1/reports` returns `204`
- **THEN** the request body has `target_type = "user"`, `target_id = <the profile's userId>`, `reason_category = "harassment"`, `reason_note = <the note>` AND a success toast is surfaced

#### Scenario: Picker exposes exactly the six user-facing categories

- **WHEN** the reason picker is inspected
- **THEN** it contains exactly the six categories (Spam / Ujaran kebencian (SARA) / Pelecehan / Konten dewasa / Misinformasi / Lainnya) AND no `self_harm` / `csam_suspected` option

#### Scenario: Duplicate report surfaces the already-reported message

- **WHEN** the `POST /api/v1/reports` returns `409` with `{ "error": { "code": "duplicate_report" } }`
- **THEN** the outcome is `ReportOutcome.Duplicate` AND an "already reported" message (sourced via `stringResource`) is surfaced (no crash, no generic error)

#### Scenario: Report note is gated at 200 Unicode code points

- **WHEN** the note field holds exactly 200 Unicode code points (including a surrogate-pair emoji), and again at 201
- **THEN** submit is enabled at 200 and disabled at 201, AND the gate counts **Unicode code points** (NOT the UTF-16 `.length`, so a regression to `.length` would fail the test) — matching the server's ≤200 bound

#### Scenario: Blank note is omitted from the request body

- **WHEN** a report is submitted with an empty/blank note
- **THEN** the `POST /api/v1/reports` body contains NO `reason_note` key (the field is omitted, not sent as `""` or `null`)

### Requirement: A pure Compose-free ProfileUiState projection

The mobile app SHALL model the screen state as a Compose-free `ProfileUiState` (a `data class` or sealed type) produced by a pure projection function mapping the `ProfileOutcome` (+ an `isInitialLoad` flag and the live follow/block/report sub-states) to the rendered state — mirroring `NearbyTimelineUiState` / `PostDetailUiState` — so the mapping is deterministically unit-testable in commonTest without composing UI. Initial-load vs refresh SHALL be separate fields (per `mobile-design-system`): `isInitialLoad = true` with no content maps to a loading state; a `NotFound` outcome maps to a not-found state; a `NetworkError` (with no prior content) maps to an error state with a retry control. The one-shot events (block→navigate-back, the toasts, the `429`/duplicate banners) SHALL be nullable fields cleared via `onXxxShown()` callbacks. The projection MUST carry no PII (no userId, no coordinates) beyond the display fields rendered.

#### Scenario: Initial load maps to loading; not-found maps to not-found

- **WHEN** the projection runs with `isInitialLoad = true` and no outcome, and again with a `NotFound` outcome
- **THEN** the first yields a loading state AND the second yields a not-found state (a single not-found state, no per-cause variation)

#### Scenario: One-shot events are nullable state fields, not streams

- **WHEN** inspecting the `ProfileViewModel` + `ProfileUiState`
- **THEN** the navigate-back / toast / banner one-shots are nullable `ProfileUiState` fields consumed via `onXxxShown()` callbacks AND no `Channel`/`SharedFlow` ViewModel→UI event bus is introduced

### Requirement: Follower and following counts render as static numbers

`ProfileScreen` SHALL render `followerCount` and `followingCount` as static numbers with a `stringResource` label. They SHALL NOT be tappable in this change — there SHALL be no clickable node on the count area and no follower/following list screen (no dead controls). The follower/following **list** screens (backed by `GET /api/v1/users/{user_id}/followers` and `/following` + `social-list-profile-summaries`) are deferred to a `follow-up` issue.

#### Scenario: Counts are not tappable

- **WHEN** the count area's semantics tree is inspected
- **THEN** the follower and following counts render as text with no clickable node, and no navigation to a list screen is wired

### Requirement: Profile rendering carries no PII and does not log bodies

`ProfileScreen` and its data layer SHALL render only display fields (display name, @handle, bio, the counts, the Premium badge). The target `userId` (a UUID) MUST NOT be rendered in any UI node. No raw coordinates exist on this surface. Tokens and response bodies MUST NOT be logged (`HttpClientFactory` stays at `LogLevel.HEADERS` with `Authorization` sanitization; this capability MUST NOT widen logging).

#### Scenario: The userId UUID is not in the rendered tree and not logged

- **GIVEN** a profile reached via `ProfileRoute("11111111-1111-1111-1111-111111111111")` with `username = "raka.jkt"`
- **WHEN** the screen is rendered
- **THEN** no UI node contains the UUID `11111111-1111-1111-1111-111111111111` (the display identity is shown) AND `HttpClientFactory` remains at `LogLevel.HEADERS` (bodies not logged)

### Requirement: All profile copy is sourced via shared resources

Every user-facing string on the profile surface (the counts labels, the Premium badge label, the follow/unfollow labels, the kebab items, the block confirmation modal copy, the report categories + note placeholder, the toasts, the not-found / error / loading copy) SHALL be sourced via `:shared:resources` `stringResource(Res.string.<name>)` in single-language Bahasa Indonesia. No hardcoded UI string literal SHALL appear in the profile source. `SharedStringsCatalogTest` SHALL reference each new accessor and bump its declared-count assertion.

#### Scenario: No hardcoded UI strings on the profile surface

- **WHEN** the `screens/profile/` + `profile/` sources are inspected
- **THEN** no hardcoded UI string literal appears AND every user-facing string resolves from a `Res.string` accessor AND `SharedStringsCatalogTest` references the new keys with an updated count

### Requirement: Edit-profile, suspension countdown, and post-detail identity tap are deferred

This change SHALL NOT ship: (a) an **edit-profile** affordance (bio / display-name / username editing) — no backend write endpoint is shipped (`user-profile-read` is read-only; Premium username customization is DESIGN-status with no `PATCH /api/v1/user/username`); (b) a **suspension-countdown** on the profile — `user-profile-read` deliberately does not carry suspension state (it is surfaced at the auth boundary), so there is no backing data; (c) the **post-detail author-identity tap** → profile — blocked by the `mobile-post-detail` `PostDetailRoute` no-author-UUID serialization discipline. Each deferral SHALL be tracked by a `follow-up` GitHub issue. The self `ProfileScreen` SHALL render no edit control and no suspension field; the feed-card identity tap (per `mobile-post-card` / `mobile-nearby-timeline` / `mobile-global-timeline`) is the in-scope entry to other-user profiles.

#### Scenario: No edit control and no suspension field on the self profile

- **GIVEN** a loaded self profile (`isSelf = true`)
- **WHEN** `ProfileScreen` is rendered
- **THEN** no edit-profile control is present AND no suspension-countdown field is rendered (neither is backed by a shipped endpoint)

### Requirement: Profile Koin wiring reuses the shared client

`ProfileApiClient` and `ProfileRepository` SHALL be registered as Koin singletons in `di/MobileModule.kt`, with `single<ProfileFlow> { get<ProfileRepository>() }`, reusing the shared `HttpClient` (no new client, no `X-Session-Id`). `ProfileViewModel` SHALL be obtained via `koinViewModel()` scoped to the Nav3 entry and SHALL talk to `ProfileFlow`, never to the ApiClient directly (the `UI → ViewModel → Repository → ApiClient` dependency direction).

#### Scenario: ViewModel depends on the repository seam, not the ApiClient

- **WHEN** inspecting `ProfileViewModel` and `di/MobileModule.kt`
- **THEN** `ProfileViewModel` depends on `ProfileFlow` (bound to `ProfileRepository`) and holds no `ProfileApiClient` reference AND the repository + client are Koin singletons reusing the shared `HttpClient`

### Requirement: Profile test trio

The change SHALL ship: (1) **commonTest** covering the `ProfileUiState` projection (loading / loaded-self / loaded-other / not-found / error), the follow optimistic-flip + revert-on-failure + 429-revert, the block→navigate-back one-shot, the report category→wire-enum mapping (all six) + duplicate (409) + 429, the `UserProfileResponse` parse against the shipped camelCase wire (incl. omitted-`bio` and omitted-`isPrivate` fixtures) + the snake_case negative guard, the constant-404→`NotFound` mapping, the no-generic-fallthrough in each outcome mapping, the `CancellationException`-rethrow, and the `ProfileRoute` polymorphic serialized round-trip — via `FakeProfileFlow` + MockEngine + `runTest`; (2) a **Robolectric** `ProfileScreenTest` (`mobile/app/src/androidUnitTest/...`, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list per the `*ScreenTest` convention) covering self vs other rendering, the follow control presence/absence on self, the block confirmation modal (incl. Batal makes no call), the report reason picker (exactly six categories), counts not tappable, and the no-UUID-in-tree assertion; (3) an **iosTest** flow test (`mobile/app/src/iosTest/...`, mirroring `NearbyTimelineFlowIosTest` / the post-detail iOS test, Kotlin/Native-legal function names) exercising the profile surface on the simulator.

#### Scenario: The three test layers exist and pass

- **WHEN** the test suite is run
- **THEN** the commonTest projection/parse/mapping tests, the Robolectric `ProfileScreenTest`, and the iosTest flow test all exist and pass, AND `ProfileScreenTest` is in the Release-variant exclude block so `:mobile:app:testDevReleaseUnitTest` passes

