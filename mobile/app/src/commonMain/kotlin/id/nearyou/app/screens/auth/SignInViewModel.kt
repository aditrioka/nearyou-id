package id.nearyou.app.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the sign-in flow routes once an outcome resolves (one-shot, consumed via [SignInViewModel.onNavigationHandled]). */
enum class SignInNavTarget {
    /** `SignInOutcome.Success` → `replaceAll(HomeRoute)`. */
    Home,

    /** `SignInOutcome.NoAccount` → `add(AgeGateRoute)` (the verified id_token is stashed first). */
    AgeGate,
}

/**
 * The exposed sign-in screen state: the pure [signInUiState] projection (CTA label/enabled + error
 * banner) plus the screen-owned facts the projection does not carry — the involuntary-logout notice,
 * the banned-state appeal entry, and the nullable one-shot [navigation] target.
 */
data class SignInScreenUiState(
    val ctaLabel: SignInCtaLabel,
    val ctaEnabled: Boolean,
    val errorBanner: SignInErrorBanner?,
    val sessionExpired: Boolean,
    val showAppealEntry: Boolean,
    val navigation: SignInNavTarget?,
)

/**
 * The `SignInRoute`-scoped state holder (docs/11 §2.2): an androidx [ViewModel] in commonMain, resolved
 * via `viewModel { … }` scoped to the Nav3 entry, exposing ONE [StateFlow]<[SignInScreenUiState]> via
 * `stateIn(WhileSubscribed)` (the `UsernameCustomizationViewModel`/`AppealViewModel` precedent). It owns
 * the sign-in outcome + in-flight flag that `SignInScreen` previously held in `remember`, so they survive
 * a configuration change and the Google ceremony — launched on [viewModelScope] — is not cancelled by
 * recomposition. The CTA→state mapping delegates to the unchanged pure [signInUiState].
 *
 * The involuntary-logout notice is captured ONCE from [PendingReturnDestination] in the initial state (a
 * non-clearing read; the holder is cleared on sign-in success by `appEntryProvider`). The no-account path
 * stashes the verified `id_token` in [PendingSignupIdentity] BEFORE raising the [SignInNavTarget.AgeGate]
 * one-shot, and clears the consumed outcome so a system-back to a retained `SignInRoute` lands on a clean
 * initial screen (the `SignInScreen.kt` `outcome = null` precedent).
 *
 * The banned outcome lasts while its [AppealSession] token is held (#581): a back from the appeal keeps the
 * spec'd disabled banned state, while the appeal's re-sign-in exits (a rejected token, an approved decision)
 * drop the token and so consume the outcome — this entry survives the appeal's `replaceAll(SignInRoute)`
 * (same content key), and must then show a usable CTA, not the stale ban.
 */
class SignInViewModel(
    private val authFlow: AuthFlow,
    private val pendingSignupIdentity: PendingSignupIdentity,
    pendingReturnDestination: PendingReturnDestination,
    private val appealSession: AppealSession,
) : ViewModel() {
    private data class VmState(
        val outcome: SignInOutcome? = null,
        val inFlight: Boolean = false,
        val sessionExpired: Boolean = false,
        val navigation: SignInNavTarget? = null,
    )

    private val state = MutableStateFlow(VmState(sessionExpired = pendingReturnDestination.wasInvoluntary()))

    val uiState: StateFlow<SignInScreenUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    init {
        viewModelScope.launch {
            appealSession.isHeld.drop(1).collect { held -> if (!held) clearBannedOutcome() }
        }
    }

    private fun clearBannedOutcome() = state.update { if (it.outcome is SignInOutcome.Banned) it.copy(outcome = null) else it }

    private fun VmState.toUiState(): SignInScreenUiState {
        val projected = signInUiState(outcome = outcome, inFlight = inFlight)
        return SignInScreenUiState(
            ctaLabel = projected.ctaLabel,
            ctaEnabled = projected.ctaEnabled,
            errorBanner = projected.errorBanner,
            sessionExpired = sessionExpired,
            // appeal-sign-in-ban-distinction: the "Ajukan banding" entry shows for a SUSPENSION only
            // (non-null suspended_until). A permanent ban (null) routes to the support path — no entry.
            showAppealEntry = (outcome as? SignInOutcome.Banned)?.suspendedUntil != null,
            navigation = navigation,
        )
    }

    /** "Masuk dengan Google" tapped — run the ceremony on [viewModelScope] (single in-flight; the flag is
     *  set BEFORE the launch so a same-frame double-tap cannot pass the guard). */
    fun onSignInClick() {
        if (state.value.inFlight) return
        state.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            // signInWithGoogle() returns an outcome for every case (GoogleSignIn / HTTP failures map to
            // SignInOutcome.*); the only throw is cooperative cancellation when the VM scope is cleared,
            // which propagates and skips applyOutcome (the screen is gone — no stuck "loading" to reset).
            val result = authFlow.signInWithGoogle()
            applyOutcome(result)
        }
    }

    private fun applyOutcome(result: SignInOutcome) {
        when (result) {
            is SignInOutcome.NoAccount -> {
                // Stash the verified id_token BEFORE raising the age-gate one-shot (the signup flow reuses
                // it without a second Google ceremony); clear the consumed outcome so a back-nav to a
                // retained SignInRoute shows a clean initial screen — the SignInScreen.kt outcome=null reset.
                pendingSignupIdentity.set(result.idToken)
                state.update { it.copy(inFlight = false, outcome = null, navigation = SignInNavTarget.AgeGate) }
            }
            SignInOutcome.Success ->
                state.update { it.copy(inFlight = false, outcome = SignInOutcome.Success, navigation = SignInNavTarget.Home) }
            else ->
                // Banned / InvalidIdToken / NetworkError / RateLimited / Cancelled — stay on the screen; the
                // outcome drives the CTA label / banner / appeal entry. No navigation.
                state.update { it.copy(inFlight = false, outcome = result) }
        }
    }

    /** "Ajukan banding" tapped. With no token held (a 403 without one) the appeal can only re-route to
     *  re-sign-in, and no drop will come, so the banned outcome is consumed now. */
    fun onAppealOpened() {
        if (appealSession.peek() == null) clearBannedOutcome()
    }

    /** One-shot consumer (docs/11 §2.2): the screen calls this after invoking the matching nav callback. */
    fun onNavigationHandled() = state.update { it.copy(navigation = null) }
}
