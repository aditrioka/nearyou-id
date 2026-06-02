package id.nearyou.app.location

/**
 * Test-only [LocationPermissionController]. NOT an `expect`/`actual` — a plain commonTest fake that
 * reports a programmable status, flips to [afterRequest] when [request] is invoked, and counts both
 * [request] and [openAppSettings] invocations so the gate scenarios (rationale-then-prompt, decline,
 * no-re-prompt-on-re-entry, the "Buka Pengaturan" CTA) can be driven without a platform location SDK
 * — mirroring the `mobile-auth-signin` `FakeGoogleSignInGateway` idiom.
 */
class FakeLocationPermissionController(
    current: LocationPermissionStatus = LocationPermissionStatus.NOT_DETERMINED,
    private val afterRequest: LocationPermissionStatus = LocationPermissionStatus.GRANTED,
) : LocationPermissionController {
    private var currentStatus: LocationPermissionStatus = current

    var requestCount: Int = 0
        private set

    var openAppSettingsCount: Int = 0
        private set

    override suspend fun status(): LocationPermissionStatus = currentStatus

    override suspend fun request(): LocationPermissionStatus {
        requestCount++
        currentStatus = afterRequest
        return currentStatus
    }

    override fun openAppSettings() {
        openAppSettingsCount++
    }

    /**
     * Test hook: simulate the OS permission changing OUTSIDE the app (e.g. the user toggling it in
     * the system Settings screen). Used by the ON_RESUME re-check regression test — the gate must
     * re-query and reflect this on the next foreground entry without a cold restart.
     */
    fun simulateExternalStatusChange(status: LocationPermissionStatus) {
        currentStatus = status
    }
}
