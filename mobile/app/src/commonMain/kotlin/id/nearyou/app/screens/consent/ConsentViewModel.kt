package id.nearyou.app.screens.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.ConsentSnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The exposed consent screen state: the pure [consentUiState] projection (CTA label/enabled + banner +
 * the post-failure skip) plus the three toggle values for the `Switch` rows and the nullable-style one-shot
 * [done] flag (→ HomeRoute).
 */
data class ConsentScreenUiState(
    val ctaLabel: ConsentCtaLabel,
    val ctaEnabled: Boolean,
    val banner: ConsentBanner?,
    val showSkip: Boolean,
    val analytics: Boolean,
    val crash: Boolean,
    val ads: Boolean,
    val done: Boolean,
)

/**
 * The `ConsentRoute`-scoped state holder (docs/11 §2.2): an androidx [ViewModel] in commonMain, resolved
 * via `viewModel { … }` scoped to the Nav3 entry, exposing ONE [StateFlow]<[ConsentScreenUiState]> via
 * `stateIn(WhileSubscribed)`. It owns the three consent toggles + submit outcome + in-flight flag that
 * `ConsentScreen` previously held in `remember`, so the **toggle selections survive a configuration change**
 * and the PATCH — on [viewModelScope] — is not cancelled by recomposition. The CTA→state mapping delegates
 * to the unchanged pure [consentUiState].
 *
 * The toggles seed from the [initialAnalytics]/[initialCrash]/[initialAdsPersonalization] params (defaults
 * OFF / ON / OFF — the V2 column default; no GET round-trip). On a submit `200` the VM writes the submitted
 * triple to [ConsentSnapshotStore] **before** raising the [done] one-shot (mirroring the prior
 * `ConsentScreen.kt` ordering + the [handleConsentTerminalOutcome] seam), so a user who consents only at
 * onboarding has a durable snapshot for the analytics tracker / crash gate (resolves #198).
 */
class ConsentViewModel(
    private val consentFlow: ConsentFlow,
    private val snapshotStore: ConsentSnapshotStore,
    initialAnalytics: Boolean = false,
    initialCrash: Boolean = true,
    initialAdsPersonalization: Boolean = false,
) : ViewModel() {
    private data class VmState(
        val analytics: Boolean,
        val crash: Boolean,
        val ads: Boolean,
        val outcome: ConsentOutcome? = null,
        val inFlight: Boolean = false,
        val done: Boolean = false,
    )

    private val state =
        MutableStateFlow(VmState(analytics = initialAnalytics, crash = initialCrash, ads = initialAdsPersonalization))

    val uiState: StateFlow<ConsentScreenUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    private fun VmState.toUiState(): ConsentScreenUiState {
        val projected = consentUiState(outcome = outcome, inFlight = inFlight)
        return ConsentScreenUiState(
            ctaLabel = projected.ctaLabel,
            ctaEnabled = projected.ctaEnabled,
            banner = projected.banner,
            showSkip = projected.showSkip,
            analytics = analytics,
            crash = crash,
            ads = ads,
            done = done,
        )
    }

    fun onAnalyticsChange(value: Boolean) = state.update { it.copy(analytics = value) }

    fun onCrashChange(value: Boolean) = state.update { it.copy(crash = value) }

    fun onAdsChange(value: Boolean) = state.update { it.copy(ads = value) }

    /** "Simpan & lanjutkan" tapped — submit on [viewModelScope] (single in-flight; the flag is set BEFORE
     *  the launch so a same-frame double-tap cannot pass the guard). On `200` the snapshot is written before
     *  the [done] one-shot is raised. */
    fun onContinueClick() {
        val current = state.value
        if (current.inFlight) return
        state.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            val result = consentFlow.submitConsent(current.analytics, current.crash, current.ads)
            if (result == ConsentOutcome.Success) {
                // Persist the submitted triple on 200 (mirrors ConsentSettingsScreen) BEFORE raising `done`.
                snapshotStore.write(
                    ConsentSnapshot(analytics = current.analytics, crash = current.crash, adsPersonalization = current.ads),
                )
            }
            applyOutcome(result)
        }
    }

    private fun applyOutcome(result: ConsentOutcome) {
        state.update { it.copy(inFlight = false, outcome = result) }
        // Success → Home (the reused pure seam); RetryableError/TokenInvalid/Cancelled → stay on-screen.
        handleConsentTerminalOutcome(result, onDone = { state.update { it.copy(done = true) } })
    }

    /** One-shot consumer (docs/11 §2.2): the screen calls this after invoking the Home navigation. */
    fun onDoneHandled() = state.update { it.copy(done = false) }
}
