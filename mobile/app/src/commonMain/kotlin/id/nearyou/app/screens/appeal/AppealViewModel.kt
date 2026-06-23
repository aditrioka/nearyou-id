package id.nearyou.app.screens.appeal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.appeal.AppealFlow
import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.appeal.AppealStatusOutcome
import id.nearyou.app.appeal.AppealSubmitOutcome
import id.nearyou.app.ui.components.capCountdownMinutes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The appeal-surface ViewModel (docs/11 §2.2): an androidx [ViewModel] in commonMain, scoped to the appeal
 * Nav3 entry, exposing ONE [StateFlow]<[AppealUiState]> via `stateIn(WhileSubscribed)` (the
 * `UsernameCustomizationViewModel` precedent).
 *
 * It reads the limited appeal token from [AppealSession] (captured at the banned/suspended sign-in `403`).
 * On entry it loads the caller's own appeal status (so a returning user sees Pending/Decided rather than a
 * blank form). The single-in-flight submit maps each [AppealSubmitOutcome] to the screen status, and a
 * fresh submit (or `AlreadyPending`) transitions to [AppealStatus.Pending]. A missing token or a `401`
 * surfaces [AppealStatus.SessionRedirect] (the token is one-shot — re-sign-in re-mints it).
 */
class AppealViewModel(
    private val flow: AppealFlow,
    private val session: AppealSession,
) : ViewModel() {
    private data class VmState(
        val status: AppealStatus = AppealStatus.Loading,
        val appealText: String = "",
        val isSubmitting: Boolean = false,
    )

    private val state = MutableStateFlow(VmState())

    val uiState: StateFlow<AppealUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    init {
        loadOnEntry()
    }

    private fun VmState.toUiState(): AppealUiState {
        val count = appealText.length
        val submitEnabled = status is AppealStatus.Form && appealText.isNotBlank() && !isSubmitting
        return AppealUiState(
            status = status,
            appealText = appealText,
            charCount = count,
            charLimit = APPEAL_TEXT_LIMIT,
            submitEnabled = submitEnabled,
        )
    }

    /** On-entry: read the appeal token, then load the caller's own appeal status. No token → re-sign-in. */
    private fun loadOnEntry() {
        val token = session.peek()
        if (token == null) {
            state.update { it.copy(status = AppealStatus.SessionRedirect) }
            return
        }
        state.update { it.copy(status = AppealStatus.Loading) }
        viewModelScope.launch {
            val outcome =
                try {
                    flow.status(token)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    AppealStatusOutcome.TransportError
                }
            ensureActive()
            state.update { it.copy(status = outcome.toStatus()) }
        }
    }

    private fun AppealStatusOutcome.toStatus(): AppealStatus =
        when (this) {
            AppealStatusOutcome.None -> AppealStatus.Form()
            is AppealStatusOutcome.Pending -> AppealStatus.Pending(actionType)
            is AppealStatusOutcome.Decided -> AppealStatus.Decided(approved, decisionReason)
            AppealStatusOutcome.SessionExpired -> AppealStatus.SessionRedirect
            AppealStatusOutcome.TransportError -> AppealStatus.LoadError
        }

    /** Editor text handler: caps at the limit (so the submit can never exceed the backend guard) and clears
     *  any prior inline submit error (returning to a clean editor). */
    fun onTextChange(raw: String) {
        val capped = raw.take(APPEAL_TEXT_LIMIT)
        state.update {
            val status = if (it.status is AppealStatus.Form) AppealStatus.Form() else it.status
            it.copy(appealText = capped, status = status)
        }
    }

    /** "Kirim banding" tapped — issue the POST (single in-flight); map the outcome onto the screen status. */
    fun onSubmit() {
        val token =
            session.peek() ?: run {
                state.update { it.copy(status = AppealStatus.SessionRedirect) }
                return
            }
        val current = state.value
        val text = current.appealText.trim()
        if (text.isEmpty() || current.isSubmitting || current.status !is AppealStatus.Form) return
        state.update { it.copy(isSubmitting = true, status = AppealStatus.Submitting) }
        viewModelScope.launch {
            val outcome =
                try {
                    flow.submit(text, token)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    AppealSubmitOutcome.TransportError
                }
            ensureActive()
            val status =
                when (outcome) {
                    is AppealSubmitOutcome.Submitted, AppealSubmitOutcome.AlreadyPending ->
                        AppealStatus.Pending(actionType = null)
                    AppealSubmitOutcome.NotEligible -> AppealStatus.Form(AppealFormError.NotEligible)
                    is AppealSubmitOutcome.RateLimited ->
                        AppealStatus.Form(AppealFormError.RateLimited(capCountdownMinutes(outcome.retryAfterSeconds)))
                    AppealSubmitOutcome.InvalidText -> AppealStatus.Form(AppealFormError.InvalidText)
                    AppealSubmitOutcome.SessionExpired -> AppealStatus.SessionRedirect
                    AppealSubmitOutcome.TransportError -> AppealStatus.Form(AppealFormError.Transport)
                }
            state.update { it.copy(isSubmitting = false, status = status) }
        }
    }

    /** Retry the on-entry own-status read after a [AppealStatus.LoadError]. */
    fun onRetryLoad() = loadOnEntry()
}
