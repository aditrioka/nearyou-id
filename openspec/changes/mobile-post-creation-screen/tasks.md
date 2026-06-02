## 1. Networking — PostCreationApiClient

- [ ] 1.1 Add `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostCreationApiClient.kt`: a `@Serializable CreatePostRequestDto(content: String, latitude: Double, longitude: Double)` (bare camelCase keys, matching the shipped backend `CreatePostRequestDto`).
- [ ] 1.2 Add a minimal `@Serializable CreatedPostDto(id: String)` for the 201 body (relies on the shared `Json` `ignoreUnknownKeys`); add a code comment recording that `distance_m`/`created_at` are snake_case on THIS create response (vs the Nearby timeline's camelCase) and that the minimal DTO intentionally ignores them.
- [ ] 1.3 Add an `@Serializable` error envelope DTO (`{ "error": { "code", "message" } }`) sufficient to read `error.code` from a 400 body.
- [ ] 1.4 Define `sealed interface PostCreationApiResult { Success(id: String); HttpError(status: Int, errorCode: String?); NetworkError(cause: Throwable) }`.
- [ ] 1.5 Implement `suspend fun createPost(content: String, lat: Double, lng: Double): PostCreationApiResult` issuing `POST /api/v1/posts` over the shared `HttpClient`: 201 → parse `CreatedPostDto` → `Success`; non-2xx → parse the error envelope (best-effort) → `HttpError(status, errorCode)`; transport failure → `NetworkError`. Rethrow `CancellationException` (never swallow it). MUST NOT attach the Bearer token or handle 401 (the `Auth` plugin owns both) and MUST NOT log the body/coordinate.

## 2. Repository + outcome mapping

- [ ] 2.1 Add `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostCreationOutcome.kt`: `sealed interface PostCreationOutcome { Success(id: String); ContentEmpty; ContentTooLong; LocationOutOfBounds; ContentRejected; LocationUnavailable; NetworkError; Error }`.
- [ ] 2.2 Add a `CreatePostFlow` interface (`suspend fun submit(content: String): PostCreationOutcome`) — the testable seam mirroring `NearbyTimelineFlow`.
- [ ] 2.3 Implement `CreatePostRepository(apiClient, locationProvider, permissionController, diagnosticLog = {})` as `CreatePostFlow`. `submit(content)` MUST gate on `permissionController.status()` BEFORE touching the location provider (the shipped `AndroidLocationProvider.current()` has no permission guard and can throw a synchronous `SecurityException` un-gated): `DENIED` → `LocationUnavailable` (no `current()`, no POST); `NOT_DETERMINED` → `permissionController.request()`, and if the result is not `GRANTED` → `LocationUnavailable` (no POST); `GRANTED` → proceed to 2.4. Rethrow `CancellationException`.
- [ ] 2.4 On the granted path: acquire `locationProvider.current()`, catching `LocationUnavailableException` → `LocationUnavailable` (no POST); otherwise POST and map `PostCreationApiResult` → `PostCreationOutcome` exhaustively (no wildcard): 201→`Success`; 400 keyed on `error.code` (`content_empty`/`content_too_long`/`location_out_of_bounds`/`content_moderated_profanity`), any other/absent 400 → `Error` + `diagnosticLog`; 5xx/IO → `NetworkError`; 401 delegated to the `Auth` plugin (not mapped here).

## 3. Pure UiState projection

- [ ] 3.1 Add `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationUiState.kt`: a Compose-free `PostCreationUiState` (idle/typing with `charCount` + `overLimit` + `submitEnabled`, `Loading`, `Success`, and the per-error banner variants), carrying no PII.
- [ ] 3.2 Implement a Unicode **code-point** length helper (NOT UTF-16 units; commonMain-safe — e.g. iterate code points) so a 280-emoji string counts as 280.
- [ ] 3.3 Implement the pure `postCreationUiState(content: String, outcome: PostCreationOutcome?, inFlight: Boolean): PostCreationUiState`: submit enabled iff `1..280` non-blank code points AND not in-flight; `inFlight` → `Loading`; outcome → `Success`/error variant. Deterministic, no wall-clock, no PII.

## 4. Strings (:shared:resources)

- [ ] 4.1 Add to `shared/resources/src/commonMain/composeResources/values/strings.xml`: `post_create_title`, `post_create_content_placeholder`, `post_create_char_counter` (`formatted="true"`, `"%1$d/280"`), `cta_post`, `post_create_loading`, `post_create_error_empty`, `post_create_error_too_long`, `post_create_error_location`, `post_create_error_moderated` (generic, keyword-free), `post_create_location_unavailable` — derived BI copy consistent with the Mobile #3/#4/#5 register, with an XML comment flagging them for UX review (no docs-canonical composer copy exists).
- [ ] 4.2 Extend `mobile/app/src/commonTest/kotlin/id/nearyou/app/resources/SharedStringsCatalogTest.kt` to reference every new `Res.string.*` accessor and update the declared-count assertion (36 → new total).

## 5. Screen + FAB entry point

- [ ] 5.1 Add `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt` (Voyager `Screen`): injects `CreatePostFlow` + `LocationPermissionController` (the latter for the denial banner's settings deep-link); holds `content`, `outcome`, `inFlight` in `remember`; renders title + multiline `OutlinedTextField` (placeholder) + live counter (`post_create_char_counter` with the code-point count) + outcome-driven error banner + a "Posting" CTA (`cta_post`) disabled per `postCreationUiState`. All copy via `stringResource`; under `NearYouTheme`.
- [ ] 5.2 On CTA tap: set `inFlight`, call `flow.submit(content)`, reset `inFlight` in `finally`; on `Success` → `navigator.pop()` from a `LaunchedEffect(outcome)` (never mutate the navigator during composition).
- [ ] 5.3 Wire the `LocationUnavailable` banner's "Buka Pengaturan" control to `LocationPermissionController.openAppSettings()` (reuse `location_open_settings`); wire `NetworkError`/`Error` to a `cta_retry` control that re-submits.
- [ ] 5.4 Update `HomeScreen` to host a `Scaffold { floatingActionButton = … }` wrapping the existing `NearbyTimelineScreen.Content()` body; the FAB pushes `PostCreationScreen()` via `LocalNavigator.currentOrThrow`. Do NOT modify `NearbyTimelineScreen.kt` (keep `mobile-nearby-timeline` delta-free).

## 6. DI wiring

- [ ] 6.1 In `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`, register `single { PostCreationApiClient(get()) }`, `single { CreatePostRepository(get(), get(), get()) }` (apiClient, `LocationProvider`, `LocationPermissionController`), and `single<CreatePostFlow> { get<CreatePostRepository>() }`; reuse the platform-bound `LocationProvider` + `LocationPermissionController` + shared `HttpClient` (no new location/permission bindings — both come from `platformModule` via `mobile-location-permission-flow`).
- [ ] 6.2 Verify the Koin graph resolves (extend `KoinInitTest` or add a focused check that `CreatePostFlow` resolves).

## 7. Tests

- [ ] 7.1 commonTest `PostCreationUiStateTest`: empty/whitespace → disabled; 280 code points → enabled + not over-limit; 281 → disabled + over-limit; 280-emoji → count 280 + enabled, 281-emoji → over-limit; `inFlight` → `Loading` + disabled; each outcome → its state.
- [ ] 7.2 commonTest `PostCreationApiClientTest` (Ktor `MockEngine`): request is `POST /api/v1/posts` with body keys `content`/`latitude`/`longitude` (bare — negative guard vs snake_case); 201 parses `CreatedPostDto.id` and tolerates snake_case `distance_m`/`created_at`; non-2xx → `HttpError(status, errorCode)`; transport failure → `NetworkError`; `CancellationException` rethrown.
- [ ] 7.3 commonTest `CreatePostRepositoryTest` (MockEngine + a fake `LocationProvider` counting `current()` calls + the existing `FakeLocationPermissionController`): permission `DENIED`→`LocationUnavailable` with ZERO `current()` calls AND ZERO requests; `NOT_DETERMINED`(`afterRequest = GRANTED`)→`request()` invoked once + POST issued + `Success`; `GRANTED` + `current()` throws→`LocationUnavailable` with ZERO requests; `GRANTED` happy path: 201→`Success`; each enumerated 400 `error.code`→its outcome; unknown 400→`Error` + diagnostic; 5xx/IO→`NetworkError`. Add a `FakeCreatePostFlow` test double for screen tests.
- [ ] 7.4 androidUnitTest Robolectric `PostCreationScreenTest` via `FakeCreatePostFlow`: initial render (title, placeholder, `0/280`, disabled CTA); typing a valid string enables the CTA; a 281-code-point string disables it; each error banner (`ContentEmpty`/`ContentTooLong`/`LocationOutOfBounds`/`ContentRejected`/`LocationUnavailable`/`NetworkError`); the `LocationUnavailable` "Buka Pengaturan" affordance; success→pop.
- [ ] 7.5 Add `**/PostCreationScreenTest*` to the `mobile/app/build.gradle.kts` Release-variant `tasks.withType<Test>()` exclude block (alongside the existing `*ScreenTest` exclusions); confirm `./gradlew :mobile:app:testDevReleaseUnitTest` does not attempt it.
- [ ] 7.6 Add a no-hardcoded-strings source check for `PostCreationScreen.kt` (grep/inspection consistent with the existing mobile-strings discipline) and a logging-not-widened check (HttpClientFactory still `LogLevel.HEADERS`).

## 8. Follow-ups, validation, verification

- [ ] 8.1 Add `FOLLOW_UPS.md` entries for the named deferrals: `mobile-post-creation-manual-location` (map-pin) and the Nearby-auto-refresh-on-return follow-up.
- [ ] 8.2 Run `openspec validate mobile-post-creation-screen --strict` → green.
- [ ] 8.3 Run `./gradlew ktlintCheck detekt :mobile:app:testDebugUnitTest :mobile:app:testDevReleaseUnitTest` → green (mirrors the pre-push lint+test gate; note the Release-variant run guards the `*ScreenTest` exclude).
- [ ] 8.4 Manual runtime pass (device location actuals are not unit-testable): on an Android device + iOS sim, open the composer via the FAB, post a short text from a real location → 201 → pop to Home; verify the location-denied path shows the enable-location copy + "Buka Pengaturan"; confirm no coordinate appears in debug logs.
