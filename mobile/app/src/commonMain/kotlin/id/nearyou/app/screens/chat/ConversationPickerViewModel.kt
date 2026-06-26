package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ConversationListOutcome
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.chat.SendOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one-shot result of sharing the post into the picked conversation (`mobile-chat-embedded-posts`).
 * Each maps from the embed-send [SendOutcome] with NO generic fallthrough:
 *  - [Navigate] (`Sent`) — carries the picked conversation's id + PII-free partner display fields so the
 *    caller can open the thread;
 *  - [Blocked] (`Blocked`, a 403 on the send) — the recipient blocked the sender (or vice versa);
 *  - [Failed] (every other non-`Sent` outcome — `TooLong`/`Error`/`NetworkError`/`SessionExpired`) — a
 *    retryable failure surfaced as a neutral message (no thread navigation).
 */
sealed interface ChatShareResult {
    data class Navigate(
        val conversationId: String,
        val partnerUsername: String,
        val partnerDisplayName: String,
    ) : ChatShareResult

    data object Blocked : ChatShareResult

    data object Failed : ChatShareResult
}

/**
 * `ConversationPickerRoute`-scoped ViewModel — the share-a-post-to-chat picker. Lists the user's
 * existing conversations (the shipped [ConversationsFlow.loadFirstPage], reusing the conversation-list
 * single-`stateIn` `uiState` projection) and, on a pick, sends the post embed into the chosen
 * conversation via the shipped [ChatFlow.send] (the SAME send path, carrying `embedded_post_id`). The
 * conversation already exists, so create-or-return collapses to the "return" arm — its id is the row's
 * `conversationId`; the conversation-list rows are PII-stripped (no partner UUID), so the picker shares
 * to the conversation id directly rather than re-deriving a recipient id. A `403` on the send maps to
 * [ChatShareResult.Blocked]; `Sent` → [ChatShareResult.Navigate] (open the thread).
 */
class ConversationPickerViewModel(
    private val postId: String,
    private val conversationsFlow: ConversationsFlow,
    private val chatFlow: ChatFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<ConversationListOutcome?>(null)
    val outcome: StateFlow<ConversationListOutcome?> = _outcome.asStateFlow()

    private val initialLoad = MutableStateFlow(true)

    /** The single list state — reuses the conversation-list projection (identical list shape). */
    val uiState: StateFlow<ConversationListUiState> =
        combine(_outcome, initialLoad) { outcome, isInitialLoad ->
            conversationListUiState(outcome, isInitialLoad)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState.Loading)

    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    private val _shareResult = MutableStateFlow<ChatShareResult?>(null)
    val shareResult: StateFlow<ChatShareResult?> = _shareResult.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh / error-retry — re-fetch the conversation list. */
    fun reload() {
        if (initialLoad.value) return
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            try {
                _outcome.value = conversationsFlow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _outcome.value = ConversationListOutcome.NetworkError
            } finally {
                if (initial) initialLoad.value = false
            }
        }
    }

    /**
     * Share the post into [row]'s conversation: send a message carrying `embedded_post_id = postId`,
     * then map the [SendOutcome] to a [ChatShareResult] one-shot (`Sent` → [ChatShareResult.Navigate]).
     * A reentrancy guard ([_sharing]) prevents a same-frame double-share.
     */
    fun shareTo(row: ConversationRow) {
        if (_sharing.value) return
        _sharing.value = true
        viewModelScope.launch {
            try {
                _shareResult.value =
                    when (chatFlow.send(row.conversationId, content = null, embeddedPostId = postId)) {
                        is SendOutcome.Sent ->
                            ChatShareResult.Navigate(
                                conversationId = row.conversationId,
                                partnerUsername = row.partnerUsername,
                                partnerDisplayName = row.partnerDisplayName,
                            )
                        SendOutcome.Blocked -> ChatShareResult.Blocked
                        SendOutcome.TooLong,
                        SendOutcome.Error,
                        SendOutcome.NetworkError,
                        SendOutcome.SessionExpired,
                        -> ChatShareResult.Failed
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _shareResult.value = ChatShareResult.Failed
            } finally {
                _sharing.value = false
            }
        }
    }

    /** Clears the one-shot [shareResult] after the screen has consumed it (navigation / snackbar). */
    fun clearShareResult() {
        _shareResult.value = null
    }
}
