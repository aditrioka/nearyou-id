## RENAMED Requirements

- FROM: `### Requirement: Nearby fetch targets the canonical endpoint with fixed radius and session header`
- TO: `### Requirement: Nearby fetch targets the canonical endpoint with the selected radius and session header`

## MODIFIED Requirements

### Requirement: Nearby fetch targets the canonical endpoint with the selected radius and session header

`NearbyTimelineApiClient` SHALL issue `GET /api/v1/timeline/nearby` (the canonical endpoint per `openspec/specs/nearby-timeline/spec.md`) with query parameters `lat` and `lng` from the `LocationProvider` and `radius_m` from the **selected radius position** supplied by `NearbyTimelineViewModel` — defaulting to `20000` (the 20 km position) and constrained to the 10/20/50/100 km set per `openspec/specs/mobile-nearby-radius-slider/spec.md`. The former single named constant `NEARBY_RADIUS_M` (`= 20000`) is generalized into this selected value (it MAY remain as the 20 km default). The request SHALL carry the `X-Session-Id` header from `SessionIdProvider` (a value matching `^[A-Za-z0-9-]{1,64}$`). The first-page request SHALL omit the `cursor` parameter. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment).

#### Scenario: First-page request shape at the default radius
- **GIVEN** a Ktor MockEngine capturing outbound requests AND a `StubLocationProvider` returning `LatLng(-6.2, 106.8)` AND the default 20 km radius position selected
- **WHEN** `NearbyTimelineApiClient.fetchNearby(...)` runs for the first page
- **THEN** the captured request is `GET` with path `/api/v1/timeline/nearby` AND query `lat=-6.2`, `lng=106.8`, `radius_m=20000`, AND NO `cursor` parameter

#### Scenario: First-page request shape at a non-default selected radius
- **GIVEN** a Ktor MockEngine capturing outbound requests AND a `StubLocationProvider` returning `LatLng(-6.2, 106.8)` AND the 50 km radius position selected (a Premium session)
- **WHEN** `NearbyTimelineApiClient.fetchNearby(...)` runs for the first page
- **THEN** the captured request query carries `radius_m=50000` (the selected position, not the fixed `20000`)
