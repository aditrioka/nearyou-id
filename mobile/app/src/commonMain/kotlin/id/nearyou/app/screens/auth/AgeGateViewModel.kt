package id.nearyou.app.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.routing.PendingSignupIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Where the age-gate flow routes on a terminal outcome (one-shot, consumed via [AgeGateViewModel.onNavigationHandled]). */
enum class AgeGateNavTarget {
    /** `SignUpOutcome.Success` → `replaceAll(ConsentRoute)` (the signup-201 terminus, via onSignedUp). */
    Home,

    /** `SignUpOutcome.AccountExists` (409) OR the absent-identity process-death guard → re-route to SignIn. */
    SignIn,
}

/**
 * The exposed age-gate screen state: the pure [ageGateUiState] projection (CTA label/enabled + banner)
 * plus the screen-owned facts the projection does not carry — the picked DOB (for the field text), the
 * picker visibility, the absent-identity guard flag, and the nullable one-shot [navigation] target.
 */
data class AgeGateScreenUiState(
    val ctaLabel: AgeGateCtaLabel,
    val ctaEnabled: Boolean,
    val banner: AgeGateBanner?,
    val selectedDob: LocalDate?,
    /** The raw picker value, re-supplied as the DatePicker's `initialMillis` when the dialog re-opens. */
    val selectedDobMillis: Long?,
    val showPicker: Boolean,
    val identityAbsent: Boolean,
    val navigation: AgeGateNavTarget?,
)

/**
 * The `AgeGateRoute`-scoped state holder (docs/11 §2.2): an androidx [ViewModel] in commonMain, resolved
 * via `viewModel { … }` scoped to the Nav3 entry, exposing ONE [StateFlow]<[AgeGateScreenUiState]> via
 * `stateIn(WhileSubscribed)`. It owns the picked DOB / picker visibility / signup outcome / in-flight flag
 * that `AgeGateScreen` previously held in `remember`, so the **picked DOB survives a configuration change**
 * (the primary data-loss bug) and the signup call — on [viewModelScope] — is not cancelled by recomposition.
 * The CTA→state mapping delegates to the unchanged pure [ageGateUiState] (`isDobSubmittable` against an
 * injectable [today]).
 *
 * **Identity lifecycle (Decision 4):** the verified Google `id_token` is read with a non-clearing
 * [PendingSignupIdentity.peek] (so a retryable error can resubmit). The absent-identity process-death guard
 * runs synchronously in the **initial state** (the holder is a Koin `single`, read before first composition):
 * an empty holder → `clear()` + `identityAbsent = true` + the [AgeGateNavTarget.SignIn] one-shot, so the
 * screen renders no signup form even one frame. The terminal-exit `clear()` + routing reuses the pure
 * [handleAgeGateTerminalOutcome] seam verbatim (its signature is unchanged — the VM passes `state.update`
 * lambdas as the nav callbacks), so `AgeGateOutcomeHandlerTest` stays green.
 */
class AgeGateViewModel(
    private val authFlow: AuthFlow,
    private val pendingSignupIdentity: PendingSignupIdentity,
    private val today: LocalDate = systemToday(),
) : ViewModel() {
    private data class VmState(
        val selectedDobMillis: Long? = null,
        val showPicker: Boolean = false,
        val outcome: SignUpOutcome? = null,
        val inFlight: Boolean = false,
        val identityAbsent: Boolean = false,
        val navigation: AgeGateNavTarget? = null,
    )

    private val state = MutableStateFlow(initialState())

    val uiState: StateFlow<AgeGateScreenUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    /** Absent-identity process-death guard, resolved synchronously so the screen never renders the form for a
     *  frame: an empty holder → clear (idempotent) + raise the SignIn re-route + flag `identityAbsent`. */
    private fun initialState(): VmState =
        if (pendingSignupIdentity.peek() == null) {
            pendingSignupIdentity.clear()
            VmState(identityAbsent = true, navigation = AgeGateNavTarget.SignIn)
        } else {
            VmState()
        }

    private fun VmState.toUiState(): AgeGateScreenUiState {
        val selectedDob = selectedDobMillis?.let { dobFromUtcMillis(it) }
        val projected = ageGateUiState(outcome = outcome, dobSubmittable = isDobSubmittable(selectedDob, today), inFlight = inFlight)
        return AgeGateScreenUiState(
            ctaLabel = projected.ctaLabel,
            ctaEnabled = projected.ctaEnabled,
            banner = projected.banner,
            selectedDob = selectedDob,
            selectedDobMillis = selectedDobMillis,
            showPicker = showPicker,
            identityAbsent = identityAbsent,
            navigation = navigation,
        )
    }

    /** Toggle the DOB picker dialog. */
    fun onShowPicker(show: Boolean) = state.update { it.copy(showPicker = show) }

    /** DOB picker confirmed: a non-null pick updates the DOB (a null pick keeps the prior value); always closes
     *  the dialog — mirroring the screen's prior `if (picked != null) selectedDobMillis = picked; showPicker = false`. */
    fun onDobPicked(utcMillis: Long?) = state.update { it.copy(selectedDobMillis = utcMillis ?: it.selectedDobMillis, showPicker = false) }

    /** "Buat akun" tapped — run signup on [viewModelScope] (single in-flight; the flag is set BEFORE the launch
     *  so a same-frame double-tap cannot pass the guard). The DOB is sanity-validated (never an 18+ client gate). */
    fun onCreateAccountClick() {
        val current = state.value
        if (current.inFlight) return
        val idToken = pendingSignupIdentity.peek() ?: return
        val dob = current.selectedDobMillis?.let { dobFromUtcMillis(it) } ?: return
        if (!isDobSubmittable(dob, today)) return
        state.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            // signUpWithGoogle() returns an outcome for every case; the only throw is cooperative cancellation.
            val result = authFlow.signUpWithGoogle(idToken, dob)
            applyOutcome(result)
        }
    }

    private fun applyOutcome(result: SignUpOutcome) {
        state.update { it.copy(inFlight = false, outcome = result) }
        // Terminal-exit decision via the reused pure seam: it clears the holder on Success/AccountExists (not on a
        // retryable error) and invokes the matching nav callback — here a state.update that raises the one-shot.
        handleAgeGateTerminalOutcome(
            outcome = result,
            pendingSignupIdentity = pendingSignupIdentity,
            onSignedUp = { state.update { it.copy(navigation = AgeGateNavTarget.Home) } },
            onExitToSignIn = { state.update { it.copy(navigation = AgeGateNavTarget.SignIn) } },
        )
    }

    /** One-shot consumer (docs/11 §2.2): the screen calls this after invoking the matching nav callback. */
    fun onNavigationHandled() = state.update { it.copy(navigation = null) }
}
