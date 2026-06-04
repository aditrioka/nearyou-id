## MODIFIED Requirements

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

## ADDED Requirements

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
