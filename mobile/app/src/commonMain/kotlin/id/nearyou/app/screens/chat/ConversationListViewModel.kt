package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `ConversationListRoute`-scoped ViewModel owning the conversation-list first-page load state. Mirrors
 * `GlobalTimelineViewModel`: the first load runs once on construction; [reload] re-fetches
 * (pull-to-refresh + error retry) with a reentrancy guard. Loading is split into [isInitialLoad]
 * (skeleton) and [isRefreshing] (`PullToRefreshBox` indicator over retained content), so the list never
 * shows a skeleton + pull spinner at once.
 */
class ConversationListViewModel(
    private val flow: ConversationsFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<ConversationListOutcome?>(null)
    val outcome: StateFlow<ConversationListOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        if (_isRefreshing.value || _isInitialLoad.value) return
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
                _isInitialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
