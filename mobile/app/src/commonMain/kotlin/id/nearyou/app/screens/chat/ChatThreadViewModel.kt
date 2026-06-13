package id.nearyou.app.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ChatThreadOutcome
import id.nearyou.app.chat.CreateConversationOutcome
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.infra.supabaserealtime.ChatMessageInbound
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `ChatThreadRoute`-scoped ViewModel (design D4). It owns the merge of three message sources into one
 * ordered, id-deduplicated list (the pure [mergeChatMessages] + [chatMessageRows]):
 *  1. **REST history** — first page on entry + a REST resync on every (re)subscribe (no-outbox recovery);
 *     older pages on scroll-up via [loadOlder].
 *  2. **Optimistic sends** — appended locally on [send]; the temp client id is reconciled to the server
 *     id from the 201 so a realtime echo collapses by id (no duplicate).
 *  3. **Realtime inbound** — [chatRealtimeSubscriber] collected in [viewModelScope]; each message merged
 *     by id (a redaction inbound flips an existing row's content to null IN PLACE).
 *
 * A realtime/token failure NEVER breaks the screen: the collector loop logs + retries, and the thread
 * stays usable in REST-only mode (send still maps via REST; pull-to-refresh / [retry] resyncs). The
 * subscription is cold — collection starts on construction and is torn down when the VM is cleared.
 *
 * Shadow-ban is server-authoritative (design D6): the client carries NO ban signal; an own-message echo
 * collapses via the id-dedupe, not a client-side filter.
 */
@OptIn(ExperimentalUuidApi::class)
class ChatThreadViewModel(
    private val conversationId: String,
    private val flow: ChatFlow,
    private val chatRealtimeSubscriber: ChatRealtimeSubscriber,
    private val viewerIdProvider: ViewerIdProvider,
    private val nowIso: () -> String = { Clock.System.now().toString() },
    private val resubscribeDelayMillis: Long = RESUBSCRIBE_DELAY_MILLIS,
    private val diagnosticLog: (String) -> Unit = {},
) : ViewModel() {
    // The three id-keyed sources (a map = update-in-place dedupe; the pure merge unions their values).
    private val rest = LinkedHashMap<String, ChatMessage>()
    private val optimistic = LinkedHashMap<String, ChatMessage>()
    private val realtime = LinkedHashMap<String, ChatMessage>()

    // The viewer's own id (JWT sub) for own-vs-other alignment; "" until resolved (optimistic sends still
    // mark own because they use this same value). Never rendered.
    private var viewerId: String = ""

    // The cursor to fetch yet-OLDER messages (from the first page; advanced by loadOlder, untouched by resync).
    private var olderCursor: String? = null
    private var tempCounter: Int = 0

    private val _rows = MutableStateFlow<List<ChatMessageRow>>(emptyList())
    val rows: StateFlow<List<ChatMessageRow>> = _rows.asStateFlow()

    private val _historyOutcome = MutableStateFlow<ChatThreadOutcome?>(null)
    val historyOutcome: StateFlow<ChatThreadOutcome?> = _historyOutcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _sendOutcome = MutableStateFlow<SendOutcome?>(null)
    val sendOutcome: StateFlow<SendOutcome?> = _sendOutcome.asStateFlow()

    private val _sendInFlight = MutableStateFlow(false)
    val sendInFlight: StateFlow<Boolean> = _sendInFlight.asStateFlow()

    // The create-or-return one-shot result for the future profile "Kirim pesan" caller (design D5,
    // follow-up). Non-null once startConversation resolves a conversation id to navigate to.
    private val _startedConversationId = MutableStateFlow<String?>(null)
    val startedConversationId: StateFlow<String?> = _startedConversationId.asStateFlow()
    private val _startOutcome = MutableStateFlow<CreateConversationOutcome?>(null)
    val startOutcome: StateFlow<CreateConversationOutcome?> = _startOutcome.asStateFlow()

    init {
        viewModelScope.launch {
            viewerId = viewerIdProvider.viewerId().orEmpty()
            recompute()
        }
        // The realtime loop: each iteration does a REST first-page resync THEN (re)subscribes. The first
        // iteration's resync IS the initial load (drives the skeleton); a later iteration is the no-outbox
        // recovery on a dropped/ended subscription (design D4). A realtime failure degrades to REST-only.
        viewModelScope.launch {
            var initial = true
            while (isActive) {
                resyncFirstPage(initial = initial)
                initial = false
                try {
                    chatRealtimeSubscriber.subscribe(parseConversationId()).collect { onRealtime(it) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Realtime transport/token failure — log (no content/token), degrade to REST-only.
                    diagnosticLog("chat_realtime_subscribe_failed")
                }
                // The flow ended (transport drop / completion) OR errored. Back off before the next
                // resync + re-subscribe so a persistent failure cannot hot-loop (the thread stays usable
                // in REST-only mode meanwhile).
                delay(resubscribeDelayMillis)
            }
        }
    }

    /** Pull-to-refresh + error-retry: re-fetch the newest page and merge (keeps the list mounted). */
    fun retry() {
        viewModelScope.launch { resyncFirstPage(initial = false) }
    }

    /** Load-older on scroll-up: fetch the next OLDER page via the cursor and merge by id. No-op at the end. */
    fun loadOlder() {
        val cursor = olderCursor ?: return
        viewModelScope.launch {
            when (val outcome = flow.loadHistory(conversationId, cursor = cursor)) {
                is ChatThreadOutcome.Loaded -> {
                    mergeRest(outcome.messages.map { it.toChatMessage() })
                    olderCursor = outcome.nextCursor
                    recompute()
                }
                // A failed older-page fetch leaves the loaded thread intact (no error chrome for paging).
                else -> diagnosticLog("chat_load_older_failed")
            }
        }
    }

    /** Optimistic send + POST, reconciling the server id; maps the result to a [SendOutcome]. */
    fun send(content: String) {
        if (_sendInFlight.value || !canSubmitChat(content)) return
        val tempId = "temp-${tempCounter++}"
        val tempMessage =
            ChatMessage(
                id = tempId,
                senderId = viewerId,
                content = content,
                createdAtIso = nowIso(),
                isRedacted = false,
            )
        optimistic[tempId] = tempMessage
        _sendOutcome.value = null
        // Claim the in-flight slot synchronously (no same-frame double-send window). Recompute to render
        // the optimistic bubble immediately.
        _sendInFlight.value = true
        recompute()
        viewModelScope.launch {
            try {
                when (val outcome = flow.send(conversationId, content)) {
                    is SendOutcome.Sent -> {
                        // Reconcile temp id → server id IN PLACE so a realtime echo dedupes by id.
                        optimistic.remove(tempId)
                        val server = outcome.message.toChatMessage()
                        optimistic[server.id] = server
                        // Keep the Sent outcome so the screen can clear the input + run the first-send
                        // notification-permission prompt (task 9.4); sendBarState maps Sent → Idle.
                        _sendOutcome.value = outcome
                        recompute()
                    }
                    else -> {
                        // Failed send: drop the optimistic bubble + surface the outcome's banner.
                        optimistic.remove(tempId)
                        _sendOutcome.value = outcome
                        recompute()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                optimistic.remove(tempId)
                _sendOutcome.value = SendOutcome.NetworkError
                recompute()
            } finally {
                _sendInFlight.value = false
            }
        }
    }

    /** Clears a transient send banner (after the user dismisses / edits the input). */
    fun clearSendOutcome() {
        _sendOutcome.value = null
    }

    /**
     * The create-or-return entry for the future profile "Kirim pesan" caller (design D5 — profile wiring
     * deferred to a follow-up after PR #245). Resolves a 1:1 conversation with [recipientUserId] and, on
     * success, exposes its id via [startedConversationId] for the caller to navigate to a fresh
     * `ChatThreadRoute`; non-success maps to [startOutcome] for the caller to surface.
     */
    fun startConversation(recipientUserId: String) {
        viewModelScope.launch {
            when (val outcome = flow.createOrReturn(recipientUserId)) {
                is CreateConversationOutcome.Ready -> _startedConversationId.value = outcome.conversationId
                else -> _startOutcome.value = outcome
            }
        }
    }

    private suspend fun resyncFirstPage(initial: Boolean) {
        try {
            when (val outcome = flow.loadHistory(conversationId, cursor = null)) {
                is ChatThreadOutcome.Loaded -> {
                    mergeRest(outcome.messages.map { it.toChatMessage() })
                    // Set the older-page cursor only on the first successful load (resync must not rewind paging).
                    if (olderCursor == null) olderCursor = outcome.nextCursor
                    _historyOutcome.value = outcome
                    recompute()
                }
                else -> _historyOutcome.value = outcome
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            _historyOutcome.value = ChatThreadOutcome.NetworkError
        } finally {
            if (initial) _isInitialLoad.value = false
        }
    }

    private fun mergeRest(messages: List<ChatMessage>) {
        for (message in messages) rest[message.id] = message
    }

    private fun onRealtime(inbound: ChatMessageInbound) {
        realtime[inbound.id.toString()] =
            ChatMessage(
                id = inbound.id.toString(),
                senderId = inbound.senderId.toString(),
                content = inbound.content,
                createdAtIso = inbound.createdAt.toString(),
                isRedacted = inbound.redactedAt != null,
            )
        recompute()
    }

    private fun recompute() {
        val merged = mergeChatMessages(rest.values.toList(), optimistic.values.toList(), realtime.values.toList())
        _rows.value = chatMessageRows(merged, viewerId)
    }

    private fun parseConversationId(): Uuid = Uuid.parse(conversationId)

    private companion object {
        /** Backoff between a dropped/ended realtime subscription and the next resync + re-subscribe. */
        const val RESUBSCRIBE_DELAY_MILLIS: Long = 3000L
    }
}
