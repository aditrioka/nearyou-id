package id.nearyou.app.location

/**
 * Compose-free, platform-free location-permission status (coarse). Android terminal-denial
 * ("don't ask again" / permanently denied) and iOS `restricted` collapse into [DENIED] (whose
 * escape hatch is the "Buka Pengaturan" CTA) rather than adding enum members — per
 * `openspec/specs/mobile-location` § "Permission status maps to a pure, Compose-free UI state".
 */
enum class LocationPermissionStatus {
    GRANTED,
    DENIED,
    NOT_DETERMINED,
}

/**
 * The commonMain seam for the location-permission ceremony, with Android / iOS actuals (mirroring
 * the `mobile-auth-signin` `GoogleSignInGateway` testability idiom — production binds a platform
 * actual in each `platformModule`; a `FakeLocationPermissionController` drives tests).
 *
 * Platform-free by contract: this interface references NO platform location/permission API
 * (`FusedLocationProviderClient`, `CLLocationManager`, `ActivityCompat`, `ACCESS_COARSE_LOCATION`)
 * — `openspec/specs/mobile-location` § "Interface is platform-free in commonMain".
 *
 * Coarse only: the actuals request `ACCESS_COARSE_LOCATION` (Android) / when-in-use (iOS) — never
 * fine, never background/"always".
 */
interface LocationPermissionController {
    /** The current OS location-permission status. Cheap and side-effect-free (shows no prompt). */
    suspend fun status(): LocationPermissionStatus

    /**
     * Fires the OS permission prompt and suspends until the user responds, returning the resulting
     * status. The UU-PDP consent rationale is surfaced by the Nearby gate (the [LocationGate]
     * orchestration) BEFORE this is invoked — this is the OS-prompt step only. Sensible to call from
     * [LocationPermissionStatus.NOT_DETERMINED]; from a terminal [LocationPermissionStatus.DENIED]
     * the OS shows nothing, so the "Buka Pengaturan" deep link ([openAppSettings]) is the only path
     * forward.
     */
    suspend fun request(): LocationPermissionStatus

    /** Deep-links to the OS app-settings screen so the user can flip a denied permission. */
    fun openAppSettings()
}
