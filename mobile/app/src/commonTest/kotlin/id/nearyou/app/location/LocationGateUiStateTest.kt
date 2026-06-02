package id.nearyou.app.location

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure coverage of the [locationGateUiState] projection (task 8.1) — the permission-status → gate
 * UI-state mapping, exercised without a Compose UI runner (mirroring `NearbyTimelineUiStateTest` /
 * `AgeGateUiStateTest`). Exhaustive over [LocationPermissionStatus]; deterministic, no platform dep.
 *
 * The enum has exactly three members because platform terminal-denial states (Android "don't ask
 * again" / permanently-denied, iOS `restricted`) are collapsed into `DENIED` by the platform actuals
 * (the `Android`/`Ios` controllers), NOT by adding enum members — so the projection only ever sees
 * `GRANTED` / `DENIED` / `NOT_DETERMINED`, and every terminal denial lands on [LocationGateUiState.Denied]
 * (whose escape hatch is the "Buka Pengaturan" CTA).
 */
class LocationGateUiStateTest {
    @Test
    fun `granted projects to Granted (proceed-to-fetch)`() {
        assertEquals(LocationGateUiState.Granted, locationGateUiState(LocationPermissionStatus.GRANTED))
    }

    @Test
    fun `denied projects to Denied (denied-fallback)`() {
        assertEquals(LocationGateUiState.Denied, locationGateUiState(LocationPermissionStatus.DENIED))
    }

    @Test
    fun `not-determined projects to Rationale (prompt-rationale)`() {
        assertEquals(LocationGateUiState.Rationale, locationGateUiState(LocationPermissionStatus.NOT_DETERMINED))
    }
}
