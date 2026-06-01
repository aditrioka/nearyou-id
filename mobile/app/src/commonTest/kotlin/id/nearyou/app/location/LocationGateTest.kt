package id.nearyou.app.location

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure coverage of the [LocationGate] orchestrator's rationale-vs-prompt decision logic (task 8.2),
 * driven by a [FakeLocationPermissionController] with `runTest` (no Compose runner). The modal
 * *render* is asserted in the Robolectric `NearbyLocationGateScreenTest`; here we assert the decision
 * logic: that the rationale precedes the prompt, that declining does not force the prompt, and that a
 * prior denial does NOT re-prompt on re-entry.
 */
class LocationGateTest {
    @Test
    fun `refresh on NOT_DETERMINED projects to Rationale without firing the OS prompt`() =
        runTest {
            val fake = FakeLocationPermissionController(current = LocationPermissionStatus.NOT_DETERMINED)
            val gate = LocationGate(fake)

            gate.refresh()

            assertEquals(LocationGateUiState.Rationale, gate.state.value)
            assertEquals(0, fake.requestCount, "refresh() must not fire the OS prompt (rationale shows first)")
        }

    @Test
    fun `accepting the rationale fires the OS prompt exactly once then projects the result`() =
        runTest {
            val fake =
                FakeLocationPermissionController(
                    current = LocationPermissionStatus.NOT_DETERMINED,
                    afterRequest = LocationPermissionStatus.GRANTED,
                )
            val gate = LocationGate(fake)

            gate.refresh()
            gate.onRationaleAccepted()

            assertEquals(1, fake.requestCount, "accepting the rationale fires the OS prompt exactly once")
            assertEquals(LocationGateUiState.Granted, gate.state.value)
        }

    @Test
    fun `accepting but being denied at the OS prompt projects to Denied`() =
        runTest {
            val fake =
                FakeLocationPermissionController(
                    current = LocationPermissionStatus.NOT_DETERMINED,
                    afterRequest = LocationPermissionStatus.DENIED,
                )
            val gate = LocationGate(fake)

            gate.refresh()
            gate.onRationaleAccepted()

            assertEquals(1, fake.requestCount)
            assertEquals(LocationGateUiState.Denied, gate.state.value)
        }

    @Test
    fun `declining the rationale drops to Denied without forcing the OS prompt`() =
        runTest {
            val fake = FakeLocationPermissionController(current = LocationPermissionStatus.NOT_DETERMINED)
            val gate = LocationGate(fake)

            gate.refresh()
            gate.onRationaleDeclined()

            assertEquals(LocationGateUiState.Denied, gate.state.value)
            assertEquals(0, fake.requestCount, "declining must not force the OS prompt")
        }

    @Test
    fun `a prior denial does not re-prompt on re-entry`() =
        runTest {
            val fake = FakeLocationPermissionController(current = LocationPermissionStatus.DENIED)
            val gate = LocationGate(fake)

            gate.refresh() // first entry
            assertEquals(LocationGateUiState.Denied, gate.state.value)
            gate.refresh() // re-composition
            gate.refresh() // re-entry

            assertEquals(0, fake.requestCount, "re-entry while DENIED must not increment the request count")
        }
}
