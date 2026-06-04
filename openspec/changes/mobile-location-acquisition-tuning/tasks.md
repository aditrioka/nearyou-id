## 1. Pre-implementation re-check (before the first feat commit)

- [ ] 1.1 Dated WebSearch re-confirm `CurrentLocationRequest.Builder` (`setMaxUpdateAgeMillis` / `setDurationMillis` / `setGranularity`) is the canonical current API in the already-pinned `play-services-location 21.3.0`, and the iOS `CLLocationManager.location` cached-fix-reuse + `withTimeout`-bounded `requestLocation()` pattern is still current (propose-time check done 2026-06-05; re-verify at apply kickoff per repo precedent that library calls stale fast). Record the dated note in `design.md` if anything shifted.
- [ ] 1.2 Confirm NO `gradle/libs.versions.toml` change is needed (uses existing `21.3.0`); if a bump turns out necessary, add a `docs/09-Versions.md` Version Pinning Decisions Log row before coding.

## 2. commonMain — caching decorator + clock seam

- [ ] 2.1 Add named constants with rationale KDoc (design D4): in-process warm staleness `60s`, Android `maxUpdateAgeMillis 90s`, acquisition ceiling `durationMillis`/iOS timeout `12s`.
- [ ] 2.2 Implement `CachingLocationProvider` in `commonMain` package `id.nearyou.app.location` (co-located with the actuals it wraps + `LocationUnavailableException`) implementing `LocationProvider`, wrapping a delegate `LocationProvider`, holding `(LatLng, TimeMark)`; `current()` returns the held fix when `mark.elapsedNow() < stalenessWindow` (exclusive bound), else delegates and stores (specs: "Consumers share one in-process warm fix").
- [ ] 2.3 Inject a `kotlin.time.TimeSource` seam (default `TimeSource.Monotonic`); make NO wall-clock / `Clock.System.now()` call for staleness (design D3; spec scenario "Staleness uses an injected clock").
- [ ] 2.4 Make NO logging/diagnostic call carrying the coordinate from the decorator (hold it privately, return to caller only) (design D6; spec scenario "Acquired coordinate is never logged").
- [ ] 2.5 Single-flight the check-delegate-store critical section with a `kotlinx.coroutines.sync.Mutex` so concurrent `current()` on a cold/expired holder share **one** acquisition (design D7; spec scenario "Concurrent cold-holder calls share one acquisition").

## 3. Android actual — CurrentLocationRequest tuning

- [ ] 3.1 Replace the 2-arg `getCurrentLocation(priority, token)` in `AndroidLocationProvider.current()` with a `CurrentLocationRequest.Builder()` carrying `setPriority(PRIORITY_BALANCED_POWER_ACCURACY)`, `setDurationMillis(12_000)`, `setMaxUpdateAgeMillis(90_000)`, `setGranularity(GRANULARITY_COARSE)` (specs: "bounded" + "recent cached fix reused").
- [ ] 3.2 Attempt an age-acceptable `lastLocation` before the fresh acquisition; keep `LocationUnavailableException` as the granted-but-no-fix terminal (design D5).
- [ ] 3.3 Keep `ACCESS_COARSE_LOCATION`-only — no `ACCESS_FINE_LOCATION`, no background (spec scenario "Coarse-only, no fine, no background" still passes).

## 4. iOS actual — timeout ceiling + cached-fix reuse

- [ ] 4.1 In `IosLocationProvider`, check `CLLocationManager.location` and reuse it when its `timestamp` is within the staleness window before calling `requestLocation()` (spec: "iOS reuses a recent cached fix"); do NOT log the coordinate or the `timestamp` on the reuse branch (guarded by 6.8).
- [ ] 4.2 Wrap the one-shot `requestLocation()` acquisition in `withTimeout`/`withTimeoutOrNull` (~12s, defensive ceiling above the internal ~10s) resolving to `LocationUnavailableException` on expiry (spec: "iOS acquisition is timeout-bounded"); keep `kCLLocationAccuracyReduced`, when-in-use only.

## 5. Koin wiring — qualifier + decorator binding

- [ ] 5.1 Re-bind the real platform provider in each `platformModule` (androidMain + iosMain) behind a Koin qualifier `named("deviceLocation")` (design D2).
- [ ] 5.2 Bind the unqualified `LocationProvider` in `mobileModule` to `CachingLocationProvider(get(named("deviceLocation")), TimeSource.Monotonic, stalenessWindow)`; verify `NearbyTimelineRepository` + `CreatePostRepository` resolve it unchanged (spec: "Production binds the caching decorator over the real provider").
- [ ] 5.3 Confirm no second unqualified `LocationProvider` binding remains (Koin would otherwise clash); `StubLocationProvider` stays the unqualified `commonTest` double.

## 6. Tests

- [ ] 6.1 `commonTest` decorator test (counting fake device source + `TestTimeSource`): two `current()` within the window → exactly one acquisition + same coordinate (spec: "Warm fix reused across consumers").
- [ ] 6.2 `commonTest`: advance `TestTimeSource` past the window → second `current()` re-delegates (count → 2) (spec: "An expired fix is re-acquired").
- [ ] 6.3 `commonTest`: decorator wrapping a fake that throws `LocationUnavailableException` → `current()` propagates it, returns no stale/sentinel coordinate (spec: "A delegate timeout surfaces as LocationUnavailableException").
- [ ] 6.4 Source-inspection guard test (strip comments first per repo precedent — else the file's own KDoc trips the scan): assert `AndroidLocationProvider` uses `CurrentLocationRequest` with `setDurationMillis` + `setMaxUpdateAgeMillis` + `setGranularity(GRANULARITY_COARSE)` and NOT the 2-arg `getCurrentLocation` overload; assert no `ACCESS_FINE_LOCATION`.
- [ ] 6.5 Source-inspection: `IosLocationProvider` wraps `requestLocation()` in a coroutine timeout AND checks `CLLocationManager.location` with a `timestamp`-comparison token AND a nil/absent fall-through guard; no background/"Always" authorization.
- [ ] 6.6 `commonTest` concurrency test: two `current()` launched via `async`/`awaitAll` on a cold holder backed by a counting fake whose acquisition suspends until both callers await → exactly ONE delegate acquisition + same coordinate (spec: "Concurrent cold-holder calls share one acquisition"; design D7 single-flight).
- [ ] 6.7 `commonTest` staleness-boundary test: advance `TestTimeSource` to elapsed == `stalenessWindow` exactly → second `current()` re-acquires (count increments), pinning the exclusive `<` bound (spec: "A fix exactly at the staleness window is re-acquired").
- [ ] 6.8 Extend the source-scan no-logging guard to the new coordinate-holder: `PostCreationSourceGuardTest` scans a hardcoded file list and will NOT pick up `CachingLocationProvider` — add it (and the tuned `AndroidLocationProvider` / `IosLocationProvider`) to that comment-stripping guard (or a sibling `LocationSourceGuardTest`), asserting no `println` / `Log.` / `NSLog` / `Napier` / `LogLevel.BODY`/`ALL` and no coordinate-or-`timestamp`-bearing diagnostic call (security N1/N2; design D6 — makes the no-coordinate-logging property test-enforced, not just review-enforced).

## 7. Validate, gate, verify, sync

- [ ] 7.1 `openspec validate mobile-location-acquisition-tuning --strict` passes.
- [ ] 7.2 Mobile gate: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + root `./gradlew detekt ktlintCheck` green (worktree needs a copied `local.properties` SDK pointer). No new `*ScreenTest` expected (provider/repo-layer); if one is added, list it in the `build.gradle.kts` Release-variant test-exclude.
- [ ] 7.3 iOS local verification (CI is Linux-only): `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` + sim smoke that a cold-cache acquisition no longer hangs and a warm second read is instant.
- [ ] 7.4 On-device cold-cache check (Android): confirm the composer + Nearby first-load no longer sit 30–46s+; tune the named constants (2.1) if lived latency disagrees (design Open Question).
- [ ] 7.5 At archive: spec-sync `openspec/specs/mobile-location/spec.md` and delete `FOLLOW_UPS.md § mobile-location-acquisition-latency` (its action items are now shipped).
