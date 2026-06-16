package id.nearyou.app.screens.username

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.username.UsernameChangeOutcome
import id.nearyou.app.username.UsernameCheckOutcome
import id.nearyou.app.username.UsernameFlow
import id.nearyou.app.username.UsernameFormatGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * The `UsernameCustomizationRoute`-scoped state holder (docs/11 §2.2): an androidx [ViewModel] in
 * commonMain, obtained via `koinViewModel()` scoped to the Nav3 entry (the pushed-route precedent).
 * Exposes ONE [StateFlow]<[UsernameUiState]> via `stateIn(WhileSubscribed)`.
 *
 * It owns: the input candidate; the **on-entry self-Premium resolution** (a self-profile read — Settings
 * pushes the route unconditionally, so the screen owns the gate, design D2); the **debounced budget-aware
 * probe** (500 ms; one `check` per settled candidate, stops after a `ProbeExhausted` to protect the 3/day
 * limit); the **submit** (gated behind a confirmation dialog); and the **success one-shot** ([VmState.changed],
 * cleared via [onChangedShown]). The authoritative gate is the reactive `403` from a probe/submit — the
 * backstop for the `premium_billing_retry` edge + staleness. A self-read failure degrades to the editor
 * (isPremiumKnown = true), where the reactive `403`/`200` then governs. Mirrors `SearchViewModel`'s
 * single-in-flight job discipline + `ProfileViewModel`'s self-id resolution.
 */
class UsernameCustomizationViewModel(
    private val flow: UsernameFlow,
    private val profileFlow: ProfileFlow,
    private val selfUserIdProvider: SelfUserIdProvider,
) : ViewModel() {
    private data class VmState(
        val currentUsername: String = "",
        val candidate: String = "",
        val isPremiumKnown: Boolean? = null,
        val checkOutcome: UsernameCheckOutcome? = null,
        val changeOutcome: UsernameChangeOutcome? = null,
        val isSubmitting: Boolean = false,
        val confirmVisible: Boolean = false,
        val changed: Boolean = false,
    )

    private val state = MutableStateFlow(VmState())

    private var probeJob: Job? = null
    private var lastProbedCandidate: String? = null
    private var probeExhausted: Boolean = false

    val uiState: StateFlow<UsernameUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    init {
        resolvePremiumOnEntry()
    }

    private fun VmState.toUiState(): UsernameUiState =
        usernameUiState(
            currentUsername = currentUsername,
            candidate = candidate,
            isPremiumKnown = isPremiumKnown,
            checkOutcome = checkOutcome,
            changeOutcome = changeOutcome,
            isSubmitting = isSubmitting,
            confirmVisible = confirmVisible,
            changed = changed,
        )

    /** On-entry: resolve the self profile → seed the current handle + the Premium gate. A missing id or a
     *  read failure degrades to the editor (the reactive 403 then governs — never an error wall). */
    private fun resolvePremiumOnEntry() {
        viewModelScope.launch {
            val id = selfUserIdProvider.selfUserId()
            if (id == null) {
                state.update { it.copy(isPremiumKnown = true) }
                return@launch
            }
            when (val outcome = profileFlow.loadProfile(id)) {
                is ProfileOutcome.Loaded ->
                    state.update {
                        it.copy(currentUsername = outcome.profile.username, isPremiumKnown = outcome.profile.isPremium)
                    }
                // NotFound / NetworkError on the self read → editor (reactive 403 backstops correctness).
                else -> state.update { it.copy(isPremiumKnown = true) }
            }
        }
    }

    /** The text field's change handler: caps at 30 code points, returns to the editing surface (clears any
     *  prior change result), and schedules a single debounced probe for a fresh, format-valid candidate. */
    fun onCandidateChange(raw: String) {
        val capped = UsernameFormatGuard.cap(raw)
        probeJob?.cancel()
        state.update { it.copy(candidate = capped, changeOutcome = null, checkOutcome = null) }
        val shouldProbe =
            UsernameFormatGuard.isValid(capped) &&
                capped != state.value.currentUsername &&
                !probeExhausted &&
                capped != lastProbedCandidate
        if (!shouldProbe) return
        probeJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MILLIS)
                val outcome =
                    try {
                        flow.check(capped)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        UsernameCheckOutcome.CheckNetworkError
                    }
                ensureActive()
                lastProbedCandidate = capped
                if (outcome == UsernameCheckOutcome.ProbeExhausted) probeExhausted = true
                // Only commit if the candidate is still current (a newer keystroke did not supersede it).
                if (state.value.candidate == capped) state.update { it.copy(checkOutcome = outcome) }
            }
    }

    /** "Ganti" tapped: surface the confirmation modal (the destructive change is gated behind it). */
    fun onSubmitClick() {
        val current = state.value
        if (UsernameFormatGuard.isValid(current.candidate) && current.candidate != current.currentUsername) {
            state.update { it.copy(confirmVisible = true) }
        }
    }

    /** Confirmation dismissed ("Batal") — no request issued. */
    fun onDismissConfirm() = state.update { it.copy(confirmVisible = false) }

    /** Confirmation accepted ("Ganti") — issue the PATCH; Success raises the [VmState.changed] one-shot. */
    fun onConfirmChange() {
        val candidate = state.value.candidate
        if (!UsernameFormatGuard.isValid(candidate)) return
        probeJob?.cancel()
        state.update { it.copy(confirmVisible = false, isSubmitting = true, changeOutcome = null) }
        viewModelScope.launch {
            val outcome =
                try {
                    flow.change(candidate)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    UsernameChangeOutcome.NetworkError
                }
            ensureActive()
            state.update {
                it.copy(
                    isSubmitting = false,
                    changeOutcome = outcome,
                    changed = outcome is UsernameChangeOutcome.Success,
                )
            }
        }
    }

    /** The success one-shot consumer (docs/11 §2.2): clears [VmState.changed] after the screen pops. */
    fun onChangedShown() = state.update { it.copy(changed = false) }

    /** Error/network retry: clear the change result to return to the editing surface. */
    fun onRetry() = state.update { it.copy(changeOutcome = null) }

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 500
    }
}
