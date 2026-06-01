## ADDED Requirements

### Requirement: Nearby feed is gated on location permission with a denial fallback

The Nearby surface SHALL consult the `mobile-location` `LocationPermissionController` BEFORE fetching: when permission is granted it SHALL proceed to the existing `NearbyTimelineFlow.loadFirstPage()` fetch path; when permission is denied or unavailable it SHALL render a **pre-fetch** location-permission-denied state and SHALL NOT invoke the fetch. The denial state SHALL show `stringResource(Res.string.<nearby location denied>)` ("*Aktifkan lokasi untuk lihat postingan sekitar*") plus a "*Buka Pengaturan*" CTA that invokes `LocationPermissionController.openAppSettings()`. This denial state is a pre-fetch gate state, distinct from the six fetch-outcome states in the § "Screen state mapping covers loading, content, empty, error, and both rate-limit states" requirement (which is unchanged).

#### Scenario: Denied permission renders the fallback and issues no fetch
- **GIVEN** a fake `LocationPermissionController` reporting `DENIED` AND a `FakeNearbyTimelineFlow` counting fetch invocations
- **WHEN** the Nearby surface is composed
- **THEN** the rendered tree contains a node whose text matches `stringResource` of the "Aktifkan lokasi…" denial copy AND a clickable "Buka Pengaturan" node AND the fetch invocation count is `0`

#### Scenario: Granted permission drives the existing fetch path
- **GIVEN** a fake `LocationPermissionController` reporting `GRANTED` AND a `FakeNearbyTimelineFlow`
- **WHEN** the Nearby surface is composed
- **THEN** `NearbyTimelineFlow.loadFirstPage()` is invoked (the existing fetch path runs) AND no denial copy is rendered

#### Scenario: Buka Pengaturan CTA deep-links to settings
- **GIVEN** the denial state is rendered
- **WHEN** the "Buka Pengaturan" CTA is activated
- **THEN** `LocationPermissionController.openAppSettings()` is invoked

### Requirement: Default LocationProvider binding is the real device provider; repository mapping unchanged

The default **production** `LocationProvider` Koin binding SHALL be the real platform device-location provider (per the `mobile-location` capability), NOT `StubLocationProvider`; `StubLocationProvider` SHALL be retained as the test double. The `LocationProvider` interface signature (`suspend fun current(): LatLng`) SHALL remain unchanged. `NearbyTimelineRepository`'s status-driven `NearbyTimelineOutcome` mapping (HTTP 200→`Loaded`, 400→retryable `Error`, 5xx/IO→`NetworkError`, 401 delegated to the shipped `Auth` plugin) SHALL remain byte-for-byte unchanged — location-permission denial is handled by the screen's pre-fetch gate, not by a new repository outcome.

#### Scenario: Real provider is the default production binding
- **WHEN** inspecting the production Koin modules and `commonTest`
- **THEN** the production `LocationProvider` is the real platform provider (not `StubLocationProvider`) AND `StubLocationProvider` returning `LatLng(-6.2, 106.8)` remains the test double

#### Scenario: Repository outcome mapping is unchanged
- **WHEN** comparing `NearbyTimelineRepository`'s status→`NearbyTimelineOutcome` mapping before and after this change
- **THEN** the mapping is unchanged (no new `NearbyTimelineOutcome` member is introduced for location denial; the gate lives in the screen layer)

## REMOVED Requirements

### Requirement: LocationProvider stub supplies a fixed coordinate; real location is deferred

**Reason**: Fulfilled by the `mobile-location-permission-flow` change. The deferred real-location provider, runtime permission request, UU-PDP consent modal, and Nearby permission-denial fallback now ship (see the new `mobile-location` capability + the two ADDED requirements above). The requirement's "No platform location or permission API is referenced" scenario is now intentionally false (Android `FusedLocationProviderClient` + `ACCESS_COARSE_LOCATION` and iOS `CLLocationManager` are introduced), and its "FOLLOW_UPS tracks the location-permission follow-up" scenario is obsolete — that entry was migrated out of `FOLLOW_UPS.md` to [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Pre-Launch #6 on 2026-06-01.

**Migration**: Production binds the real platform `LocationProvider` (Android fused/coarse via `play-services-location`, iOS `CLLocationManager` when-in-use); `StubLocationProvider(-6.2, 106.8)` is retained as the test double. The Nearby screen gates the fetch on granted permission and renders a denial state otherwise (the ADDED "Nearby feed is gated on location permission" requirement). `NearbyTimelineRepository`'s status→outcome mapping is unchanged (the ADDED "Default LocationProvider binding…repository mapping unchanged" requirement). The `LocationProvider` interface signature is unchanged.
