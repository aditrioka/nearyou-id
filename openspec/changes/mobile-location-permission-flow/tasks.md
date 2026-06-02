## 1. Pre-implementation gates & dependency pin

- [x] 1.1 **Pre-implementation library re-check (MUST — new pin)**: run a fresh dated `WebSearch` (`"play-services-location FusedLocationProviderClient 2026"`, `"android coarse location best practice 2026"`) per [`openspec/project.md`](../../../openspec/project.md) § Pre-implementation library re-check. Confirm fused-over-LocationManager still holds (design D1 recorded 2026-06-02); drop a one-line `re-check 2026-MM-DD confirms…` note in the first feat commit body. If a materially-better alternative surfaced → STOP and surface to the user. — re-check 2026-06-02 confirms Fused remains Google's recommended Android location API over the deprecated LocationManager; no materially-better alternative surfaced.
- [x] 1.2 Pin `play-services-location` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (version + `libs.*` alias); add to `:mobile:app` `androidMain` dependencies only. — pinned 21.3.0 (`libs.google.playServicesLocation`); androidMain only.
- [x] 1.3 Add a Version Pinning Decisions Log row in [`docs/09-Versions.md`](../../../docs/09-Versions.md) recording the fused-vs-LocationManager rationale + the dated re-check (mirrors the `play-services-auth` / Credential Manager row shape).

## 2. commonMain seams (mobile-location capability)

- [x] 2.1 Add `LocationPermissionController` interface in `mobile/app/src/commonMain/.../location/` — Compose-free status enum (`GRANTED` / `DENIED` / `NOT_DETERMINED`), a `suspend` request operation (consent rationale → OS prompt), and `openAppSettings()`. No platform symbols in commonMain (spec: "Interface is platform-free in commonMain").
- [x] 2.2 Add the pure permission-status → UI-state projection (Compose-free, deterministic; mirrors `NearbyTimelineUiState` / `AgeGateUiState`) — maps `GRANTED`/`DENIED`/`NOT_DETERMINED` to proceed-to-fetch / denied-fallback / prompt-rationale. Carries no coordinates. — `locationGateUiState` projection + `LocationGate` orchestrator (the testable rationale-vs-prompt decision logic).

## 3. Platform actuals (Android fused, iOS CLLocationManager)

- [x] 3.1 Android: real `LocationProvider` actual via `FusedLocationProviderClient` (best-effort `getCurrentLocation` balanced-power + `getLastLocation` fallback), **coarse only**. MUST NOT log the coordinate (spec: "Acquired coordinate is never logged"). — `AndroidLocationProvider`; no logging; throws `LocationUnavailableException` on no-fix.
- [x] 3.2 Android: `LocationPermissionController` actual — runtime `ACCESS_COARSE_LOCATION` request via the existing `CurrentActivityHolder` / Activity-result seam; `openAppSettings()` → app-details settings intent built with `Uri.fromParts("package", packageName, null)` (self-scoped; never an externally-derived URI). Register both in `androidMain` `PlatformModule.kt`. — `AndroidLocationPermissionController` + `LocationPermissionRequestBridge` (launcher set in MainActivity); registered in PlatformModule.
- [x] 3.3 iOS: real `LocationProvider` actual via `CLLocationManager` (**when-in-use**, reduced accuracy acceptable per design D2/Open Q3). MUST NOT log the coordinate. — `IosLocationProvider` (one-shot `requestLocation`, `kCLLocationAccuracyReduced`); no logging.
- [x] 3.4 iOS: `LocationPermissionController` actual — `requestWhenInUseAuthorization`; `openAppSettings()` → `UIApplication.openSettingsURLString`. Register both in `iosMain` `PlatformModule.kt`. No "Always"/background authorization (spec: "Coarse-only, no fine, no background"). — `IosLocationPermissionController`; K/N signatures verified against the CoreLocation/UIKit klibs; registered in PlatformModule.

## 4. Consent rationale modal + strings

- [x] 4.1 Add the UU-PDP location-consent rationale (why/what/how-often) + denial-state copy ("*Aktifkan lokasi untuk lihat postingan sekitar*") + "*Buka Pengaturan*" CTA strings to `:shared:resources` (Bahasa Indonesia; `Res.string.*`). — 5 strings added + SharedStringsCatalogTest bumped 31→36.
- [x] 4.2 Build the consent-rationale modal composable — all copy via `stringResource(Res.string.*)`, zero hardcoded literals (spec: "Consent-modal copy is fully resource-backed"); surfaced by the controller's request path *before* the OS prompt. — `LocationConsentModal` (Material 3 AlertDialog).

## 5. Koin binding swap

- [x] 5.1 Remove `single<LocationProvider> { StubLocationProvider() }` from the commonMain `MobileModule`; bind the real provider in each `platformModule` (Android/iOS). Retain `StubLocationProvider` in commonMain as the test double (spec: "Production binds the real provider; stub retained for tests"). Keep the `mobile-nearby-timeline` testable-seam intact (`NearbyTimelineApiClient`/`Repository`/`SessionIdProvider` + the `NearbyTimelineFlow` binding stay in `mobileModule`). — done; MobileModule no longer references LocationProvider/StubLocationProvider; stub retained in timeline/.
- [x] 5.2 Mask coordinate query params in the `HttpClient` `Logging` output: extend `HttpClientFactory`'s logging config so the `lat`/`lng` (and any `coord`) query-parameter VALUES are redacted in the logged request line (analogous to the existing `Authorization` `sanitizeHeader`). Debug-build only (release installs no `Logging` plugin). Spec: `mobile-location` § "Coordinate query parameters are masked in HTTP-client logs". — `CoordinateMaskingLogger` wraps the injected logger; masks `lat`/`lng`/`coord…` values to `***`.

## 6. Nearby screen gate + denial state (mobile-nearby-timeline delta)

- [x] 6.1 Gate the Nearby surface on `LocationPermissionController` before fetching: granted → existing `NearbyTimelineFlow.loadFirstPage()` (unchanged); denied/unavailable → pre-fetch denial state, no fetch (spec: "Nearby feed is gated…"). Resolve Open Q1 (granted-but-no-fix surfacing) — keep `NearbyTimelineRepository`'s outcome mapping unchanged. — `LocationGate` projection; granted-but-no-fix caught in `NearbyFeed` → existing `NetworkError` Error state (provider-throws variant of Open Q1).
- [x] 6.2 Render the denial state: "Aktifkan lokasi…" copy + "Buka Pengaturan" CTA → `openAppSettings()`. Confirm no re-nagging on every Nearby visit (Open Q2). — `LocationDeniedState`; `refresh()` re-projects DENIED→Denied without calling `request()` (no re-nag).
- [x] 6.3 Verify `NearbyTimelineRepository` is unmodified (no new `NearbyTimelineOutcome` member; mapping byte-for-byte unchanged — spec: "Repository outcome mapping is unchanged"). — repository file untouched (confirmed in §9 git diff).

## 7. Platform configuration

- [x] 7.1 AndroidManifest: add `<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />`; confirm NO `ACCESS_FINE_LOCATION` (spec: "Android manifest declares coarse and not fine").
- [x] 7.2 iOS `Info.plist`: add a non-empty `NSLocationWhenInUseUsageDescription` justification string.

## 8. Tests

- [x] 8.1 `commonTest`: permission-status → UI-state projection test (granted / denied / not-determined; confirm terminal-denial states collapse into `DENIED`) — deterministic, no platform dep. — `LocationGateUiStateTest` (collapse documented as a platform-actual concern).
- [x] 8.2 `commonTest`: fake-`LocationPermissionController` test for the **pure** rationale-vs-prompt decision logic — request-shows-rationale-then-prompt, the decline path, AND the no-re-prompt-on-re-entry path (request-count does not increment on re-entry while `DENIED`). (`commonTest` has no Compose runner; keep the decision logic pure and assert the modal *render* in the Robolectric `*ScreenTest` below.) — `LocationGateTest` (+ OS-denied path).
- [x] 8.3 Robolectric screen test (fake controller + `FakeNearbyTimelineFlow`): denied → denial copy + "Buka Pengaturan" + fetch count `0`; granted → fetch path runs; granted-but-no-fix → existing retryable error state (no new outcome member); CTA → `openAppSettings()`; consent-modal render. — `NearbyLocationGateScreenTest` (+ exact-copy pin); existing `NearbyTimelineScreenTest` updated to bind a GRANTED fake controller.
- [x] 8.4 Add any new Robolectric `*ScreenTest` to the `mobile/app/build.gradle.kts` Release-variant test-exclude block (verify via `:mobile:app:testDevReleaseUnitTest`, not only Debug — precedent [PR #126](https://github.com/aditrioka/nearyou-id/pull/126)). — `**/NearbyLocationGateScreenTest*` added to the exclude.
- [x] 8.5 Logging test: assert the Nearby request's logged request line (capturing logger, `LogLevel.HEADERS`) does NOT contain the `lat`/`lng` values (spec: `mobile-location` § "Coordinate query parameters are masked in HTTP-client logs"). — `HttpClientCoordinateMaskTest`.

## 9. Build & lint verification

- [x] 9.1 `./gradlew ktlintCheck detekt` green (both frameworks — CI runs both). — BUILD SUCCESSFUL.
- [x] 9.2 `./gradlew :mobile:app:testDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (Debug + Release variant — confirms the screen-test exclude). — `testDevDebugUnitTest` (147 tests) + `testDevReleaseUnitTest` both green; `NearbyLocationGateScreenTest` correctly excluded from Release.
- [x] 9.3 iOS compiles: `./gradlew :mobile:app:compileKotlinIosSimulatorArm64` (or the project's iOS link task) green. — BUILD SUCCESSFUL (K/N CoreLocation/UIKit signatures verified against the platform klibs).
- [x] 9.4 Confirm NO new Gradle module added (work is within `:mobile:app` + `:shared:resources`) → no `dev/module-descriptions.txt` / `sync-readme.sh` step needed. — confirmed; files added to existing modules only.

## 10. Manual device/sim verification (platform actuals are not unit-tested)

- [x] 10.1 Android device/emulator: cold launch → Nearby → consent rationale → OS coarse-permission prompt → grant → real-location nearby feed renders. Verify deny → denial state + "Buka Pengaturan" round-trips to settings and back. Confirm no `lat`/`lng` value appears in logcat (the debug `Logging` request line is masked). — verified on a physical Samsung A17 (Android 16): consent modal ✓, decline → denial state ✓, "Buka Pengaturan" → self-scoped app-settings ✓, enable-in-Settings → BACK → **gate auto-refreshes (ON_RESUME)** ✓, logcat shows `lat=***&lng=***` (zero raw-coordinate leaks) ✓. Also a full e2e against **staging**: real Google sign-in (200) → location → `GET /timeline/nearby?lat=***&lng=***` (200, masked) → app rendered the real rate-limit hard-cap state. Surfaced + fixed a real bug during this pass: the gate refreshed only on first composition (`LaunchedEffect(Unit)`), so returning from Settings didn't re-check — fixed via `LifecycleEventEffect(ON_RESUME)` + a regression test.
- [ ] 10.2 iOS simulator (per the iOS-sim verification recipe): launch → Nearby → rationale → when-in-use prompt → grant → feed renders; deny → denial state + Settings deep link. Confirm no coordinate appears in device logs.

## 11. Docs, follow-ups & archive

- [x] 11.1 Confirm the `mobile-location-permission-flow` `FOLLOW_UPS.md` entry stays absent (already migrated to roadmap Pre-Launch #6 on 2026-06-01); no new dangling entry. Add any genuinely-new deferrals surfaced during apply (e.g., precise-location/radius-slider interplay) only if out of scope. — verified: only the historical audit-log narrative mentions it; no live open entry; no new out-of-scope deferral surfaced.
- [x] 11.2 `openspec validate mobile-location-permission-flow --strict` green before archive; at archive, `openspec validate --specs mobile-location --specs mobile-nearby-timeline --strict` green. — `openspec validate mobile-location-permission-flow --strict` green (the `--specs` check runs at `/opsx:archive`).
- [x] 11.3 Update PR title/body at each phase boundary per [`openspec/project.md`](../../../openspec/project.md) § "PR title and body MUST stay current" (first feat commit → `feat(mobile): mobile-location-permission-flow`; archive → merge-ready shape). — apply-phase done (PR #136 retitled `feat(mobile): …` + implementation body); archive-phase update runs in `/opsx:archive`.
