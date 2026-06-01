## 1. Pre-implementation gates & dependency pin

- [ ] 1.1 **Pre-implementation library re-check (MUST — new pin)**: run a fresh dated `WebSearch` (`"play-services-location FusedLocationProviderClient 2026"`, `"android coarse location best practice 2026"`) per [`openspec/project.md`](../../../openspec/project.md) § Pre-implementation library re-check. Confirm fused-over-LocationManager still holds (design D1 recorded 2026-06-02); drop a one-line `re-check 2026-MM-DD confirms…` note in the first feat commit body. If a materially-better alternative surfaced → STOP and surface to the user.
- [ ] 1.2 Pin `play-services-location` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (version + `libs.*` alias); add to `:mobile:app` `androidMain` dependencies only.
- [ ] 1.3 Add a Version Pinning Decisions Log row in [`docs/09-Versions.md`](../../../docs/09-Versions.md) recording the fused-vs-LocationManager rationale + the dated re-check (mirrors the `play-services-auth` / Credential Manager row shape).

## 2. commonMain seams (mobile-location capability)

- [ ] 2.1 Add `LocationPermissionController` interface in `mobile/app/src/commonMain/.../location/` — Compose-free status enum (`GRANTED` / `DENIED` / `NOT_DETERMINED`), a `suspend` request operation (consent rationale → OS prompt), and `openAppSettings()`. No platform symbols in commonMain (spec: "Interface is platform-free in commonMain").
- [ ] 2.2 Add the pure permission-status → UI-state projection (Compose-free, deterministic; mirrors `NearbyTimelineUiState` / `AgeGateUiState`) — maps `GRANTED`/`DENIED`/`NOT_DETERMINED` to proceed-to-fetch / denied-fallback / prompt-rationale. Carries no coordinates.

## 3. Platform actuals (Android fused, iOS CLLocationManager)

- [ ] 3.1 Android: real `LocationProvider` actual via `FusedLocationProviderClient` (best-effort `getCurrentLocation` balanced-power + `getLastLocation` fallback), **coarse only**. MUST NOT log the coordinate (spec: "Acquired coordinate is never logged").
- [ ] 3.2 Android: `LocationPermissionController` actual — runtime `ACCESS_COARSE_LOCATION` request via the existing `CurrentActivityHolder` / Activity-result seam; `openAppSettings()` → app-details settings intent. Register both in `androidMain` `PlatformModule.kt`.
- [ ] 3.3 iOS: real `LocationProvider` actual via `CLLocationManager` (**when-in-use**, reduced accuracy acceptable per design D2/Open Q3). MUST NOT log the coordinate.
- [ ] 3.4 iOS: `LocationPermissionController` actual — `requestWhenInUseAuthorization`; `openAppSettings()` → `UIApplication.openSettingsURLString`. Register both in `iosMain` `PlatformModule.kt`. No "Always"/background authorization (spec: "Coarse-only, no fine, no background").

## 4. Consent rationale modal + strings

- [ ] 4.1 Add the UU-PDP location-consent rationale (why/what/how-often) + denial-state copy ("*Aktifkan lokasi untuk lihat postingan sekitar*") + "*Buka Pengaturan*" CTA strings to `:shared:resources` (Bahasa Indonesia; `Res.string.*`).
- [ ] 4.2 Build the consent-rationale modal composable — all copy via `stringResource(Res.string.*)`, zero hardcoded literals (spec: "Consent-modal copy is fully resource-backed"); surfaced by the controller's request path *before* the OS prompt.

## 5. Koin binding swap

- [ ] 5.1 Remove `single<LocationProvider> { StubLocationProvider() }` from the commonMain `MobileModule`; bind the real provider in each `platformModule` (Android/iOS). Retain `StubLocationProvider` in commonMain as the test double (spec: "Production binds the real provider; stub retained for tests").

## 6. Nearby screen gate + denial state (mobile-nearby-timeline delta)

- [ ] 6.1 Gate the Nearby surface on `LocationPermissionController` before fetching: granted → existing `NearbyTimelineFlow.loadFirstPage()` (unchanged); denied/unavailable → pre-fetch denial state, no fetch (spec: "Nearby feed is gated…"). Resolve Open Q1 (granted-but-no-fix surfacing) — keep `NearbyTimelineRepository`'s outcome mapping unchanged.
- [ ] 6.2 Render the denial state: "Aktifkan lokasi…" copy + "Buka Pengaturan" CTA → `openAppSettings()`. Confirm no re-nagging on every Nearby visit (Open Q2).
- [ ] 6.3 Verify `NearbyTimelineRepository` is unmodified (no new `NearbyTimelineOutcome` member; mapping byte-for-byte unchanged — spec: "Repository outcome mapping is unchanged").

## 7. Platform configuration

- [ ] 7.1 AndroidManifest: add `<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />`; confirm NO `ACCESS_FINE_LOCATION` (spec: "Android manifest declares coarse and not fine").
- [ ] 7.2 iOS `Info.plist`: add a non-empty `NSLocationWhenInUseUsageDescription` justification string.

## 8. Tests

- [ ] 8.1 `commonTest`: permission-status → UI-state projection test (granted / denied / not-determined) — deterministic, no platform dep.
- [ ] 8.2 `commonTest`/shared: fake-`LocationPermissionController` test exercising request-shows-rationale-then-prompt + the decline path.
- [ ] 8.3 Screen test (fake controller + `FakeNearbyTimelineFlow`): denied → denial copy + "Buka Pengaturan" + fetch count `0`; granted → fetch path runs; CTA → `openAppSettings()`.
- [ ] 8.4 Add any new Robolectric `*ScreenTest` to the `mobile/app/build.gradle.kts` Release-variant test-exclude block (verify via `:mobile:app:testDevReleaseUnitTest`, not only Debug — precedent [PR #126](https://github.com/aditrioka/nearyou-id/pull/126)).

## 9. Build & lint verification

- [ ] 9.1 `./gradlew ktlintCheck detekt` green (both frameworks — CI runs both).
- [ ] 9.2 `./gradlew :mobile:app:testDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (Debug + Release variant — confirms the screen-test exclude).
- [ ] 9.3 iOS compiles: `./gradlew :mobile:app:compileKotlinIosSimulatorArm64` (or the project's iOS link task) green.
- [ ] 9.4 Confirm NO new Gradle module added (work is within `:mobile:app` + `:shared:resources`) → no `dev/module-descriptions.txt` / `sync-readme.sh` step needed.

## 10. Manual device/sim verification (platform actuals are not unit-tested)

- [ ] 10.1 Android device/emulator: cold launch → Nearby → consent rationale → OS coarse-permission prompt → grant → real-location nearby feed renders. Verify deny → denial state + "Buka Pengaturan" round-trips to settings and back.
- [ ] 10.2 iOS simulator (per the iOS-sim verification recipe): launch → Nearby → rationale → when-in-use prompt → grant → feed renders; deny → denial state + Settings deep link. Confirm no coordinate appears in device logs.

## 11. Docs, follow-ups & archive

- [ ] 11.1 Confirm the `mobile-location-permission-flow` `FOLLOW_UPS.md` entry stays absent (already migrated to roadmap Pre-Launch #6 on 2026-06-01); no new dangling entry. Add any genuinely-new deferrals surfaced during apply (e.g., precise-location/radius-slider interplay) only if out of scope.
- [ ] 11.2 `openspec validate mobile-location-permission-flow --strict` green before archive; at archive, `openspec validate --specs mobile-location --specs mobile-nearby-timeline --strict` green.
- [ ] 11.3 Update PR title/body at each phase boundary per [`openspec/project.md`](../../../openspec/project.md) § "PR title and body MUST stay current" (first feat commit → `feat(mobile): mobile-location-permission-flow`; archive → merge-ready shape).
