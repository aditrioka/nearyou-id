package id.nearyou.app.location

/**
 * Pure, Compose-free projection of the Nearby location-gate result, mirroring `NearbyTimelineUiState`
 * / `AgeGateUiState`. Each state is a deterministic function of the [LocationPermissionStatus] — no
 * wall-clock / platform / Compose dependency — and carries NO coordinates (the projection is the gate
 * decision, never a location). Per `openspec/specs/mobile-location` § "Permission status maps to a
 * pure, Compose-free UI state".
 */
sealed interface LocationGateUiState {
    /** Status not yet known (the pre-query window) → a neutral spinner. */
    data object Loading : LocationGateUiState

    /** `NOT_DETERMINED` → surface the UU-PDP consent rationale (which, on accept, fires the prompt). */
    data object Rationale : LocationGateUiState

    /** `GRANTED` → proceed to the existing Nearby fetch path. */
    data object Granted : LocationGateUiState

    /** `DENIED` → the denial fallback ("Aktifkan lokasi…" + "Buka Pengaturan"). */
    data object Denied : LocationGateUiState
}

/**
 * Maps a [LocationPermissionStatus] to its gate UI state. Exhaustive over the enum — no generic
 * fallthrough:
 * - `GRANTED` ⇒ [LocationGateUiState.Granted] (proceed-to-fetch).
 * - `DENIED` ⇒ [LocationGateUiState.Denied] (denied-fallback).
 * - `NOT_DETERMINED` ⇒ [LocationGateUiState.Rationale] (prompt-rationale).
 */
fun locationGateUiState(status: LocationPermissionStatus): LocationGateUiState =
    when (status) {
        LocationPermissionStatus.GRANTED -> LocationGateUiState.Granted
        LocationPermissionStatus.DENIED -> LocationGateUiState.Denied
        LocationPermissionStatus.NOT_DETERMINED -> LocationGateUiState.Rationale
    }
