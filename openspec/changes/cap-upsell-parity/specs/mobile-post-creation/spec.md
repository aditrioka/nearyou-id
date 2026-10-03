## MODIFIED Requirements

### Requirement: Outcome mapping is HTTP-status + error.code driven with no generic fallthrough

`CreatePostRepository.submit(content)` SHALL FIRST consult `LocationPermissionController.status()` and acquire the device coordinate ONLY when permission is granted — the shipped `AndroidLocationProvider.current()` has no permission guard and can throw a synchronous `SecurityException` if called under a denied permission, so it MUST NOT be reached un-gated. The repository SHALL map each submit result to exactly one member of a sealed `PostCreationOutcome` — `Success`, `ContentEmpty`, `ContentTooLong`, `LocationOutOfBounds`, `ContentRejected`, `LocationUnavailable`, `RateLimited`, `NetworkError`, `Error` — with no generic "submit failed" wildcard fallthrough. The mapping SHALL be:
- **Permission `DENIED`** → `LocationUnavailable` immediately; NO `current()` call and NO `POST` is issued (the OS shows nothing for a terminal denial; the "Buka Pengaturan" CTA is the path forward).
- **Permission `NOT_DETERMINED`** → call `LocationPermissionController.request()` (the contextual OS prompt); if the result is not `GRANTED` → `LocationUnavailable` (no `POST`); if `GRANTED` → continue as the granted path.
- **Permission `GRANTED`** → acquire `LocationProvider.current()`; if it throws `LocationUnavailableException` (the granted-but-no-fix path) → `LocationUnavailable` (no `POST`); otherwise issue `POST /api/v1/posts` and map its result:
  - **HTTP 201** → `Success` (carrying the parsed `id`). A `Verdict.Flag` (UU-ITE soft flag) post is returned by the backend as a normal 201 and SHALL be treated as `Success` (it is not an error; the row was created and silently queued).
  - **HTTP 400** → keyed on the parsed `error.code`: `content_empty` → `ContentEmpty`, `content_too_long` → `ContentTooLong`, `location_out_of_bounds` → `LocationOutOfBounds`, `content_moderated_profanity` → `ContentRejected`; any other/absent 400 code → a retryable `Error` with a diagnostic emitted to logs (NOT a silent no-op, NOT a crash).
  - **HTTP 401** → handled upstream by the shipped Ktor `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` → `SignInScreen`); the repository MUST NOT reimplement 401 refresh/retry.
  - **HTTP 429** (the Free 10/day post cap — `post-creation` § "Daily post cap"; Premium skips the limiter, so a `429` is a Free cap hit) → `RateLimited(retryAfterSeconds)`, carrying the `Retry-After` seconds parsed by `PostCreationApiClient` onto its `HttpError` (absent/unparseable → `0`). It is a distinct, non-retryable outcome — a retry cannot succeed before the daily reset — and MUST NOT fall into `NetworkError`/`Error`.
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

#### Scenario: 429 maps to RateLimited carrying Retry-After
- **GIVEN** a `MockEngine` returning 429 `{"error":{"code":"rate_limited"}}` with `Retry-After: 51540`
- **WHEN** the repository processes the response
- **THEN** the outcome is `RateLimited(retryAfterSeconds = 51540)` AND NOT `NetworkError`; AND a 429 with no `Retry-After` yields `RateLimited(0)`

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
- **THEN** each of permission `DENIED`, permission `NOT_DETERMINED`-then-not-granted, granted-but-no-fix, HTTP 201, each enumerated 400 `error.code`, an unknown 400, HTTP 429, HTTP 5xx, and network/IO failure maps to exactly one `PostCreationOutcome` member; there is NO `else`/wildcard branch emitting a generic "submit failed" copy (401 is delegated to the shipped `Auth` plugin, not mapped here)

### Requirement: Pure PostCreationUiState projection with a code-point length gate

The mobile app SHALL model the screen state as a Compose-free `PostCreationUiState` and a pure projection function `postCreationUiState(content: String, outcome: PostCreationOutcome?, inFlight: Boolean): PostCreationUiState` (mirroring `mobile-age-gate`'s `AgeGateUiState`) so the state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL: (a) compute the character count as the number of **Unicode code points** in `content` (NOT UTF-16 code units); (b) mark the submit CTA enabled if and only if the content has ≥1 non-blank code point AND ≤280 code points AND `inFlight` is false; (c) expose an over-limit flag when the count exceeds 280; (d) derive `Loading` while `inFlight`; (e) derive `Success` / the specific error-banner variant from the `outcome` — EXCEPT `RateLimited`, which projects NO banner (the daily cap is surfaced by the cap dialog, § "Screen state mapping", never by a second inline surface). The projection MUST carry no PII (no coordinate, no author id) and no wall-clock/platform dependency.

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

#### Scenario: RateLimited projects no banner
- **WHEN** the projection is invoked (not in-flight) with a valid content string and `RateLimited(retryAfterSeconds = 60)`
- **THEN** the banner is `null` AND success is false AND the submit CTA stays enabled for the valid content

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
- **RateLimited** (the Free 10/day post cap) → the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, mockup frame 18) with the body `stringResource(Res.string.post_create_cap_upsell)` formatted with the live countdown derived from `retryAfterSeconds` — NOT an inline banner. The typed content SHALL be preserved. The dialog's visibility is the `PostCreationViewModel`'s nullable `createOutcome` (docs/11 §2.2); "Tutup" / scrim / back / auto-dismiss SHALL clear it via `onCapDialogDismissed()`; "Aktifkan Premium" SHALL clear it AND invoke the screen's hoisted `onActivatePremium(PaywallEntry.POST_CAP)`, which `appEntryProvider` wires to push `PaywallRoute(POST_CAP)` (the same hoisted callback carries `PaywallEntry.IMAGE_ATTACH` for the Free image-attach gate, `mobile-image-attachment`). The composer stays navigation-free.

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

#### Scenario: RateLimited shows the post cap dialog and keeps the draft
- **GIVEN** a `FakeCreatePostFlow` returning `RateLimited(retryAfterSeconds = 1140)`
- **WHEN** the user types "halo" and submits
- **THEN** the `DailyCapUpsellDialog` is shown whose body matches `stringResource(Res.string.post_create_cap_upsell)` formatted with "19 mnt" AND no inline rate-limit banner is rendered AND the content field still contains "halo"

#### Scenario: Dismissing the post cap dialog clears the outcome
- **GIVEN** the post cap dialog is shown
- **WHEN** "Tutup" is tapped
- **THEN** the dialog disappears AND the ViewModel's `createOutcome` is `null` AND the hoisted `onActivatePremium` is not invoked

#### Scenario: The post cap CTA opens the paywall as POST_CAP
- **GIVEN** the post cap dialog shown on the composer under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.POST_CAP)`

#### Scenario: Success pops back to the home surface
- **GIVEN** a `FakeCreatePostFlow` returning `Success`
- **WHEN** the composer submits successfully within a `NavDisplay` over a test back stack (or with a recording pop callback)
- **THEN** the composer's entry is removed from the back stack (`backStack.removeLastOrNull()`) and the home surface becomes current again

### Requirement: New Bahasa Indonesia composer strings are declared in :shared:resources

The change SHALL add the composer strings to `shared/resources/src/commonMain/composeResources/values/strings.xml` and reference each via the generated `Res.string.*` accessor (the CMP Resources codegen emits an accessor only for a declared `<string>`, so a missing key fails to compile). The composer key set owned by this requirement SHALL be (12 total): `post_create_title`, `post_create_content_placeholder`, `post_create_char_counter` (declared `formatted="true"`, taking the integer count), `cta_post`, `post_create_loading`, `post_create_error_empty`, `post_create_error_too_long`, `post_create_error_location`, `post_create_error_moderated`, `post_create_location_unavailable`, and — added by `mobile-mockup-visual-conformance` — `post_create_location_chip` (value `"Lokasi saat ini"`, the composer chip's static label) and `post_create_privacy_note` (value `"Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain"`, the UU-PDP fuzzing-transparency note; "hingga ±5 km" is the canonical user-facing obfuscation framing per the `distance-rendering` spec's 5 km display floor + the `coordinate-jitter` spec's 50–500 m envelope, phrased with the mockup board's onboarding "hingga" qualifier so the copy is not a precise obfuscation-magnitude claim). (The composer's daily-cap dialog body `post_create_cap_upsell` is owned by § "Screen state mapping" and the `mobile-cap-upsell-dialog` capability, not this set. The former drift key `post_create_error_rate_limited` — the inline rate-limit banner the cap dialog replaced — is removed from the catalog.) The `post_create_error_moderated` copy MUST use the canonical backend rejection wording from `openspec/specs/post-creation/spec.md` § "Verdict.Reject" — `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` — which is generic and contains no moderation keyword. `SharedStringsCatalogTest` SHALL reference every composer accessor; its tracked-accessor list SHALL cover every declared `<string>` in the catalog with no duplicates, and its size assertion SHALL equal the declared-string count (a change that adds or removes a key updates the list and the count together).

#### Scenario: All composer string keys are declared and accessible

- **WHEN** `SharedStringsCatalogTest` (which references each composer `Res.string.*` accessor by name, including `post_create_location_chip` and `post_create_privacy_note`) is compiled and run
- **THEN** it compiles (every referenced key exists in `strings.xml`) AND its tracked-accessor list covers every declared `<string>` with no duplicates (the size assertion equals the declared-string count)

#### Scenario: The privacy-note copy states the canonical fuzzing floor

- **WHEN** inspecting the value of `post_create_privacy_note` in `strings.xml`
- **THEN** it equals `"Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain"` — the user-facing obfuscation framing canonical to the `distance-rendering` spec's 5 km display floor (with the `coordinate-jitter` 50–500 m envelope underneath), softened with the board's "hingga" qualifier, and consistent with the shipped `location_consent_body` promise, with no coordinate placeholder or parameterization

#### Scenario: The moderation-rejection copy is canonical and omits the matched keyword

- **WHEN** inspecting the value of `post_create_error_moderated` in `strings.xml`
- **THEN** it equals the canonical backend rejection wording `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` — a generic Bahasa Indonesia message with no specific profanity/keyword token (matching the backend's keyword-omission discipline)

#### Scenario: The retired rate-limit banner key is gone
- **WHEN** inspecting `strings.xml` and `PostCreationUiState.kt`
- **THEN** there is no `post_create_error_rate_limited` key AND `PostCreationBanner` declares no rate-limit member AND `post_create_cap_upsell` is declared
