## MODIFIED Requirements

### Requirement: PostCreationScreen renders the composer surface

The mobile app SHALL ship a composable `PostCreationScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`), mapped from the `PostCreationRoute` `NavKey` by the `entryProvider`, that renders the post composer. The screen SHALL display: (a) a top-bar/title via `stringResource(Res.string.post_create_title)`; (b) a multiline content input field whose placeholder is `stringResource(Res.string.post_create_content_placeholder)`; (c) a live character counter via `stringResource(Res.string.post_create_char_counter)` formatted with the current Unicode-code-point count, positioned at the **bottom composer bar, right-aligned** (mockup frame 6 placement, per `mobile-mockup-visual-conformance`); (d) a "Posting" CTA via `stringResource(Res.string.cta_post)` that is disabled while the content is empty/over-limit/in-flight **or while an image upload is in flight** (per `mobile-image-attachment`); (e) the loading / success / per-error states per the § "Screen state mapping" requirement; (f) a **location chip** below the content field styled with the `NearYouColors` reserved-purpose location tokens — `locationPinContainer` container, `onLocationPinContainer` label, `ic_post_location` glyph tinted `locationPin` (mockup frame 6 `.chip.loc`) — whose label is the static `stringResource(Res.string.post_create_location_chip)`; the chip MUST NOT render the device coordinate, a reverse-geocoded city, or any location-derived value (the composer has no geocoding capability and the PII discipline forbids rendering the actual coordinate); (g) a **privacy note** below the chip rendering `ic_privacy_shield` (tinted the `NearYouColors` `success` token) + `stringResource(Res.string.post_create_privacy_note)` in small (12sp-scale) `onSurfaceVariant` text — the UU-PDP location-fuzzing transparency surface (mockup frame 6 `.privacy-note`). The screen SHALL render a **Premium image-attach affordance** (the mockup frame 6 image button, now activated) whose gating, picker, upload, and preview/remove mechanics are governed by the `mobile-image-attachment` capability — Premium viewers reach the picker; Free viewers reach the upsell. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Initial render shows title, placeholder, zero counter, disabled CTA

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a `FakeCreatePostFlow` and no text entered
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_title)` AND a field showing the `post_create_content_placeholder` text AND a counter node reflecting a count of `0` AND the "Posting" CTA is present in a disabled state

#### Scenario: Location chip and privacy note are rendered with static copy only

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a `FakeCreatePostFlow`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_create_location_chip)` AND a node whose text matches `stringResource(Res.string.post_create_privacy_note)` AND no node renders a numeric coordinate or city name (the chip label and note are static catalog strings)

#### Scenario: Counter renders in the bottom composer bar

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` and reads the layout bounds of the counter node and the privacy-note node (the last content element above the bottom bar)
- **THEN** the counter node's top edge is at or below the privacy note's bottom edge (the counter sits in the pinned bottom composer bar, below ALL scrollable content — strictly stronger than below-the-field, which the pre-change layout also satisfied) — asserted via Robolectric bounds comparison (the `AppShellScreenTest` bounds-math idiom)

#### Scenario: A Premium image-attach affordance is rendered

- **WHEN** a test composes the `PostCreationScreen` composable under `NearYouTheme` with a Premium viewer
- **THEN** the rendered tree contains an image-attach affordance (the activated mockup frame 6 image button), whose picker/upload/gating behavior is asserted by the `mobile-image-attachment` capability tests (this requirement only pins that the composer surfaces the affordance, replacing the prior no-attachment-toolbar negative guard)

#### Scenario: No hardcoded UI strings in PostCreationScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Create request targets POST /api/v1/posts with the device coordinate

`PostCreationApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostCreationApiClient.kt`) SHALL issue `POST /api/v1/posts` (the canonical endpoint per `openspec/specs/post-creation/spec.md`) with a JSON request body whose wire keys are exactly `content` (String), `latitude` (Double), and `longitude` (Double) — bare camelCase, matching the shipped backend `CreatePostRequestDto`, NOT snake_case — plus an **optional** `image_id` (String, `@SerialName("image_id")`, snake_case to match the shipped `CreatePostRequestDto.imageId`) that is **present only when an image is attached** (omitted/null for a text-only post). The Bearer `Authorization` header SHALL be attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment or 401 refresh). The client SHALL NOT swallow `CancellationException` (it MUST rethrow it; transport failures map to a `NetworkError` result).

#### Scenario: Request shape and method
- **GIVEN** a Ktor `MockEngine` capturing outbound requests
- **WHEN** `PostCreationApiClient.createPost(content = "halo", lat = -6.2, lng = 106.8)` runs
- **THEN** the captured request is `POST` with path `/api/v1/posts` AND its JSON body contains the keys `content`, `latitude`, `longitude` with the supplied values

#### Scenario: Body uses bare latitude/longitude (negative guard against snake_case)
- **WHEN** inspecting the `@Serializable` request DTO and the captured request body
- **THEN** the wire keys are `latitude` / `longitude` (NOT `lat` / `lng` / `actual_lat` / snake_case) so the body matches the shipped `CreatePostRequestDto` exactly

#### Scenario: image_id is included only when an image is attached
- **GIVEN** a `MockEngine` capturing outbound requests
- **WHEN** `createPost(...)` runs once with an attached `imageId = "abc"` and once with no image
- **THEN** the first body contains `"image_id":"abc"` (snake_case wire key) AND the second body omits `image_id` (or serializes it as absent/null) — a text-only post is byte-identical to the pre-change body

#### Scenario: Non-2xx parses the error envelope into HttpError(status, errorCode)
- **GIVEN** a `MockEngine` returning HTTP 400 `{"error":{"code":"content_too_long","message":"..."}}`
- **WHEN** `createPost(...)` runs
- **THEN** the result is `PostCreationApiResult.HttpError` carrying `status = 400` AND `errorCode = "content_too_long"` (the `{ "error": { "code" } }` envelope is parsed best-effort; a non-2xx is a value, never a thrown exception)

#### Scenario: CancellationException is rethrown, not swallowed
- **GIVEN** a `createPost(...)` call whose coroutine is cancelled mid-flight (the HTTP call throws `CancellationException`)
- **WHEN** the client's catch handling runs
- **THEN** the `CancellationException` is rethrown (structured concurrency unwinds) and is NOT mapped to `NetworkError` (mirrors `NearbyTimelineApiClient`'s cancellation discipline)
