package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.accountdeletion.AccountDeletionFlow
import id.nearyou.app.data.accountdeletion.AccountDeletionOutcome
import id.nearyou.app.data.accountdeletion.DeletionCancelOutcome
import id.nearyou.app.data.accountdeletion.DeletionStatusOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `SettingsRoute`-NavEntry-scoped ViewModel owning the account-deletion ("Hapus Akun") request + the
 * pending-deletion restore banner. Resolved via `viewModel { … }` inside `SettingsScreen` (the
 * established mobile state-holder Pattern Registry entry, docs/11 § 2.2), alongside the existing
 * [SettingsViewModel] (logout). On entry it fetches the deletion status ([refreshStatus]) so the banner
 * reflects an existing in-grace request.
 *
 * Confirming deletion issues `POST` → on success the banner shows the scheduled state (the account is
 * fully functional during grace — this is NOT a suspension). "Batalkan" issues `DELETE` (restore) → on
 * success the banner clears; a retryable failure KEEPS the banner and raises a one-shot [cancelError]
 * (the screen shows a non-trapping snackbar) — NEVER an optimistic clear. A `401` on any call surfaces
 * [terminal401] so the screen routes to sign-in.
 */
class SettingsAccountDeletionViewModel(
    private val flow: AccountDeletionFlow,
) : ViewModel() {
    /** Non-null = a pending deletion banner is shown, carrying the restore-by instant (ISO-8601). */
    private val _banner = MutableStateFlow<DeletionBannerState?>(null)
    val banner: StateFlow<DeletionBannerState?> = _banner.asStateFlow()

    /** Terminal `401` on any call — the screen routes to sign-in (one-shot; the re-route is in flight). */
    private val _terminal401 = MutableStateFlow(false)
    val terminal401: StateFlow<Boolean> = _terminal401.asStateFlow()

    /** A one-shot "deletion request failed" signal — the screen renders a non-trapping snackbar then consumes. */
    private val _requestError = MutableStateFlow(false)
    val requestError: StateFlow<Boolean> = _requestError.asStateFlow()

    /** A one-shot "restore (cancel) failed, banner kept" signal — non-trapping snackbar then consume. */
    private val _cancelError = MutableStateFlow(false)
    val cancelError: StateFlow<Boolean> = _cancelError.asStateFlow()

    /** True while a `POST`/`DELETE` is in flight — guards a double-tap (a re-tap is a no-op). */
    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    init {
        refreshStatus()
    }

    /** Fetches the deletion status (`GET`) on screen entry to drive the banner. A failure leaves no banner. */
    fun refreshStatus() {
        viewModelScope.launch {
            val outcome =
                try {
                    flow.fetchStatus()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DeletionStatusOutcome.Unavailable
                }
            when (outcome) {
                is DeletionStatusOutcome.Pending ->
                    _banner.value = DeletionBannerState(scheduledHardDeleteAt = outcome.scheduledHardDeleteAt)
                DeletionStatusOutcome.NotPending -> _banner.value = null
                // A status-read failure must NOT trap the otherwise-functional Settings screen → no banner.
                DeletionStatusOutcome.Unavailable -> Unit
                DeletionStatusOutcome.Terminal401 -> _terminal401.value = true
            }
        }
    }

    /**
     * Issues `POST /api/v1/account/deletion-request` after the user confirms the dialog. On success the
     * banner shows the scheduled (restore-by) state; on a retryable failure [requestError] flips
     * (non-trapping snackbar) and no banner is shown; on `401` the token-invalid signal routes to
     * sign-in. A concurrent request while one is in flight is a no-op (the in-flight guard).
     */
    fun confirmDeletion() {
        if (_inFlight.value) return
        _inFlight.value = true
        viewModelScope.launch {
            val outcome =
                try {
                    flow.requestDeletion()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    AccountDeletionOutcome.RetryableError
                }
            when (outcome) {
                is AccountDeletionOutcome.Scheduled ->
                    _banner.value = DeletionBannerState(scheduledHardDeleteAt = outcome.scheduledHardDeleteAt)
                AccountDeletionOutcome.RetryableError -> _requestError.value = true
                AccountDeletionOutcome.Terminal401 -> _terminal401.value = true
            }
            _inFlight.value = false
        }
    }

    /**
     * Issues `DELETE /api/v1/account/deletion-request` (restore) from the banner's "Batalkan" action. On
     * success the banner clears; on a retryable failure the banner STAYS and [cancelError] flips
     * (non-trapping snackbar — NO optimistic clear); on `401` the screen routes to sign-in. A concurrent
     * cancel while one is in flight is a no-op.
     */
    fun cancelDeletion() {
        if (_inFlight.value) return
        _inFlight.value = true
        viewModelScope.launch {
            val outcome =
                try {
                    flow.cancelDeletion()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DeletionCancelOutcome.RetryableError
                }
            when (outcome) {
                DeletionCancelOutcome.Success -> _banner.value = null
                DeletionCancelOutcome.RetryableError -> _cancelError.value = true // banner stays
                DeletionCancelOutcome.Terminal401 -> _terminal401.value = true
            }
            _inFlight.value = false
        }
    }

    fun consumeRequestError() {
        _requestError.value = false
    }

    fun consumeCancelError() {
        _cancelError.value = false
    }
}

/** The pending-deletion banner state: the restore-by instant (ISO-8601) the banner formats + references. */
data class DeletionBannerState(
    val scheduledHardDeleteAt: String,
)
