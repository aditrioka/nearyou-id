package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.dataexport.DataExportFlow
import id.nearyou.app.data.dataexport.DataExportRequestOutcome
import id.nearyou.app.data.dataexport.DataExportStatusOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `SettingsRoute`-NavEntry-scoped ViewModel owning the data-export ("Unduh Data Saya") request + the
 * status-driven affordance/banner. Resolved via `viewModel { … }` inside `SettingsScreen` (the established
 * mobile state-holder Pattern Registry entry, docs/11 § 2.2), alongside the existing [SettingsViewModel]
 * (logout) and [SettingsAccountDeletionViewModel]. On entry it seeds the status ([refreshStatus]) so the
 * row + ready banner reflect the caller's latest request.
 *
 * Confirming the dialog issues `POST` → on success the row reflects the in-progress (single-active) state.
 * The request is suppressed while already in progress (single-active server-side, mirrored client-side). A
 * `401` on either call surfaces [terminal401] so the screen routes to sign-in; a retryable `POST` failure
 * raises a one-shot [requestError] and a status-read failure raises a one-shot [statusError] (both
 * non-trapping snackbars) — the screen stays functional. The signed `downloadUrl` is held only transiently
 * inside [DataExportUiState.Ready] for the open-URL hand-off — never persisted, never logged.
 */
class SettingsDataExportViewModel(
    private val flow: DataExportFlow,
) : ViewModel() {
    /** The status-driven projection over the shipped vocabulary; [DataExportUiState.Loading] until seeded. */
    private val _uiState = MutableStateFlow<DataExportUiState>(DataExportUiState.Loading)
    val uiState: StateFlow<DataExportUiState> = _uiState.asStateFlow()

    /** Terminal `401` on any call — the screen routes to sign-in (one-shot; the re-route is in flight). */
    private val _terminal401 = MutableStateFlow(false)
    val terminal401: StateFlow<Boolean> = _terminal401.asStateFlow()

    /** A one-shot "export request failed" signal — the screen renders a non-trapping snackbar then consumes. */
    private val _requestError = MutableStateFlow(false)
    val requestError: StateFlow<Boolean> = _requestError.asStateFlow()

    /** A one-shot "status read failed" signal — the screen renders a non-trapping error then consumes. */
    private val _statusError = MutableStateFlow(false)
    val statusError: StateFlow<Boolean> = _statusError.asStateFlow()

    /** True while a `POST` is in flight — guards a double-tap (a re-tap is a no-op). */
    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    init {
        refreshStatus()
    }

    /**
     * Seeds the export status (`GET`) on screen entry to drive the row + ready banner. A transient failure
     * leaves the screen in the requestable ([DataExportUiState.None]) state AND raises [statusError]; a
     * `401` routes to sign-in.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            val outcome =
                try {
                    flow.fetchStatus()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataExportStatusOutcome.Unavailable
                }
            when (outcome) {
                DataExportStatusOutcome.None -> _uiState.value = DataExportUiState.None
                DataExportStatusOutcome.InProgress -> _uiState.value = DataExportUiState.InProgress
                is DataExportStatusOutcome.Ready ->
                    _uiState.value =
                        DataExportUiState.Ready(
                            downloadExpiresAt = outcome.downloadExpiresAt,
                            downloadUrl = outcome.downloadUrl,
                        )
                DataExportStatusOutcome.Expired -> _uiState.value = DataExportUiState.Expired
                DataExportStatusOutcome.Failed -> _uiState.value = DataExportUiState.Failed
                // A status-read failure must NOT trap the screen → stay requestable + surface a non-trapping error.
                DataExportStatusOutcome.Unavailable -> {
                    _uiState.value = DataExportUiState.None
                    _statusError.value = true
                }
                DataExportStatusOutcome.Terminal401 -> _terminal401.value = true
            }
        }
    }

    /** True when a fresh export may be requested — false while loading or single-active (in progress). */
    fun canRequest(): Boolean =
        when (_uiState.value) {
            DataExportUiState.Loading, DataExportUiState.InProgress -> false
            else -> true
        }

    /**
     * Issues `POST /api/v1/account/export` after the user confirms the dialog. On success the row reflects
     * the in-progress (single-active) state; on a retryable failure [requestError] flips (non-trapping
     * snackbar) and the state is unchanged; on `401` the token-invalid signal routes to sign-in. A request
     * while one is in flight, or while already in progress (single-active), is a no-op.
     */
    fun confirmExport() {
        if (_inFlight.value || !canRequest()) return
        _inFlight.value = true
        viewModelScope.launch {
            val outcome =
                try {
                    flow.requestExport()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataExportRequestOutcome.RetryableError
                }
            when (outcome) {
                DataExportRequestOutcome.Requested -> _uiState.value = DataExportUiState.InProgress
                DataExportRequestOutcome.RetryableError -> _requestError.value = true
                DataExportRequestOutcome.Terminal401 -> _terminal401.value = true
            }
            _inFlight.value = false
        }
    }

    fun consumeRequestError() {
        _requestError.value = false
    }

    fun consumeStatusError() {
        _statusError.value = false
    }
}

/**
 * The status-driven UI projection for the data-export surface. Exhaustive over the shipped vocabulary:
 * [Loading] before the seed `GET` resolves; [None] = requestable, no banner; [InProgress] = single-active
 * "sedang diproses" (not re-issuable); [Ready] = the deadline + the transient signed URL for the in-app
 * open-download affordance; [Expired]/[Failed] = a re-request note. [Ready] holds the signed [downloadUrl]
 * ONLY transiently for the open-URL hand-off — it is never persisted to DataStore / preferences / disk.
 */
sealed interface DataExportUiState {
    data object Loading : DataExportUiState

    data object None : DataExportUiState

    data object InProgress : DataExportUiState

    data class Ready(val downloadExpiresAt: String, val downloadUrl: String) : DataExportUiState

    data object Expired : DataExportUiState

    data object Failed : DataExportUiState
}
