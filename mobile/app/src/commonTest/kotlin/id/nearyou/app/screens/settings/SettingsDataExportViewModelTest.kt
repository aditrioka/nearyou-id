package id.nearyou.app.screens.settings

import id.nearyou.app.data.dataexport.DataExportRequestOutcome
import id.nearyou.app.data.dataexport.DataExportStatusOutcome
import id.nearyou.app.data.dataexport.FakeDataExportFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage of [SettingsDataExportViewModel] (mobile-data-export-entry, mobile-settings). The status
 * UI-state projection is asserted over the FULL shipped vocabulary (`none`/`pending`/`processing`/`ready`/
 * `expired`/`failed`), plus the confirm / single-active-suppression / 401 / retryable-error paths.
 * `viewModelScope` dispatches on Main → an [UnconfinedTestDispatcher] runs the init/confirm coroutines
 * eagerly against the (non-suspending) fake, so state is asserted right after each call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDataExportViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vmWithStatus(status: DataExportStatusOutcome) = SettingsDataExportViewModel(FakeDataExportFlow(statusOutcome = status))

    @Test
    fun `init seeds status — none projects to None`() {
        val flow = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.None)
        val vm = SettingsDataExportViewModel(flow)
        assertEquals(1, flow.statusCount)
        assertEquals(DataExportUiState.None, vm.uiState.value)
    }

    @Test
    fun `init seeds status — pending and processing both project to InProgress`() {
        assertEquals(DataExportUiState.InProgress, vmWithStatus(DataExportStatusOutcome.InProgress).uiState.value)
    }

    @Test
    fun `init seeds status — ready projects to Ready carrying the deadline and url`() {
        val vm = vmWithStatus(DataExportStatusOutcome.Ready("2026-07-01T00:00:00Z", "https://r2.example/signed"))
        val state = vm.uiState.value
        assertTrue(state is DataExportUiState.Ready)
        assertEquals("2026-07-01T00:00:00Z", state.downloadExpiresAt)
        assertEquals("https://r2.example/signed", state.downloadUrl)
    }

    @Test
    fun `init seeds status — expired and failed project to their states`() {
        assertEquals(DataExportUiState.Expired, vmWithStatus(DataExportStatusOutcome.Expired).uiState.value)
        assertEquals(DataExportUiState.Failed, vmWithStatus(DataExportStatusOutcome.Failed).uiState.value)
    }

    @Test
    fun `status read failure is non-trapping — stays requestable and surfaces statusError`() {
        val vm = vmWithStatus(DataExportStatusOutcome.Unavailable)
        assertEquals(DataExportUiState.None, vm.uiState.value)
        assertTrue(vm.statusError.value)
        assertFalse(vm.terminal401.value)
        vm.consumeStatusError()
        assertFalse(vm.statusError.value)
    }

    @Test
    fun `status read 401 routes to sign-in`() {
        val vm = vmWithStatus(DataExportStatusOutcome.Terminal401)
        assertTrue(vm.terminal401.value)
    }

    @Test
    fun `confirm export success projects to InProgress`() {
        val flow =
            FakeDataExportFlow(
                statusOutcome = DataExportStatusOutcome.None,
                requestOutcome = DataExportRequestOutcome.Requested,
            )
        val vm = SettingsDataExportViewModel(flow)
        vm.confirmExport()
        assertEquals(1, flow.requestCount)
        assertEquals(DataExportUiState.InProgress, vm.uiState.value)
    }

    @Test
    fun `confirm export is suppressed while single-active in progress`() {
        val flow = FakeDataExportFlow(statusOutcome = DataExportStatusOutcome.InProgress)
        val vm = SettingsDataExportViewModel(flow)
        assertEquals(DataExportUiState.InProgress, vm.uiState.value)
        vm.confirmExport()
        assertEquals(0, flow.requestCount, "no POST is issued while an export is already in progress (single-active)")
    }

    @Test
    fun `confirm export after failed status issues a fresh POST`() {
        val flow =
            FakeDataExportFlow(
                statusOutcome = DataExportStatusOutcome.Failed,
                requestOutcome = DataExportRequestOutcome.Requested,
            )
        val vm = SettingsDataExportViewModel(flow)
        assertEquals(DataExportUiState.Failed, vm.uiState.value)
        vm.confirmExport()
        assertEquals(1, flow.requestCount, "a failed export is not single-active-locked — a fresh request issues a POST")
        assertEquals(DataExportUiState.InProgress, vm.uiState.value)
    }

    @Test
    fun `confirm export 401 routes to sign-in`() {
        val flow =
            FakeDataExportFlow(
                statusOutcome = DataExportStatusOutcome.None,
                requestOutcome = DataExportRequestOutcome.Terminal401,
            )
        val vm = SettingsDataExportViewModel(flow)
        vm.confirmExport()
        assertTrue(vm.terminal401.value)
    }

    @Test
    fun `confirm export retryable error surfaces requestError and leaves state unchanged`() {
        val flow =
            FakeDataExportFlow(
                statusOutcome = DataExportStatusOutcome.None,
                requestOutcome = DataExportRequestOutcome.RetryableError,
            )
        val vm = SettingsDataExportViewModel(flow)
        vm.confirmExport()
        assertTrue(vm.requestError.value)
        assertEquals(DataExportUiState.None, vm.uiState.value)
        vm.consumeRequestError()
        assertFalse(vm.requestError.value)
    }
}
