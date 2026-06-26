package id.nearyou.app.screens.referral

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.referral.ReferralRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The referral screen's UI state — loading, loaded (code + progress), or a load error. */
sealed interface ReferralUiState {
    data object Loading : ReferralUiState

    data class Loaded(
        val inviteCode: String,
        val grantedReferrals: Int,
        val milestone: Int,
        val inviterRewardClaimed: Boolean,
    ) : ReferralUiState

    /** The fetch failed (non-2xx / transport) — the screen shows a retryable error. */
    data object Error : ReferralUiState
}

/**
 * The `ReferralRoute`-scoped state holder (docs/11 §2.2): a commonMain androidx [ViewModel] exposing
 * exactly ONE [StateFlow]<[ReferralUiState]> via `stateIn(WhileSubscribed)` (the audit #409/#410/#414
 * single-`stateIn` contract). It fetches the referral state via [ReferralRepository] on init and on
 * [retry]; the state models loading → loaded (`inviteCode` + progress) → error. The displayed values
 * are the read endpoint's reported server state — the VM computes no reward and grants nothing.
 */
class ReferralViewModel(
    private val repository: ReferralRepository,
) : ViewModel() {
    private val state = MutableStateFlow<ReferralUiState>(ReferralUiState.Loading)

    val uiState: StateFlow<ReferralUiState> =
        state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReferralUiState.Loading)

    init {
        fetch()
    }

    /** Re-fetch from the error state (resets to loading, then loads). */
    fun retry() = fetch()

    private fun fetch() {
        state.value = ReferralUiState.Loading
        viewModelScope.launch {
            val loaded = repository.loadState()
            state.value =
                if (loaded != null) {
                    ReferralUiState.Loaded(
                        inviteCode = loaded.inviteCode,
                        grantedReferrals = loaded.grantedReferrals,
                        milestone = loaded.milestone,
                        inviterRewardClaimed = loaded.inviterRewardClaimed,
                    )
                } else {
                    ReferralUiState.Error
                }
        }
    }
}
