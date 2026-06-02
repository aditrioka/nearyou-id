package id.nearyou.app.timeline

import id.nearyou.distance.LatLng

/**
 * Supplies the viewer coordinate for the Nearby fetch. Reuses `id.nearyou.distance.LatLng` (already
 * a dependency via `:shared:distance`).
 *
 * The **production** Koin binding is the real platform device-location provider — `AndroidLocationProvider`
 * (`FusedLocationProviderClient`, coarse) / `IosLocationProvider` (`CLLocationManager`, when-in-use),
 * bound in each `platformModule` by `mobile-location-permission-flow`. The interface signature
 * (`suspend fun current(): LatLng`) is unchanged from the `mobile-nearby-timeline` contract; a real
 * provider signals "permission granted but no fix" via `LocationUnavailableException` rather than a
 * sentinel coordinate. The Nearby screen gates the fetch on a granted location permission ahead of
 * calling [NearbyTimelineRepository], so the repository's status→outcome mapping stays unchanged.
 * [StubLocationProvider] is retained as the test double.
 */
interface LocationProvider {
    suspend fun current(): LatLng
}

/**
 * Fixed-coordinate stub returning Jakarta `LatLng(-6.2, 106.8)`. Retained as the **test double** after
 * `mobile-location-permission-flow` swapped the production binding to the real platform providers
 * (`openspec/specs/mobile-location` § "Production binds the real provider; stub retained for tests").
 * Deliberately references NO platform location/permission API (`FusedLocationProviderClient`,
 * `CLLocationManager`, `ACCESS_FINE_LOCATION`) — those live in the androidMain/iosMain actuals.
 */
class StubLocationProvider : LocationProvider {
    override suspend fun current(): LatLng = LatLng(lat = -6.2, lng = 106.8)
}
