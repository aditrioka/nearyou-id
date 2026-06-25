package id.nearyou.app.screens.timeline

import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationGateUiState
import id.nearyou.app.location.LocationPermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage of [LocationGateViewModel] — the `HomeRoute`-scoped host for the Nearby pre-fetch
 * [id.nearyou.app.location.LocationGate] (2026-06-10 audit, finding 05-#12: the gate moved off the
 * `remember`-scoped composition into a retained VM). Pins that the VM forwards refresh / accept / decline to
 * the gate (so [LocationGateViewModel.uiState] reflects the gate projection — `Loading` until the first
 * [LocationGateViewModel.refresh]) and that [LocationGateViewModel.openAppSettings] deep-links via the
 * controller. The gate's own rationale-vs-prompt + no-re-prompt-on-re-entry decision logic keeps its
 * dedicated `LocationGateTest`.
 *
 * `viewModelScope` dispatches on `Dispatchers.Main`; an [UnconfinedTestDispatcher] is installed as Main so
 * the launched refresh/accept coroutines run eagerly against the (non-suspending) fake. [uiState] re-exposes
 * the gate's hot `MutableStateFlow`, so `uiState.value` reflects each projection without activating a share.
 * Mirrors `AppShellViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationGateViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading_thenRefreshOnGranted_projectsGranted_withoutPrompt() {
        val fake = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
        val viewModel = LocationGateViewModel(fake)
        assertEquals(LocationGateUiState.Loading, viewModel.uiState.value, "starts Loading before the first refresh")

        viewModel.refresh()

        assertEquals(LocationGateUiState.Granted, viewModel.uiState.value)
        assertEquals(0, fake.requestCount, "refresh must never fire the OS prompt")
    }

    @Test
    fun refreshOnNotDetermined_projectsRationale_withoutPrompt() {
        val fake = FakeLocationPermissionController(current = LocationPermissionStatus.NOT_DETERMINED)
        val viewModel = LocationGateViewModel(fake)

        viewModel.refresh()

        assertEquals(LocationGateUiState.Rationale, viewModel.uiState.value)
        assertEquals(0, fake.requestCount, "the rationale shows before any OS prompt")
    }

    @Test
    fun onRationaleAccepted_firesPromptOnce_thenProjectsResult() {
        val fake =
            FakeLocationPermissionController(
                current = LocationPermissionStatus.NOT_DETERMINED,
                afterRequest = LocationPermissionStatus.GRANTED,
            )
        val viewModel = LocationGateViewModel(fake)
        viewModel.refresh()

        viewModel.onRationaleAccepted()

        assertEquals(1, fake.requestCount, "accepting the rationale fires the OS prompt exactly once")
        assertEquals(LocationGateUiState.Granted, viewModel.uiState.value)
    }

    @Test
    fun onRationaleDeclined_dropsToDenied_withoutPrompt() {
        val fake = FakeLocationPermissionController(current = LocationPermissionStatus.NOT_DETERMINED)
        val viewModel = LocationGateViewModel(fake)
        viewModel.refresh()

        viewModel.onRationaleDeclined()

        assertEquals(LocationGateUiState.Denied, viewModel.uiState.value)
        assertEquals(0, fake.requestCount, "declining must not force the OS prompt")
    }

    @Test
    fun openAppSettings_deepLinksViaController() {
        val fake = FakeLocationPermissionController(current = LocationPermissionStatus.DENIED)
        val viewModel = LocationGateViewModel(fake)

        viewModel.openAppSettings()

        assertEquals(1, fake.openAppSettingsCount, "openAppSettings deep-links to the OS settings screen")
    }
}
