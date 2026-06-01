## Context

The Nearby feed (shipped `mobile-nearby-timeline-screen`, [PR #128](https://github.com/aditrioka/nearyou-id/pull/128)) acquires the viewer coordinate from a `LocationProvider` seam whose only binding today is `StubLocationProvider` → fixed `LatLng(-6.2, 106.8)`. The seam was built explicitly for this swap ([`LocationProvider.kt`](../../../mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/LocationProvider.kt) KDoc names this change). The mobile app already has a mature expect/actual + `platformModule` pattern (auth, Mobile #3): `commonMain` interfaces + Android/iOS actuals, an Android `CurrentActivityHolder` for Activity-scoped flows, and the `GoogleSignInGateway`-interface-+-fake testability idiom.

Constraints:
- **Mobile-strings invariant**: no hardcoded UI strings — all copy via `stringResource(Res.string.*)` (`:shared:resources`).
- **Privacy / UU-PDP**: real device coordinates enter the app for the first time. Coarse accuracy only; when-in-use only; coordinates MUST NOT be logged.
- **Shipped contract**: `mobile-nearby-timeline` promised this follow-up would swap the binding "without modifying `NearbyTimelineRepository` or `NearbyTimelineScreen`." The repository part is honored; the screen part cannot be (a denial fallback is inherently a screen state) — reconciled in the spec delta.

## Goals / Non-Goals

**Goals:**
- Replace the stub with a real device-location provider (Android fused/coarse, iOS `CLLocationManager` when-in-use) behind a testable commonMain seam.
- A UU-PDP location-consent rationale modal that precedes the OS prompt.
- A Nearby permission-denial fallback ("*Aktifkan lokasi…*" + "*Buka Pengaturan*" deep link).
- Keep `NearbyTimelineRepository`'s status-driven outcome mapping unchanged.

**Non-Goals:**
- Precise location + radius slider (→ `mobile-nearby-radius-slider`).
- Following/Global denial fallbacks (those screens don't exist yet) — behavior documented, implementation deferred.
- The broader analytics-consent onboarding screen; the post-creation location picker; stronger age assurance (roadmap Pre-Launch #7).
- Background/"always" location; continuous location streaming (one-shot acquisition per fetch is sufficient for the fixed 20km radius).

## Decisions

### D1 — Android location substrate: Google Play Services **fused** (`play-services-location`)
Use `FusedLocationProviderClient` (new `play-services-location` pin) over the framework `android.location.LocationManager`. Fused is Google's recommended API (system-wide cached fixes, better battery, coarse/fine honored), and the app *already* depends on Play Services (`play-services-auth` via Credential Manager), so this adds no new dependency *class*. iOS uses the system `CLLocationManager` (no Pod).

*Alternatives considered*: framework `LocationManager` — no GMS dependency (relevant only for de-Googled devices), but lower-quality fixes and more provider-juggling boilerplate; rejected since the app is already GMS-bound.

**Verified 2026-06-02 (propose-time WebSearch):** `FusedLocationProviderClient` remains Google's recommended Android location API over framework `LocationManager` (per [Google developers reference](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)). Counter-signal noted: some projects migrate *to* `LocationManager` to drop GMS for de-Googled devices ([fobo66 migration post](https://fobo66.dev/post/play-services-location-migration/)) — not applicable here given the existing Credential Manager dependency. This is also re-checked at `/opsx:apply` per [`openspec/project.md`](../../../openspec/project.md) § Pre-implementation library re-check (new pin).

### D2 — Granularity: **coarse/approximate only**
The Free tier is a fixed 20 km radius ([`docs/02-Product.md`](../../../docs/02-Product.md):88,167 — "stuck at 20km"), and [`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md):71 prescribes approximate location for the 10-20km band. Request `ACCESS_COARSE_LOCATION` only on Android (no `ACCESS_FINE_LOCATION`); accept reduced accuracy on iOS ("Precise: Off" is fine). This is the privacy-minimizing choice and aligns with UU-PDP data minimization. Precise location is deferred with the radius slider.

### D3 — Denial propagation: **gate in the screen layer; repository untouched** (THE crux)
The shipped `LocationProvider.current(): LatLng` cannot express "denied," and `NearbyTimelineRepository` calls it unconditionally. Three options were weighed:
- **(a) Screen-layer permission gate** — a new `LocationPermissionController` seam; the Nearby screen checks permission *before* invoking the fetch. Granted → call the existing `NearbyTimelineFlow.loadFirstPage()` unchanged. Denied → render a pre-fetch denial state; the repo is never called. **CHOSEN.**
- (b) `current()` throws a typed `LocationPermissionDeniedException`; repo catches → new `NearbyTimelineOutcome` member. Rejected — modifies the repo's sealed outcome + mapping (breaks the shipped promise) and conflates permission with HTTP-status outcomes.
- (c) Evolve `LocationProvider` to return a sealed `LocationResult`. Rejected — changes the interface signature, the repo call site, and the stub; largest blast radius.

Rationale: (a) keeps `NearbyTimelineRepository`'s status→outcome mapping **byte-for-byte unchanged** (the strongest part of the shipped promise) and adds exactly one new *screen* state, which is orthogonal to the six fetch-outcome states. The denial state is a **pre-fetch gate**, not a seventh fetch outcome — so the shipped "Screen state mapping covers … six states" requirement stays unmodified. Ownership: `mobile-location` owns the reusable controller/provider/consent machinery; the `mobile-nearby-timeline` delta owns the screen's gate + the Koin binding swap.

Granted-but-no-fix (permission granted yet no coordinate obtainable — GPS off, timeout): the real provider performs best-effort acquisition (fused `getCurrentLocation` with a balanced-power priority + `getLastLocation` fallback). The exact surfacing of a total-acquisition failure (provider throws → screen maps to the **existing** retryable error state, vs the controller pre-acquires) is an apply-time refinement that, in all variants, leaves the repo's outcome enum unchanged — recorded in Open Questions.

### D4 — Trigger point: **contextual at the Nearby surface**
The consent rationale → OS prompt fires when the authenticated user lands on the Nearby feed and permission is not yet granted (matching the denial fallback being a Nearby-screen state). It does NOT couple to the unbuilt analytics-consent screen, even though the eventual onboarding order is age-gate → analytics-consent → location ([`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md):57). When the onboarding consent screen ships, it can pre-warm permission, but the Nearby gate remains the backstop.

### D5 — Testability: commonMain seam + fakes; platform actuals thin
The permission/consent orchestration is a commonMain `LocationPermissionController` interface (fake-driven in tests, mirroring `AuthFlow` / `GoogleSignInGateway`). The permission status → UI-state mapping is a **pure, Compose-free projection** (mirroring `NearbyTimelineUiState` / `AgeGateUiState`) — deterministically unit-testable in `commonTest`. Platform actuals (Android fused + permission request; iOS `CLLocationManager`) are thin and NOT `commonTest`-covered; they are exercised by manual Android-device + iOS-sim verification (a deploy/smoke task). Any new Robolectric `*ScreenTest` is added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (the `ui-test-manifest` host activity is debug-only — precedent [PR #126](https://github.com/aditrioka/nearyou-id/pull/126)).

## Risks / Trade-offs

- **[Repo "untouched" promise vs granted-but-no-fix]** → D3 keeps the repo's outcome enum unchanged in every variant; total-acquisition failure reuses the existing retryable error state. Exact catch-site resolved at apply (Open Question 1).
- **[GMS dependency on de-Googled devices]** → Accepted: the app is already GMS-bound via Credential Manager; non-GMS support is not an MVP goal.
- **[Coarse accuracy yields a coarser "nearby"]** → Acceptable: the Free radius is a fixed 20 km; coarse (~city-block) precision is well within tolerance and matches the privacy posture.
- **[iOS reduced-accuracy / "Precise: Off" returns a low-fidelity fix]** → Acceptable for the 20 km radius; revisit with the precise-location slider.
- **[Platform actuals are not unit-tested]** → Mitigated by the thin-actual design (logic in common, fakes in tests) + mandatory manual device/sim verification before archive.
- **[Coordinate leakage via logs]** → The provider MUST NOT log coordinates; `NearbyTimelineRepository.diagnosticLog` already carries no coordinates — preserve. (Mobile has no OTel yet; do not introduce coordinate attributes.)

## Migration Plan

1. Pin `play-services-location` (Android); add the `Info.plist` usage-description string and `ACCESS_COARSE_LOCATION` to the manifest.
2. Add the `mobile-location` commonMain seams (`LocationPermissionController`, the permission→state projection) + Android/iOS actuals + the real `LocationProvider` impls; add strings to `:shared:resources`.
3. Move the `LocationProvider` Koin binding from the commonMain `single<LocationProvider> { StubLocationProvider() }` to each `platformModule` (real provider); retain `StubLocationProvider` in commonMain for tests.
4. Add the screen gate + denial state to `NearbyTimelineScreen`/`HomeScreen`; wire the consent modal.
5. Manual verification on an Android device + iOS simulator (grant / deny / Settings round-trip).

**Rollback**: revert the binding move (re-bind `StubLocationProvider` in commonMain) — the rest of the surface degrades to the fixed-coordinate demo without breaking the build. No backend or schema changes to roll back.

## Open Questions

1. **Granted-but-no-fix surfacing**: provider throws (screen maps to the existing retryable error state) vs the controller pre-acquires before invoking the flow. Both keep the repo outcome enum unchanged; decide at apply based on the simplest `NearbyTimelineScreen` wiring.
2. **Permission re-prompt UX**: after a hard "deny" Android no longer shows the system dialog — confirm the "Buka Pengaturan" deep link is the only path and that the rationale modal isn't re-shown on every Nearby visit (avoid nagging). Resolve against `docs/03-UX-Design.md` at apply.
3. **iOS reduced-accuracy request shape**: whether to set `desiredAccuracy = kCLLocationAccuracyReduced` explicitly or rely on the user's Precise toggle. Defer to the iOS-actual implementation; does not affect the commonMain contract.
