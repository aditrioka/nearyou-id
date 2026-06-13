# mobile-post-creation — delta for mobile-mockup-visual-conformance

## MODIFIED Requirements

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
