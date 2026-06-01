## ADDED Requirements

### Requirement: LocationPermissionController is a commonMain seam with platform actuals

The mobile app SHALL declare a `commonMain` `LocationPermissionController` interface that exposes (a) a way to query the current location-permission status as a Compose-free enum (at minimum `GRANTED`, `DENIED`, `NOT_DETERMINED`), (b) a `suspend` request operation that surfaces the consent rationale and then the OS permission prompt, and (c) an `openAppSettings()` operation that deep-links to the OS app-settings screen. The interface MUST reference NO platform location/permission API in `commonMain`. Android and iOS `actual` bindings SHALL be registered in their respective `platformModule`; a fake implementation SHALL be available to drive tests (mirroring the `mobile-auth-signin` `GoogleSignInGateway` testability idiom).

#### Scenario: Interface is platform-free in commonMain
- **WHEN** inspecting the `LocationPermissionController` interface declaration in `mobile/app/src/commonMain`
- **THEN** it declares the status query, the suspend request, and `openAppSettings()` AND references no platform symbol (`FusedLocationProviderClient`, `CLLocationManager`, `ActivityCompat`, `ACCESS_COARSE_LOCATION`)

#### Scenario: Each platform module binds a real controller; tests use a fake
- **WHEN** inspecting `androidMain` and `iosMain` `PlatformModule.kt` and the test sources
- **THEN** each `platformModule` binds a real `LocationPermissionController` actual AND a fake `LocationPermissionController` exists in test sources for driving the gate scenarios

### Requirement: Real device-location provider replaces the stub as the production binding

The default **production** `LocationProvider` Koin binding SHALL be a real platform device-location provider: Android via `FusedLocationProviderClient` (from `play-services-location`), iOS via `CLLocationManager`. The provider SHALL request only **coarse/approximate** accuracy — Android `ACCESS_COARSE_LOCATION` only (NO `ACCESS_FINE_LOCATION`), iOS **when-in-use** authorization (no background/"always" location). The provider SHALL be invoked only after location permission is confirmed granted. The provider MUST NOT log the acquired coordinate (no `lat`/`lng`/coordinate value in any log/diagnostic call). `StubLocationProvider` (fixed `LatLng(-6.2, 106.8)`) SHALL be retained in `commonMain` as the test double.

#### Scenario: Production binds the real provider; stub retained for tests
- **WHEN** inspecting the production Koin modules (`MobileModule` + each `platformModule`) and `commonTest`
- **THEN** the production-bound `LocationProvider` is the real platform provider (NOT `StubLocationProvider`) AND `StubLocationProvider` returning `LatLng(-6.2, 106.8)` remains present and is the double used by tests

#### Scenario: Coarse-only, no fine, no background
- **WHEN** searching `mobile/app/src/androidMain` and `mobile/app/src/iosMain` for location-permission usage
- **THEN** Android references `ACCESS_COARSE_LOCATION` and does NOT reference `ACCESS_FINE_LOCATION` AND iOS uses `requestWhenInUseAuthorization` and does NOT request "Always"/background authorization

#### Scenario: Acquired coordinate is never logged
- **WHEN** inspecting the real `LocationProvider` actuals
- **THEN** no logging/diagnostic call site receives the acquired latitude/longitude (the coordinate is returned to the caller only)

### Requirement: UU-PDP location-consent rationale precedes the OS permission prompt

Before the OS location-permission prompt is shown, the app SHALL present a UU-PDP consent rationale (why location is needed, what is collected, how often it is accessed) per [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) § Location Permission and [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) § Analytics & Tracking Consent. The OS prompt SHALL fire only after the user accepts the rationale. All rationale copy SHALL be sourced via `stringResource(Res.string.*)` with no hardcoded string literals. Declining the rationale SHALL NOT force the OS prompt and SHALL leave the user in the denial fallback.

#### Scenario: Rationale is shown before the OS prompt
- **WHEN** the `LocationPermissionController.request(...)` flow runs with permission `NOT_DETERMINED`
- **THEN** the consent rationale is surfaced first AND the OS permission prompt is invoked only after the user accepts the rationale

#### Scenario: Consent-modal copy is fully resource-backed
- **WHEN** inspecting the consent-rationale modal source
- **THEN** every user-facing string is sourced via `stringResource(Res.string.<name>)` AND zero literal UI strings appear

### Requirement: Permission status maps to a pure, Compose-free UI state

The change SHALL model the permission-gate result as a Compose-free state type and a pure projection function (mirroring `NearbyTimelineUiState` / `AgeGateUiState`) that maps each permission status to its corresponding UI state deterministically, with no wall-clock, platform, or Compose dependency. The projection MUST carry no coordinates.

#### Scenario: Projection maps each status deterministically
- **WHEN** the projection is invoked for `GRANTED`, for `DENIED`, and for `NOT_DETERMINED`
- **THEN** each call returns the corresponding state (proceed-to-fetch / denied-fallback / prompt-rationale respectively) deterministically, with no platform or wall-clock dependency

### Requirement: Android and iOS platform configuration declare coarse location

The Android manifest SHALL declare `<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />` and SHALL NOT declare `ACCESS_FINE_LOCATION`. The iOS `Info.plist` SHALL declare `NSLocationWhenInUseUsageDescription` with a user-facing justification string. The Android runtime permission request SHALL go through the existing `CurrentActivityHolder` / Activity-result seam; iOS SHALL use `CLLocationManager.requestWhenInUseAuthorization`.

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

The change SHALL ship: (1) a `commonTest` test for the pure permission-status → UI-state projection covering granted / denied / not-determined; (2) a fake-`LocationPermissionController`-driven test exercising the request-shows-rationale-then-prompt path and the decline path; (3) any new Robolectric `*ScreenTest` (e.g., for the consent modal or denial state) added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list.

#### Scenario: Projection and consent-flow tests are discoverable
- **WHEN** running `./gradlew :mobile:app:testDebugUnitTest`
- **THEN** the permission-projection test and the fake-controller consent-flow test are discovered AND each documented status/path corresponds to at least one `@Test`

#### Scenario: New screen tests are excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** any new `*ScreenTest` added by this change is listed in the `tasks.withType<Test>()` Release-variant exclude block alongside the existing `*ScreenTest` exclusions
