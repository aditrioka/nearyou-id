# mobile-location Specification

## Purpose
The mobile device-location + runtime-permission + UU-PDP consent surface for `:mobile:app` — the capability that turns the flagship Nearby feed from a fixed-coordinate demo into a real "what's near me" screen. A platform-free `commonMain` seam (`LocationPermissionController` plus the shipped `LocationProvider`) abstracts the OS location-permission ceremony and coarse device-coordinate acquisition behind a fake-driven, Compose-free `LocationGate` orchestrator whose status projection maps deterministically to proceed-to-fetch / prompt-rationale / denied-fallback. Thin platform actuals back it: Android `FusedLocationProviderClient` (coarse, `ACCESS_COARSE_LOCATION` only, requested via an `ActivityResultLauncher` bridge alongside the existing `CurrentActivityHolder`) and iOS `CLLocationManager` (when-in-use, reduced accuracy) — never fine, never background, honoring UU-PDP data minimization. Before the OS prompt a fully resource-backed UU-PDP consent-rationale modal explains why/what/how-often location is used; the acquired coordinate is never logged (the provider makes no logging call, and the shared `HttpClient` masks `lat`/`lng` query-parameter values in debug logs). This capability supplies the real production `LocationProvider` binding and the contextual permission gate that the `mobile-nearby-timeline` Nearby surface consumes (`StubLocationProvider` is retained only as the test double).
## Requirements
### Requirement: LocationPermissionController is a commonMain seam with platform actuals

The mobile app SHALL declare a `commonMain` `LocationPermissionController` interface that exposes (a) a way to query the current location-permission status as a Compose-free enum (at minimum `GRANTED`, `DENIED`, `NOT_DETERMINED`), (b) a `suspend` request operation that surfaces the consent rationale and then the OS permission prompt, and (c) an `openAppSettings()` operation that deep-links to the OS app-settings screen. The interface MUST reference NO platform location/permission API in `commonMain`. Android and iOS `actual` bindings SHALL be registered in their respective `platformModule`; a fake implementation SHALL be available to drive tests (mirroring the `mobile-auth-signin` `GoogleSignInGateway` testability idiom).

#### Scenario: Interface is platform-free in commonMain
- **WHEN** inspecting the `LocationPermissionController` interface declaration in `mobile/app/src/commonMain`
- **THEN** it declares the status query, the suspend request, and `openAppSettings()` AND references no platform symbol (`FusedLocationProviderClient`, `CLLocationManager`, `ActivityCompat`, `ACCESS_COARSE_LOCATION`)

#### Scenario: Each platform module binds a real controller; tests use a fake
- **WHEN** inspecting `androidMain` and `iosMain` `PlatformModule.kt` and the test sources
- **THEN** each `platformModule` binds a real `LocationPermissionController` actual AND a fake `LocationPermissionController` exists in test sources for driving the gate scenarios

### Requirement: Real device-location provider replaces the stub as the production binding

The default **production** `LocationProvider` Koin binding SHALL be a `commonMain` caching decorator that wraps a real platform device-location provider (the real provider bound behind a Koin qualifier, e.g. `named("deviceLocation")`): Android via `FusedLocationProviderClient` (from `play-services-location`), iOS via `CLLocationManager`. Both the decorator and the wrapped provider SHALL request only **coarse/approximate** accuracy — Android `ACCESS_COARSE_LOCATION` only (NO `ACCESS_FINE_LOCATION`), iOS **when-in-use** authorization (no background/"always" location). The provider SHALL be invoked only after location permission is confirmed granted. Neither the wrapped provider nor the decorator SHALL log the acquired coordinate (no `lat`/`lng`/coordinate value in any log/diagnostic call). `StubLocationProvider` (fixed `LatLng(-6.2, 106.8)`) SHALL be retained in `commonMain` as the test double; the consuming repositories SHALL continue to inject the unqualified `LocationProvider` seam unchanged.

#### Scenario: Production binds the caching decorator over the real provider; stub retained for tests
- **WHEN** inspecting the production Koin modules (`MobileModule` + each `platformModule`) and `commonTest`
- **THEN** the unqualified production-bound `LocationProvider` is the caching decorator wrapping the real platform provider (the real provider bound behind a Koin qualifier, NOT `StubLocationProvider`) AND `StubLocationProvider` returning `LatLng(-6.2, 106.8)` remains present and is the double used by tests

#### Scenario: Coarse-only, no fine, no background
- **WHEN** searching `mobile/app/src/androidMain` and `mobile/app/src/iosMain` for location-permission usage
- **THEN** Android references `ACCESS_COARSE_LOCATION` and does NOT reference `ACCESS_FINE_LOCATION` AND iOS uses `requestWhenInUseAuthorization` and does NOT request "Always"/background authorization

#### Scenario: Acquired coordinate is never logged
- **WHEN** inspecting the real `LocationProvider` actuals AND the `commonMain` caching decorator
- **THEN** no logging/diagnostic call site receives the acquired latitude/longitude (the coordinate is held privately and returned to the caller only)

### Requirement: Coordinate query parameters are masked in HTTP-client logs

Because the Nearby fetch sends the coordinate as `lat`/`lng` **URL query parameters** (`NearbyTimelineApiClient.fetchNearby`) and the shared `HttpClient` installs the `Logging` plugin at `LogLevel.HEADERS` in debug builds (which logs the request URL line *including the query string*), the existing `Authorization`-only `sanitizeHeader` does NOT protect the coordinate. This change SHALL mask the `lat` and `lng` (and any `coord`-bearing) query-parameter VALUES in the `HttpClient` log output, analogous to the existing `Authorization`-header sanitizer in `HttpClientFactory`, so the acquired coordinate is never written to logs by the HTTP layer. (Release builds install no `Logging` plugin — already the case — so this closes the debug-build exposure.) This complements, and does not widen, the shipped `mobile-nearby-timeline` logging discipline.

#### Scenario: Nearby request log line is coordinate-free in debug builds
- **GIVEN** the shared `HttpClient` with the `Logging` plugin installed at `LogLevel.HEADERS` (debug) and a capturing logger
- **WHEN** the Nearby fetch issues `GET /api/v1/timeline/nearby?lat=<lat>&lng=<lng>&radius_m=20000` and the request line is logged
- **THEN** the captured log output does NOT contain the latitude or longitude values (the `lat`/`lng` query-parameter values are masked, mirroring the `Authorization`-header sanitizer)

### Requirement: UU-PDP location-consent rationale precedes the OS permission prompt

Before the OS location-permission prompt is shown, the app SHALL present a UU-PDP consent rationale (why location is needed, what is collected, how often it is accessed) per [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md):73 § Location Permission (the UX source), with the UU-PDP legal basis in [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md):37 (articles 20-22 — explicit consent for non-essential personal-data processing). This location-consent modal is a distinct surface from the separate, deferred analytics-consent screen (`docs/06` § Analytics & Tracking Consent) and SHALL NOT be persisted to `users.analytics_consent`. The OS prompt SHALL fire only after the user accepts the rationale. All rationale copy SHALL be sourced via `stringResource(Res.string.*)` with no hardcoded string literals. Declining the rationale SHALL NOT force the OS prompt and SHALL leave the user in the denial fallback.

#### Scenario: Rationale is shown before the OS prompt
- **WHEN** the `LocationPermissionController.request(...)` flow runs with permission `NOT_DETERMINED`
- **THEN** the consent rationale is surfaced first AND the OS permission prompt is invoked only after the user accepts the rationale

#### Scenario: Consent-modal copy is fully resource-backed
- **WHEN** inspecting the consent-rationale modal source
- **THEN** every user-facing string is sourced via `stringResource(Res.string.<name>)` AND zero literal UI strings appear

#### Scenario: A prior denial does not re-show the rationale on every Nearby visit
- **GIVEN** a fake `LocationPermissionController` reporting `DENIED` with a counter on its `request(...)` invocations
- **WHEN** the Nearby surface is re-composed/re-entered after the user has already declined or been denied
- **THEN** the rationale modal is NOT re-shown automatically (the `request(...)` invocation count does not increment on re-entry) — the denial fallback + "Buka Pengaturan" CTA is the only re-entry path

### Requirement: Permission status maps to a pure, Compose-free UI state

The change SHALL model the permission-gate result as a Compose-free state type and a pure projection function (mirroring `NearbyTimelineUiState` / `AgeGateUiState`) that maps each permission status to its corresponding UI state deterministically, with no wall-clock, platform, or Compose dependency. The projection MUST carry no coordinates. The status enum is `GRANTED` / `DENIED` / `NOT_DETERMINED`; platform terminal-denial states (Android "don't ask again" / permanently-denied, iOS `restricted`) collapse into `DENIED` (whose escape hatch is the "Buka Pengaturan" CTA) rather than adding enum members.

#### Scenario: Projection maps each status deterministically
- **WHEN** the projection is invoked for `GRANTED`, for `DENIED`, and for `NOT_DETERMINED`
- **THEN** each call returns the corresponding state (proceed-to-fetch / denied-fallback / prompt-rationale respectively) deterministically, with no platform or wall-clock dependency

### Requirement: Android and iOS platform configuration declare coarse location

The Android manifest SHALL declare `<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />` and SHALL NOT declare `ACCESS_FINE_LOCATION`. The iOS `Info.plist` SHALL declare `NSLocationWhenInUseUsageDescription` with a user-facing justification string. The Android runtime permission request SHALL go through an Activity-result seam — a `RequestPermission` `ActivityResultLauncher` registered by `MainActivity` and bridged to the `suspend` request via `LocationPermissionRequestBridge`, a sibling of the existing `CurrentActivityHolder` seam (permission results use a different `ActivityResultContract` than Credential Manager's account-picker flow). iOS SHALL use `CLLocationManager.requestWhenInUseAuthorization`.

#### Scenario: Android manifest declares coarse and not fine
- **WHEN** inspecting the Android manifest
- **THEN** it contains `ACCESS_COARSE_LOCATION` AND does NOT contain `ACCESS_FINE_LOCATION`

#### Scenario: iOS Info.plist declares the when-in-use usage description
- **WHEN** inspecting the iOS `Info.plist`
- **THEN** it contains a non-empty `NSLocationWhenInUseUsageDescription` value

### Requirement: New strings and the play-services-location pin are added

The change SHALL add the consent-rationale, denial-state, and "Buka Pengaturan" CTA strings to `:shared:resources` (consumed via `Res.string.*`). The change SHALL pin `play-services-location` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (the first location SDK on the classpath) and SHALL add a corresponding row to the Version Pinning Decisions Log ([`docs/09-Versions.md`](../../../docs/09-Versions.md)) recording the fused-vs-LocationManager rationale and the pre-implementation re-check date.

#### Scenario: Strings exist in the resources catalog
- **WHEN** inspecting `:shared:resources`
- **THEN** the location consent-rationale, denial-state, and "Buka Pengaturan" strings are present AND are referenced from the mobile sources via `Res.string.*`

#### Scenario: play-services-location is pinned with a decisions-log row
- **WHEN** inspecting `gradle/libs.versions.toml` and `docs/09-Versions.md`
- **THEN** `play-services-location` has a version pin AND a Version Pinning Decisions Log row records the rationale + the dated re-check

### Requirement: Test coverage for the permission projection and consent flow

The change SHALL ship: (1) a `commonTest` test for the pure permission-status → UI-state projection covering granted / denied / not-determined; (2) a fake-`LocationPermissionController`-driven test exercising the request-shows-rationale-then-prompt path, the decline path, and the no-re-prompt-on-re-entry path (`commonTest` has no Compose runner, so the rationale-vs-prompt **decision logic** MUST be pure/`commonTest`-able; the modal **render** is asserted in a Robolectric `*ScreenTest`); (3) any new Robolectric `*ScreenTest` (e.g., for the consent modal or denial state) added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list.

#### Scenario: Projection and consent-flow tests are discoverable
- **WHEN** running `./gradlew :mobile:app:testDebugUnitTest`
- **THEN** the permission-projection test and the fake-controller consent-flow test are discovered AND each documented status/path corresponds to at least one `@Test`

#### Scenario: New screen tests are excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** any new `*ScreenTest` added by this change is listed in the `tasks.withType<Test>()` Release-variant exclude block alongside the existing `*ScreenTest` exclusions

### Requirement: Location acquisition is bounded — no unbounded wait

The real device-location provider SHALL bound each acquisition so that a granted-but-no-timely-fix surfaces `LocationUnavailableException` within a bounded duration rather than waiting indefinitely. The Android provider SHALL pass a `CurrentLocationRequest` carrying `setDurationMillis(...)` to `getCurrentLocation` (so the request expires instead of hanging). The iOS provider SHALL bound its one-shot `requestLocation()` acquisition with a coroutine timeout as a defensive ceiling above `requestLocation()`'s internal ~10s self-timeout. On timeout the provider SHALL throw the **existing** `LocationUnavailableException` — NO new exception type and NO new `NearbyTimelineOutcome` / `PostCreationOutcome` member is introduced; the consumers' existing retryable mapping is reused.

#### Scenario: Android getCurrentLocation is duration-bounded
- **WHEN** inspecting `AndroidLocationProvider`
- **THEN** it builds a `CurrentLocationRequest` with `setDurationMillis(...)` and passes it to `getCurrentLocation` (NOT the 2-arg `getCurrentLocation(priority, token)` overload)

#### Scenario: iOS acquisition is timeout-bounded
- **WHEN** inspecting `IosLocationProvider`
- **THEN** the one-shot `requestLocation()` acquisition is wrapped in a coroutine timeout (e.g. `withTimeout`/`withTimeoutOrNull`) that resolves to `LocationUnavailableException` on expiry

#### Scenario: A delegate timeout surfaces as LocationUnavailableException through the decorator
- **GIVEN** the caching decorator wrapping a fake device source that throws `LocationUnavailableException` (the granted-but-no-fix path)
- **WHEN** `current()` is invoked with no valid warm fix held
- **THEN** `current()` throws `LocationUnavailableException` (the exception is propagated, not swallowed, and no stale/sentinel coordinate is returned)

### Requirement: A recent cached fix is reused instead of cold-acquiring

The real device-location provider SHALL return a sufficiently-recent cached device fix without forcing a cold acquisition. The Android provider SHALL set `setMaxUpdateAgeMillis(...)` and `setGranularity(GRANULARITY_COARSE)` on the `CurrentLocationRequest` and SHALL attempt an age-acceptable `lastLocation` before a fresh acquisition. The iOS provider SHALL reuse `CLLocationManager.location` when its `timestamp` is within the staleness window before falling through to `requestLocation()`.

#### Scenario: Android requests a max-age-bounded coarse fix
- **WHEN** inspecting `AndroidLocationProvider`
- **THEN** the `CurrentLocationRequest` sets `setMaxUpdateAgeMillis(...)` AND `setGranularity(GRANULARITY_COARSE)` AND a `lastLocation` attempt precedes the fresh `getCurrentLocation` acquisition

#### Scenario: iOS reuses a recent cached fix before requesting
- **WHEN** inspecting `IosLocationProvider`
- **THEN** `CLLocationManager.location` is checked (with a `timestamp` staleness guard) and reused when recent, before `requestLocation()` is called

### Requirement: Consumers share one in-process warm fix via a clock-seam-driven decorator

A `commonMain` caching decorator implementing the `LocationProvider` seam SHALL hold the last-good `LatLng` together with its acquisition instant, and on `current()` SHALL return the held fix when it is within an in-process staleness window, otherwise delegate to the wrapped provider and store the freshly-acquired result. As a result, a second consumer invoking `current()` within the staleness window SHALL reuse the first consumer's fix without re-acquiring. The staleness clock SHALL be an **injected seam** (a `kotlin.time.TimeSource` or equivalent), NOT a wall-clock call inside the decorator, so the staleness decision is pure and `commonTest`-able. The staleness comparison SHALL be **exclusive** (`elapsed < window`): a held fix whose age has reached the window SHALL be treated as expired and re-acquired. Concurrent `current()` invocations on a cold (or expired) holder SHALL be **single-flighted** (e.g. via a `Mutex`) so they share exactly one underlying acquisition rather than each delegating independently.

#### Scenario: Warm fix is reused across consumers within the window
- **GIVEN** the caching decorator wrapping a counting fake device source and an injected `TestTimeSource`
- **WHEN** `current()` is invoked twice with the elapsed time between the calls inside the staleness window
- **THEN** exactly one underlying acquisition occurs (the fake's invocation count is 1) AND both calls return the same coordinate

#### Scenario: An expired fix is re-acquired
- **GIVEN** the caching decorator wrapping a counting fake device source and an injected `TestTimeSource`
- **WHEN** the `TestTimeSource` is advanced beyond the staleness window between two `current()` calls
- **THEN** the second call delegates to the wrapped provider again (the fake's invocation count increments to 2)

#### Scenario: Staleness uses an injected clock, not a wall-clock call
- **WHEN** inspecting the caching decorator source
- **THEN** it accepts a `TimeSource` (or equivalent monotonic seam) constructor parameter AND makes no `Clock.System.now()` / wall-clock call to decide staleness

#### Scenario: Concurrent cold-holder calls share one acquisition (single-flight)
- **GIVEN** the caching decorator wrapping a counting fake device source whose acquisition suspends until both callers are awaiting, and a cold (empty) holder
- **WHEN** two `current()` calls are launched concurrently (e.g. via `async`/`awaitAll`)
- **THEN** exactly one underlying acquisition occurs (the fake's invocation count is 1) AND both calls return the same coordinate

#### Scenario: A fix exactly at the staleness window is re-acquired (exclusive bound)
- **GIVEN** the caching decorator wrapping a counting fake device source and an injected `TestTimeSource`
- **WHEN** the second `current()` is invoked with the elapsed time since the held fix equal to exactly the staleness window
- **THEN** the held fix is treated as expired and the call delegates to the wrapped provider again (the fake's invocation count increments), pinning the exclusive `elapsed < window` comparison

