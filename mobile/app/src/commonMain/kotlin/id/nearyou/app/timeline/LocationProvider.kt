package id.nearyou.app.timeline

import id.nearyou.distance.LatLng

/**
 * Supplies the viewer coordinate for the Nearby fetch. Reuses `id.nearyou.distance.LatLng` (already
 * a dependency via `:shared:distance`).
 *
 * The default Koin binding is [StubLocationProvider]. Real device location, the runtime permission
 * request, the UU-PDP consent modal, and the permission-denial fallback are deferred to a dedicated
 * follow-up `mobile-location-permission-flow` — which will swap the Koin binding for a real
 * (fused / `CLLocationManager`) provider WITHOUT modifying [NearbyTimelineRepository] or the screen.
 */
interface LocationProvider {
    suspend fun current(): LatLng
}

/**
 * Fixed-coordinate stub returning Jakarta `LatLng(-6.2, 106.8)`. Sufficient to demo rendering + all
 * screen states + the Phase 2 coordinate-fuzzing audit (which is coordinate-agnostic). Deliberately
 * references NO platform location/permission API (`FusedLocationProviderClient`, `CLLocationManager`,
 * `ACCESS_FINE_LOCATION`) — that surface is the `mobile-location-permission-flow` follow-up.
 */
class StubLocationProvider : LocationProvider {
    override suspend fun current(): LatLng = LatLng(lat = -6.2, lng = 106.8)
}
