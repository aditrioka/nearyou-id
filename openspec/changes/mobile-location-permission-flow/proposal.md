## Why

The flagship Nearby screen (shipped in `mobile-nearby-timeline-screen`, [PR #128](https://github.com/aditrioka/nearyou-id/pull/128)) renders posts around a **hardcoded Jakarta coordinate** (`StubLocationProvider` → `LatLng(-6.2, 106.8)`). NearYou is a location-based app; per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Pre-Launch #6, *"a location app cannot launch on a fixed coordinate."* This change fulfils the `LocationProvider` follow-up that `mobile-nearby-timeline` explicitly deferred: it swaps in a real device-location provider, the runtime permission request, the UU-PDP location-consent modal, and the Nearby permission-denial fallback — turning the flagship screen from a fixed-coordinate demo into a real "what's near me" surface.

## What Changes

- **Real device-location provider** (Android **fused** via `play-services-location`, **coarse/approximate** accuracy; iOS `CLLocationManager`, **when-in-use**) bound behind a commonMain testable seam. Replaces `StubLocationProvider` as the default Koin binding (the stub is retained for tests).
- **UU-PDP location-consent rationale modal** shown *before* the OS permission prompt — explains why location is needed, what is collected, and how often it is accessed. All copy via Compose Multiplatform Resources. (Distinct from the unbuilt analytics-consent screen.)
- **Runtime permission request**: Android `ACCESS_COARSE_LOCATION` (via the existing `CurrentActivityHolder` / Activity-result seam); iOS when-in-use authorization. Background/"always" location is out of scope by design.
- **Nearby permission-denial fallback state**: when permission is denied/unavailable, the Nearby screen shows *"Aktifkan lokasi untuk lihat postingan sekitar"* + a *"Buka Pengaturan"* CTA that deep-links to the OS app-settings screen (no posts are fetched).
- **New `play-services-location` pin** in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (Android only; iOS `CLLocationManager` is a system framework). **Triggers the pre-implementation library re-check** ([`openspec/project.md`](../../../openspec/project.md) § Change Delivery Workflow).
- **Platform config**: AndroidManifest gains `ACCESS_COARSE_LOCATION`; iOS `Info.plist` gains `NSLocationWhenInUseUsageDescription`.
- **New Bahasa Indonesia strings** (consent-modal title/body, denial-state copy, "Buka Pengaturan" CTA) added to `:shared:resources`.

**Explicitly out of scope** (deferred):
- Precise location + the radius slider → `mobile-nearby-radius-slider` (existing follow-up).
- Following/Global denial fallbacks ([`docs/03-UX-Design.md`](../../../docs/03-UX-Design.md) §78-79) — those screens don't exist yet; the intended behavior is documented but implementation defers to when they ship.
- The broader analytics-consent onboarding screen — separate change.
- The post-creation location picker → `mobile-post-creation-screen`.
- Stronger age assurance (Apple/Google age signals) → roadmap Pre-Launch #7.

## Capabilities

### New Capabilities
- `mobile-location`: the mobile device-location + runtime-permission + UU-PDP consent surface — a commonMain location/permission abstraction with Android (fused, coarse) and iOS (`CLLocationManager`, when-in-use) implementations, the pre-permission consent rationale modal, and the contextual trigger that gates the Nearby feed on a known-granted location permission.

### Modified Capabilities
- `mobile-nearby-timeline`: the deferred Requirement "*LocationProvider stub supplies a fixed coordinate; real location is deferred*" is now fulfilled. The Requirement is rewritten so the default binding is the real provider (stub retained for tests), the screen gains a **location-permission-denied** state ahead of the fetch, and the now-stale scenario asserting a `FOLLOW_UPS.md` entry (the entry was migrated to roadmap Pre-Launch #6 on 2026-06-01) is superseded. `NearbyTimelineRepository`'s status-driven mapping is preserved unchanged (permission is gated ahead of the fetch per design D3).

## Impact

- **Module**: `:mobile:app` (commonMain seam + androidMain/iosMain actuals + the Nearby screen's denial state + DI binding swap); `:shared:resources` (new strings).
- **Dependencies**: new `play-services-location` (Android) — first location SDK on the classpath; consistent with the existing Play Services dependency (`play-services-auth` via Credential Manager).
- **Platform config**: AndroidManifest permission; iOS `Info.plist` usage-description string.
- **Privacy / invariants**: introduces real device coordinates on-device — the provider MUST NOT log coordinates; mobile-strings invariant applies to all new copy; coarse-only honors UU-PDP data minimization.
- **No backend changes** — consumes the already-shipped `GET /api/v1/timeline/nearby`.
- **Verification**: requires manual Android-device + iOS-sim permission-flow verification (platform actuals are not unit-testable in commonTest).
