# mobile-post-creation Specification

## Purpose
The mobile content-creation surface in `:mobile:app` — the first authoring screen and the write half of the core loop. `PostCreationScreen` (a composable opened by a home-surface FAB) renders a Material 3 composer (multiline content field, a live Unicode-code-point `N/280` counter, a "Posting" CTA, an outcome-driven error banner) under `NearYouTheme`, calls the shipped `POST /api/v1/posts` through a status+`error.code`-driven `CreatePostRepository` / `CreatePostFlow` seam, and acquires the post coordinate from the already-shipped `LocationProvider` at submit time — **permission-gated** via the shipped `LocationPermissionController` so the un-guarded platform provider is never called under a denied permission (device-location-only; no map, no manual pin). PII discipline is enforced: the post-body coordinate is never logged (logging is never widened past `LogLevel.HEADERS`) and the echoed actual `latitude`/`longitude` are never rendered. Every UI string is sourced via `:shared:resources` `Res.string.*`. The Bearer token and 401 refresh are owned by the shipped `HttpClient` `Auth` plugin (never reimplemented here). This mirrors the layering of `mobile-nearby-timeline` + `mobile-age-gate` + `mobile-auth-signin`.
## Requirements
### Requirement: PostCreationScreen renders the composer surface

The mobile app SHALL ship a composable `PostCreationScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`), mapped from the `PostCreationRoute` `NavKey` by the `entryProvider`, that renders the post composer. The screen SHALL display: (a) a top-bar/title via `stringResource(Res.string.post_create_title)`; (b) a multiline content input field whose placeholder is `stringResource(Res.string.post_create_content_placeholder)`; (c) a live character counter via `stringResource(Res.string.post_create_char_counter)` formatted with the current Unicode-code-point count, positioned at the **bottom composer bar, right-aligned** (mockup frame 6 placement, per `mobile-mockup-visual-conformance`); (d) a "Posting" CTA via `stringResource(Res.string.cta_post)` that is disabled while the content is empty/over-limit/in-flight; (e) the loading / success / per-error states per the § "Screen state mapping" requirement; (f) a **location chip** below the content field styled with the `NearYouColors` reserved-purpose location tokens — `locationPinContainer` container, `onLocationPinContainer` label, `ic_post_location` glyph tinted `locationPin` (mockup frame 6 `.chip.loc`) — whose label is the static `stringResource(Res.string.post_create_location_chip)`; the chip MUST NOT render the device coordinate, a reverse-geocoded city, or any location-derived value (the composer has no geocoding capability and the PII discipline forbids rendering the actual coordinate); (g) a **privacy note** below the chip rendering `ic_privacy_shield` (tinted the `NearYouColors` `success` token) + `stringResource(Res.string.post_create_privacy_note)` in small (12sp-scale) `onSurfaceVariant` text — the UU-PDP location-fuzzing transparency surface (mockup frame 6 `.privacy-note`). The screen SHALL NOT render an attachment toolbar (the mockup's image/camera buttons are media — Month 6 roadmap; deferred, NOT part of this surface). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows title, placeholder, zero counter, disabled CTA

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a `FakeCreatePostFlow` and no text entered
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_title)` AND a field showing the `post_create_content_placeholder` text AND a counter node reflecting a count of `0` AND the "Posting" CTA is present in a disabled state

#### Scenario: Location chip and privacy note are rendered with static copy only

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a `FakeCreatePostFlow`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_location_chip)` AND a node whose text matches `stringResource(Res.string.post_create_privacy_note)` AND no node renders a numeric coordinate or city name (the chip label and note are static catalog strings)

#### Scenario: Counter renders in the bottom composer bar

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` and reads the layout bounds of the counter node and the privacy-note node (the last content element above the bottom bar)
- **THEN** the counter node's top edge is at or below the privacy note's bottom edge (the counter sits in the pinned bottom composer bar, below ALL scrollable content — strictly stronger than below-the-field, which the pre-change layout also satisfied) — asserted via Robolectric bounds comparison (the `AppShellScreenTest` bounds-math idiom)

#### Scenario: No attachment toolbar is rendered

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme`
- **THEN** the rendered tree contains NO image-attachment or camera affordance (media authoring is deferred to the media roadmap phase; this scenario is the negative guard that keeps the deferral spec-visible)

#### Scenario: No hardcoded UI strings in PostCreationScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: A home-surface FAB opens the composer; existing routing and the Nearby screen are unchanged

`HomeScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`) SHALL render a single **icon-only** `FloatingActionButton` at the **home (tab-host) level** that, when activated, appends `PostCreationRoute` to the **root** navigation back stack (above `HomeRoute`, so the composer overlays the entire surface including the `NavigationBar`). The FAB SHALL display a Material **icon** (per `mobile-design-system` § "Material 3 icons are the canonical navigation and action affordance") with a `contentDescription` sourced via `stringResource(Res.string.cta_post)`, and SHALL NOT display a visible text label — it is a `FloatingActionButton`, NOT an `ExtendedFloatingActionButton`. The FAB MUST NOT be hosted by a nested `Scaffold` inside `HomeScreen` (the single inset-owning `Scaffold` is the app section shell's, per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"). `HomeScreen` SHALL host the Nearby/Following/Global **tab host** as its body (per the `mobile-home-tab-host` capability + the `mobile-nearby-timeline` § "HomeScreen hosts NearbyTimelineScreen" requirement); the Nearby feed is rendered as the Nearby tab's content. The authenticated path SHALL continue to route to `HomeRoute` (the `mobile-auth-signin` routing **target** is unchanged). The FAB is shared across all three tabs (one composer affordance), is not duplicated per tab, and pushes onto the root back stack only. `NearbyTimelineScreen` SHALL remain **navigation-free** — it holds no back-stack reference; the FAB + root-back-stack append live at the home (tab-host) level. `NearbyTimelineScreen` MAY receive a hoisted `onSeeGlobal` lambda (the empty-state "lihat Global" tab-switch CTA from `mobile-nearby-timeline`), which is host-level tab state, NOT a back-stack reference — so the navigation-free property is preserved.

#### Scenario: HomeScreen renders an icon-only compose FAB that pushes the composer onto the root stack

- **WHEN** a test composes the `HomeScreen` tab-host composable under `NearYouTheme` over a test root back stack (or with a recording navigate-to callback) and activates the compose FAB (with any tab selected)
- **THEN** a single `FloatingActionButton` node is present, rendering a Material icon with `contentDescription` = `stringResource(Res.string.cta_post)` and NO visible text label, AND activating it appends `PostCreationRoute` to the **root** back stack (the composer surface becomes the current entry, overlaying the tab bar)

#### Scenario: The composer FAB is not an ExtendedFloatingActionButton and not in a nested Scaffold

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt`
- **THEN** the composer affordance is a `FloatingActionButton` (icon-only), NOT an `ExtendedFloatingActionButton` with a `Text` label, AND it is not hosted by a `Scaffold` declared inside `HomeScreen` (the single Scaffold is the shell's)

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** `NearbyTimelineScreen` holds no back-stack reference and contains no FAB; the FAB + back-stack append live at the home (tab-host) level. Any navigation it triggers is via a hoisted lambda (`onSeeGlobal` tab switch) — a host-level tab-state callback, NOT a back-stack push/pop

### Requirement: Create request targets POST /api/v1/posts with the device coordinate

`PostCreationApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostCreationApiClient.kt`) SHALL issue `POST /api/v1/posts` (the canonical endpoint per `openspec/specs/post-creation/spec.md`) with a JSON request body whose wire keys are exactly `content` (String), `latitude` (Double), and `longitude` (Double) — bare camelCase, matching the shipped backend `CreatePostRequestDto`, NOT snake_case. The Bearer `Authorization` header SHALL be attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment or 401 refresh). The client SHALL NOT swallow `CancellationException` (it MUST rethrow it; transport failures map to a `NetworkError` result).

#### Scenario: Request shape and method
- **GIVEN** a Ktor `MockEngine` capturing outbound requests
- **WHEN** `PostCreationApiClient.createPost(content = "halo", lat = -6.2, lng = 106.8)` runs
- **THEN** the captured request is `POST` with path `/api/v1/posts` AND its JSON body contains the keys `content`, `latitude`, `longitude` with the supplied values

#### Scenario: Body uses bare latitude/longitude (negative guard against snake_case)
- **WHEN** inspecting the `@Serializable` request DTO and the captured request body
- **THEN** the wire keys are `latitude` / `longitude` (NOT `lat` / `lng` / `actual_lat` / snake_case) so the body matches the shipped `CreatePostRequestDto` exactly

#### Scenario: Non-2xx parses the error envelope into HttpError(status, errorCode)
- **GIVEN** a `MockEngine` returning HTTP 400 `{"error":{"code":"content_too_long","message":"..."}}`
- **WHEN** `createPost(...)` runs
- **THEN** the result is `PostCreationApiResult.HttpError` carrying `status = 400` AND `errorCode = "content_too_long"` (the `{ "error": { "code" } }` envelope is parsed best-effort; a non-2xx is a value, never a thrown exception)

#### Scenario: CancellationException is rethrown, not swallowed
- **GIVEN** a `createPost(...)` call whose coroutine is cancelled mid-flight (the HTTP call throws `CancellationException`)
- **WHEN** the client's catch handling runs
- **THEN** the `CancellationException` is rethrown (structured concurrency unwinds) and is NOT mapped to `NetworkError` (mirrors `NearbyTimelineApiClient`'s cancellation discipline)

### Requirement: Success body parses minimally and no coordinate is rendered

`PostCreationApiClient` SHALL parse the 201 response into a minimal `@Serializable CreatedPostDto(id: String)` that relies on the shared `Json`'s `ignoreUnknownKeys = true` to tolerate the remaining create-response fields. The create response's `distance_m` and `created_at` are **snake_case** (unlike the Nearby timeline's camelCase `distanceM`/`createdAt`); the minimal DTO MUST NOT depend on those fields, and a code comment SHALL record that the casing asymmetry is intentional. The echoed `latitude`/`longitude` in the 201 body (the author's actual coordinate) MUST NOT be read into any rendered field.

#### Scenario: 201 create body parses the id and tolerates the snake_case fields
- **GIVEN** a `MockEngine` returning 201 with body `{ "id": "0193....-7...", "content": "halo", "latitude": -6.2, "longitude": 106.8, "distance_m": null, "created_at": "2026-06-03T00:00:00Z" }`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND `CreatedPostDto.id` is populated AND parsing does NOT fail on the snake_case `distance_m` / `created_at` keys

#### Scenario: Echoed actual coordinate is not rendered on success
- **GIVEN** a successful create whose 201 echoes `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the composer renders the success state
- **THEN** the rendered tree contains NO node whose text contains `"-6.21"` or `"106.85"` (the success path renders no coordinate)

### Requirement: Outcome mapping is HTTP-status + error.code driven with no generic fallthrough

`CreatePostRepository.submit(content)` SHALL FIRST consult `LocationPermissionController.status()` and acquire the device coordinate ONLY when permission is granted — the shipped `AndroidLocationProvider.current()` has no permission guard and can throw a synchronous `SecurityException` if called under a denied permission, so it MUST NOT be reached un-gated. The repository SHALL map each submit result to exactly one member of a sealed `PostCreationOutcome` — `Success`, `ContentEmpty`, `ContentTooLong`, `LocationOutOfBounds`, `ContentRejected`, `LocationUnavailable`, `NetworkError`, `Error` — with no generic "submit failed" wildcard fallthrough. The mapping SHALL be:
- **Permission `DENIED`** → `LocationUnavailable` immediately; NO `current()` call and NO `POST` is issued (the OS shows nothing for a terminal denial; the "Buka Pengaturan" CTA is the path forward).
- **Permission `NOT_DETERMINED`** → call `LocationPermissionController.request()` (the contextual OS prompt); if the result is not `GRANTED` → `LocationUnavailable` (no `POST`); if `GRANTED` → continue as the granted path.
- **Permission `GRANTED`** → acquire `LocationProvider.current()`; if it throws `LocationUnavailableException` (the granted-but-no-fix path) → `LocationUnavailable` (no `POST`); otherwise issue `POST /api/v1/posts` and map its result:
  - **HTTP 201** → `Success` (carrying the parsed `id`). A `Verdict.Flag` (UU-ITE soft flag) post is returned by the backend as a normal 201 and SHALL be treated as `Success` (it is not an error; the row was created and silently queued).
  - **HTTP 400** → keyed on the parsed `error.code`: `content_empty` → `ContentEmpty`, `content_too_long` → `ContentTooLong`, `location_out_of_bounds` → `LocationOutOfBounds`, `content_moderated_profanity` → `ContentRejected`; any other/absent 400 code → a retryable `Error` with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
  - **HTTP 401** → handled upstream by the shipped Ktor `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` → `SignInScreen`); the repository MUST NOT reimplement 401 refresh/retry.
  - **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).

#### Scenario: 201 maps to Success carrying the id
- **GIVEN** a `MockEngine` returning 201 with a valid `CreatedPostDto` body
- **WHEN** the repository processes the response
- **THEN** the outcome is `Success` exposing the parsed `id`

#### Scenario: 400 content_empty maps to ContentEmpty
- **GIVEN** a `MockEngine` returning 400 `{"error":{"code":"content_empty"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `ContentEmpty`

#### Scenario: 400 content_too_long maps to ContentTooLong
- **GIVEN** a `MockEngine` returning 400 `{"error":{"code":"content_too_long"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `ContentTooLong`

#### Scenario: 400 location_out_of_bounds maps to LocationOutOfBounds
- **GIVEN** a `MockEngine` returning 400 `{"error":{"code":"location_out_of_bounds"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `LocationOutOfBounds`

#### Scenario: 400 content_moderated_profanity maps to ContentRejected
- **GIVEN** a `MockEngine` returning 400 `{"error":{"code":"content_moderated_profanity"}}`
- **WHEN** the repository processes the response
- **THEN** the outcome is `ContentRejected`

#### Scenario: Unknown/absent 400 code maps to retryable Error with a logged diagnostic
- **GIVEN** a `MockEngine` returning 400 `{"error":{"code":"invalid_json"}}` (or a 400 with no parseable `error.code`)
- **WHEN** the repository processes the response
- **THEN** the outcome is the retryable `Error` AND a diagnostic is emitted to logs (NOT a silent no-op, NOT a crash)

#### Scenario: 5xx / network-IO maps to NetworkError
- **GIVEN** a `MockEngine` returning bare HTTP 500 (or throwing `IOException`)
- **WHEN** the repository processes the result
- **THEN** the outcome is `NetworkError` AND no crash occurs

#### Scenario: Denied permission short-circuits before any coordinate acquisition or POST
- **GIVEN** a `FakeLocationPermissionController` reporting `DENIED` AND a `LocationProvider` counting `current()` calls AND a `MockEngine` counting requests
- **WHEN** the repository runs `submit("halo")`
- **THEN** the outcome is `LocationUnavailable` AND `current()` was called ZERO times (the un-guarded `getCurrentLocation` is never reached) AND the `MockEngine` recorded ZERO requests

#### Scenario: Not-determined permission triggers the OS prompt then proceeds on grant
- **GIVEN** a `FakeLocationPermissionController(current = NOT_DETERMINED, afterRequest = GRANTED)` AND a `LocationProvider` returning a coordinate AND a `MockEngine` returning 201
- **WHEN** the repository runs `submit("halo")`
- **THEN** `request()` was invoked exactly once AND `current()` was acquired AND the `POST` was issued AND the outcome is `Success`

#### Scenario: Granted-but-no-fix maps to LocationUnavailable without a POST
- **GIVEN** a `FakeLocationPermissionController` reporting `GRANTED` AND a `LocationProvider` whose `current()` throws `LocationUnavailableException` AND a `MockEngine` counting requests
- **WHEN** the repository runs `submit("halo")`
- **THEN** the outcome is `LocationUnavailable` AND the `MockEngine` recorded ZERO requests (no `POST` was issued)

#### Scenario: Every submit result maps to exactly one outcome
- **WHEN** inspecting the repository result mapping and the `PostCreationOutcome` sealed type
- **THEN** each of permission `DENIED`, permission `NOT_DETERMINED`-then-not-granted, granted-but-no-fix, HTTP 201, each enumerated 400 `error.code`, an unknown 400, HTTP 5xx, and network/IO failure maps to exactly one `PostCreationOutcome` member; there is NO `else`/wildcard branch emitting a generic "submit failed" copy (401 is delegated to the shipped `Auth` plugin, not mapped here)

### Requirement: Pure PostCreationUiState projection with a code-point length gate

The mobile app SHALL model the screen state as a Compose-free `PostCreationUiState` and a pure projection function `postCreationUiState(content: String, outcome: PostCreationOutcome?, inFlight: Boolean): PostCreationUiState` (mirroring `mobile-age-gate`'s `AgeGateUiState`) so the state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL: (a) compute the character count as the number of **Unicode code points** in `content` (NOT UTF-16 code units); (b) mark the submit CTA enabled if and only if the content has ≥1 non-blank code point AND ≤280 code points AND `inFlight` is false; (c) expose an over-limit flag when the count exceeds 280; (d) derive `Loading` while `inFlight`; (e) derive `Success` / the specific error-banner variant from the `outcome`. The projection MUST carry no PII (no coordinate, no author id) and no wall-clock/platform dependency.

#### Scenario: Empty or whitespace-only content disables submit
- **WHEN** the projection is invoked with `content = ""` and again with `content = "   "` (`inFlight = false`, `outcome = null`)
- **THEN** in both cases the submit CTA is disabled AND the over-limit flag is false

#### Scenario: 280 code points enabled, 281 over-limit and disabled
- **WHEN** the projection is invoked with a 280-code-point string and again with a 281-code-point string (`inFlight = false`)
- **THEN** the 280-code-point case has the submit CTA enabled and over-limit false AND the 281-code-point case has the submit CTA disabled and over-limit true

#### Scenario: Multi-byte emoji counts as one code point
- **GIVEN** a string of 280 repetitions of a non-BMP emoji (each a single Unicode code point but two UTF-16 units)
- **WHEN** the projection computes the count
- **THEN** the count is 280 (NOT 560) AND the submit CTA is enabled; a 281-emoji string yields count 281, over-limit true, CTA disabled

#### Scenario: In-flight yields Loading with submit disabled
- **WHEN** the projection is invoked with a valid 5-code-point string and `inFlight = true`
- **THEN** the state is `Loading` AND the submit CTA is disabled

#### Scenario: Each outcome maps to its state
- **WHEN** the projection is invoked (not in-flight) for `Success`, `ContentEmpty`, `ContentTooLong`, `LocationOutOfBounds`, `ContentRejected`, `LocationUnavailable`, `NetworkError`, and `Error`
- **THEN** each call returns the corresponding success / per-error banner state deterministically (no wall-clock or platform dependency)

### Requirement: Screen state mapping covers loading, success, and each error, all copy via stringResource

The screen SHALL render the projected state with all copy via `stringResource`:
- **Loading** (`inFlight`) → the CTA shows `stringResource(Res.string.post_create_loading)` and is disabled.
- **Success** → the screen removes its own entry from the back stack (`backStack.removeLastOrNull()`, the Nav3 equivalent of pop) to return to the home surface; no coordinate is rendered.
- **ContentEmpty** → a banner with `stringResource(Res.string.post_create_error_empty)`.
- **ContentTooLong** → a banner with `stringResource(Res.string.post_create_error_too_long)`.
- **LocationOutOfBounds** → a banner with `stringResource(Res.string.post_create_error_location)`.
- **ContentRejected** → a banner with `stringResource(Res.string.post_create_error_moderated)` (generic; MUST NOT echo any matched keyword).
- **LocationUnavailable** → a banner with `stringResource(Res.string.post_create_location_unavailable)` AND a "Buka Pengaturan" control with `stringResource(Res.string.location_open_settings)` that invokes `LocationPermissionController.openAppSettings()`.
- **NetworkError / Error** → a banner with `stringResource(Res.string.signin_error_network)` AND a retry control with `stringResource(Res.string.cta_retry)`.

#### Scenario: Loading shows the loading copy and a disabled CTA
- **WHEN** the screen is in the in-flight state
- **THEN** the CTA node's text matches `stringResource(Res.string.post_create_loading)` AND the CTA is disabled

#### Scenario: ContentRejected shows the keyword-free moderation copy
- **WHEN** the outcome is `ContentRejected`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_error_moderated)` AND contains no matched-keyword substring

#### Scenario: LocationUnavailable shows enable-location copy and a settings CTA
- **GIVEN** the outcome is `LocationUnavailable`
- **WHEN** the screen renders AND the "Buka Pengaturan" control is activated
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_location_unavailable)` AND activating the control invokes `LocationPermissionController.openAppSettings()`

#### Scenario: NetworkError shows network copy and a retry control
- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Success pops back to the home surface
- **GIVEN** a `FakeCreatePostFlow` returning `Success`
- **WHEN** the composer submits successfully within a `NavDisplay` over a test back stack (or with a recording pop callback)
- **THEN** the composer's entry is removed from the back stack (`backStack.removeLastOrNull()`) and the home surface becomes current again

### Requirement: Post location is automatic-only (device-acquired); no manual selection

The composer SHALL set the post coordinate SOLELY from the device location provider (`LocationProvider.current()`, permission-gated per the § "Outcome mapping is HTTP-status + error.code driven" requirement). It MUST NOT present any UI to manually choose or adjust the post location — no map view, no draggable pin, no manual coordinate-entry field, no place search. Per `docs/02-Product.md` § 2 Post System — "automatic location (device-acquired GPS; no manual selection)", narrowed to automatic-only by [#144](https://github.com/aditrioka/nearyou-id/pull/144) — manual location selection is **out of product scope**: it is NOT a deferred feature and has NO follow-up change. (The backend `POST /api/v1/posts` still accepts client-supplied `{lat,lng}` and cannot distinguish auto from manual; automatic-only is a client/UX decision per #144, not an API-level change.)

#### Scenario: The submitted coordinate is the device fix
- **GIVEN** a fake `LocationProvider` returning a known coordinate AND a granted permission
- **WHEN** a successful submit issues the `POST /api/v1/posts`
- **THEN** the request `latitude`/`longitude` equal the values returned by `LocationProvider.current()` (the device fix), NOT any user-entered or map-selected coordinate

#### Scenario: No manual-location affordance is present
- **WHEN** inspecting `PostCreationScreen.kt` and the composer's components
- **THEN** there is NO map view, draggable-pin, manual coordinate-entry field, or place-search affordance for choosing the post location (automatic-only per `docs/02-Product.md` § 2)

### Requirement: Successful post returns to Home; Nearby auto-refresh on return is deferred

On a `Success` outcome the composer SHALL remove its own entry from the back stack (`backStack.removeLastOrNull()`, the Nav3 equivalent of pop) to return to the home surface, and SHALL NOT signal the Nearby feed to re-fetch; the newly-created post becomes visible on the next manual pull-to-refresh / `ON_RESUME`. Cross-screen auto-refresh-on-return is NOT implemented in this change and is deferred to a follow-up tracked by GitHub issue [#173](https://github.com/aditrioka/nearyou-id/issues/173) `mobile-post-creation-refresh-nearby-on-return` (label `follow-up`).

#### Scenario: No Nearby reload is signalled on success

- **WHEN** inspecting the composer's `Success` handling
- **THEN** it removes the composer entry from the back stack (`backStack.removeLastOrNull()`) AND does NOT invoke any Nearby reload / re-fetch trigger (no shared reload signal, and no Nav3 `ResultEventBus` / nav result consumed by the Nearby feed)

#### Scenario: Follow-up issue tracks the Nearby-refresh follow-up

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#173](https://github.com/aditrioka/nearyou-id/issues/173) (label `follow-up`) tracks `mobile-post-creation-refresh-nearby-on-return`

### Requirement: The post-body coordinate is never logged and logging is not widened

The post coordinate travels in the `POST /api/v1/posts` request **body**. This change MUST keep the shipped `HttpClientFactory` logging at `LogLevel.HEADERS` (request/response bodies are not logged at HEADERS) and MUST NOT widen it to `LogLevel.BODY` or `LogLevel.ALL`. `PostCreationApiClient` and `CreatePostRepository` MUST NOT `println`/log the coordinate or the serialized request body. (The existing `CoordinateMaskingLogger` masks only URL query parameters; the body is protected by not widening the level.)

#### Scenario: Log level is unchanged
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt` after this change
- **THEN** the `Logging` plugin level remains `LogLevel.HEADERS` (NOT `BODY`/`ALL`) and the `Authorization` `sanitizeHeader` + `CoordinateMaskingLogger` wrapper are retained

#### Scenario: The create client does not log the body or coordinate
- **WHEN** inspecting `PostCreationApiClient.kt` and `CreatePostRepository.kt`
- **THEN** neither logs/`println`s the request body, the `latitude`/`longitude`, nor the serialized payload AND the `CreatePostRepository`'s `diagnosticLog` sink signature is coordinate-free by construction (it accepts only `status`/`errorCode`-style primitives — never `content` or the `LatLng`), so the no-coordinate-logging discipline is structural, not merely review-enforced

### Requirement: Koin wiring behind the CreatePostFlow seam

`PostCreationApiClient` and `CreatePostRepository` SHALL be registered as singletons in the commonMain Koin `mobileModule`, and `CreatePostRepository` SHALL be bound behind a `CreatePostFlow` interface (`single<CreatePostFlow> { get<CreatePostRepository>() }`) so a `FakeCreatePostFlow` can drive the screen tests (mirroring `mobile-nearby-timeline`'s `NearbyTimelineFlow` seam and `mobile-auth-signin`'s `AuthFlow` seam). The repository SHALL consume the platform-bound `LocationProvider` AND the platform-bound `LocationPermissionController` (both provided by each `platformModule` from `mobile-location-permission-flow`) and the shared `HttpClient` — it MUST NOT introduce a new `LocationProvider` or `LocationPermissionController` binding.

#### Scenario: mobileModule registers the create graph behind the flow interface
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `PostCreationApiClient` and `CreatePostRepository` AND binds `single<CreatePostFlow> { get<CreatePostRepository>() }` AND the repository resolves the existing platform `LocationProvider`, the existing platform `LocationPermissionController`, and the shared `HttpClient` (no new `LocationProvider` / `LocationPermissionController` binding is introduced)

### Requirement: New Bahasa Indonesia composer strings are declared in :shared:resources

The change SHALL add the composer strings to `shared/resources/src/commonMain/composeResources/values/strings.xml` and reference each via the generated `Res.string.*` accessor (the CMP Resources codegen emits an accessor only for a declared `<string>`, so a missing key fails to compile). The composer key set owned by this requirement SHALL be (12 total): `post_create_title`, `post_create_content_placeholder`, `post_create_char_counter` (declared `formatted="true"`, taking the integer count), `cta_post`, `post_create_loading`, `post_create_error_empty`, `post_create_error_too_long`, `post_create_error_location`, `post_create_error_moderated`, `post_create_location_unavailable`, and — added by `mobile-mockup-visual-conformance` — `post_create_location_chip` (value `"Lokasi saat ini"`, the composer chip's static label) and `post_create_privacy_note` (value `"Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain"`, the UU-PDP fuzzing-transparency note; "hingga ±5 km" is the canonical user-facing obfuscation framing per the `distance-rendering` spec's 5 km display floor + the `coordinate-jitter` spec's 50–500 m envelope, phrased with the mockup board's onboarding "hingga" qualifier so the copy is not a precise obfuscation-magnitude claim). (The catalog additionally carries `post_create_error_rate_limited`, shipped outside this requirement's set — pre-existing drift tracked by a follow-up issue, not governed here.) The `post_create_error_moderated` copy MUST use the canonical backend rejection wording from `openspec/specs/post-creation/spec.md` § "Verdict.Reject" — `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` — which is generic and contains no moderation keyword. `SharedStringsCatalogTest` SHALL be extended to reference every new accessor and its tracked-accessor count assertion updated from 86 to **88** (the test asserts the size of its referenced-accessor list, NOT the raw catalog size — 14 catalog keys are untracked pre-existing drift, out of scope here; reconcile the literal if another in-flight change lands first).

#### Scenario: All composer string keys are declared and accessible

- **WHEN** `SharedStringsCatalogTest` (which references each composer `Res.string.*` accessor by name, including `post_create_location_chip` and `post_create_privacy_note`) is compiled and run
- **THEN** it compiles (every referenced key exists in `strings.xml`) AND its tracked-accessor count assertion equals 88 (the prior 86 plus the 2 new keys)

#### Scenario: The privacy-note copy states the canonical fuzzing floor

- **WHEN** inspecting the value of `post_create_privacy_note` in `strings.xml`
- **THEN** it equals `"Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain"` — the user-facing obfuscation framing canonical to the `distance-rendering` spec's 5 km display floor (with the `coordinate-jitter` 50–500 m envelope underneath), softened with the board's "hingga" qualifier, and consistent with the shipped `location_consent_body` promise, with no coordinate placeholder or parameterization

#### Scenario: The moderation-rejection copy is canonical and omits the matched keyword

- **WHEN** inspecting the value of `post_create_error_moderated` in `strings.xml`
- **THEN** it equals the canonical backend rejection wording `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` — a generic Bahasa Indonesia message with no specific profanity/keyword token (matching the backend's keyword-omission discipline)

### Requirement: Test coverage for the screen, projection, and networking

The change SHALL ship: (1) a Robolectric `PostCreationScreenTest` (`mobile/app/src/androidUnitTest/...`) covering initial render (title, placeholder, `0/280`, disabled CTA), the location chip + privacy note presence (static catalog copy, no coordinate/city), the counter's bottom-bar placement (bounds comparison per the § "Counter renders in the bottom composer bar" scenario), the absence of an attachment toolbar, typing enabling the CTA, a 281-code-point string disabling the CTA, each error-banner state via a `FakeCreatePostFlow`, and the success→pop behavior — added to the `mobile/app/build.gradle.kts` Release-variant `*ScreenTest` test-exclude list (the `ui-test-manifest` host activity is debug-only); (2) a commonTest `PostCreationUiStateTest` for the pure projection (incl. the code-point counting and the over-limit gate); (3) MockEngine-backed `PostCreationApiClient` / `CreatePostRepository` tests verifying the endpoint path, the bare-`latitude`/`longitude` body shape, 201→`Success`, each 400 `error.code`→outcome, the unknown-400 diagnostic, 5xx/IO→`NetworkError`, and `LocationUnavailableException`→`LocationUnavailable` (no POST issued).

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest` (the module is flavored; the unflavored `testDebugUnitTest` task does not exist)
- **THEN** `PostCreationScreenTest`, `PostCreationUiStateTest`, and the `PostCreationApiClient`/`CreatePostRepository` MockEngine tests are discovered AND each documented state / mapping corresponds to at least one `@Test` (including the chip + privacy-note render coverage and the counter bounds assertion)

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the `tasks.withType<Test>()` Release-variant exclude block lists `**/PostCreationScreenTest*` alongside the existing `*ScreenTest` exclusions, and `./gradlew :mobile:app:testDevReleaseUnitTest` does not attempt to run it

