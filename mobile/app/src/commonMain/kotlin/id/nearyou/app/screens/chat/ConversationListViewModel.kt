package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `ConversationListRoute`-scoped ViewModel owning the conversation-list first-page load state. Mirrors
 * `GlobalTimelineViewModel`: the first load runs once on construction; [reload] re-fetches
 * (pull-to-refresh + error retry) with a reentrancy guard.
 *
 * The single screen state is exposed as ONE [uiState] `StateFlow` (docs/11 §2.2) — the pure
 * `conversationListUiState(outcome, isInitialLoad)` projection folded into the ViewModel via `stateIn`, so
 * it is owned here, not recomputed in the composable. The initial-load flag is INTERNAL ([initialLoad],
 * private), reflected through [uiState] (`Loading` until the first outcome arrives), NOT a separate public
 * flow; [isRefreshing] (the `PullToRefreshBox` indicator over retained content) drives only the refresh
 * spinner and is never folded into [uiState], so the list never shows a skeleton + pull spinner at once.
 * The raw [outcome] stays exposed as the domain-state seam the white-box tests read (it carries the
 * partner UUID the PII-free `ConversationListUiState.Content` rows strip).
 */
class ConversationListViewModel(
    private val flow: ConversationsFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<ConversationListOutcome?>(null)
    val outcome: StateFlow<ConversationListOutcome?> = _outcome.asStateFlow()

    private val initialLoad = MutableStateFlow(true)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The single screen state (docs/11 §2.2): the pure [conversationListUiState] projection folded into the
     * ViewModel via [stateIn] — computed once per outcome/initial-load change, NOT re-derived in the
     * composable. `Loading` until the first outcome arrives; the refresh indicator is the separate
     * [isRefreshing] flag, never folded in here.
     */
    val uiState: StateFlow<ConversationListUiState> =
        combine(_outcome, initialLoad) { outcome, isInitialLoad ->
            conversationListUiState(outcome, isInitialLoad)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)

    init {
        load(initial = true)
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        if (_isRefreshing.value || initialLoad.value) return
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _outcome.value = ConversationListOutcome.NetworkError
            } finally {
                initialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
