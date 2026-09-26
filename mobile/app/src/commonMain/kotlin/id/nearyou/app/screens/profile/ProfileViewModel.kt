package id.nearyou.app.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.CreateConversationOutcome
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.profile.FollowToggleOutcome
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The profile screen's state holder (docs/11 § 2.2): an androidx [ViewModel] in commonMain, obtained via
 * `viewModel { … }` scoped to the Nav3 entry (the Profil section under `HomeRoute`, or a `ProfileRoute`
 * overlay). Exposes ONE [StateFlow]<[ProfileUiState]> via `stateIn(WhileSubscribed)`. The target is
 * [targetUserId] (the `ProfileRoute.userId`) or, when null, the **self** profile — resolved from the
 * session via [SelfUserIdProvider] (the access token's `sub`). Follow is optimistic with revert; block
 * pops back on success; report surfaces a per-outcome message; "Kirim pesan" create-or-returns a 1:1
 * conversation via [chatFlow] (`profile-send-message`). One-shot events (the toasts/banners, the block
 * navigate-back, the open-chat conversation id) are nullable state fields cleared via `onMessageShown()`
 * / `onNavigatedBack()` / `onChatOpened()` — NOT a `Channel`/`SharedFlow` event bus. [chatFlow] is null
 * only in a test graph that binds no `ChatFlow` (the screen's fail-safe resolution) — the action is then
 * absent and [onSendMessage] a no-op.
 */
class ProfileViewModel(
    private val flow: ProfileFlow,
    private val selfUserIdProvider: SelfUserIdProvider,
    private val targetUserId: String?,
    private val chatFlow: ChatFlow?,
) : ViewModel() {
    private data class VmState(
        val outcome: ProfileOutcome? = null,
        val isInitialLoad: Boolean = true,
        val optimisticFollowed: Boolean? = null,
        val isFollowInFlight: Boolean = false,
        val message: ProfileMessage? = null,
        val navigateBack: Boolean = false,
        val isOpeningChat: Boolean = false,
        val openChat: ProfileChatTarget? = null,
    )

    private val state = MutableStateFlow(VmState())

    /** The resolved target id (the route id, or the self id once decoded) — the action target. */
    private var resolvedUserId: String? = targetUserId

    val uiState: StateFlow<ProfileUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    init {
        resolveAndLoad()
    }

    /** Re-resolve the target (self id when [targetUserId] is null) + reload — the init load AND the
     *  error/not-found retry both call this. */
    fun retry() = resolveAndLoad()

    private fun resolveAndLoad() {
        viewModelScope.launch {
            val id = targetUserId ?: selfUserIdProvider.selfUserId()
            if (id == null) {
                // No token / undecodable subject on an authenticated surface → the retryable error state
                // (not a crash). The userId is never logged.
                state.update { it.copy(outcome = ProfileOutcome.NetworkError, isInitialLoad = false) }
                return@launch
            }
            resolvedUserId = id
            state.update { it.copy(isInitialLoad = true) }
            val outcome = flow.loadProfile(id)
            state.update { it.copy(outcome = outcome, isInitialLoad = false, optimisticFollowed = null) }
        }
    }

    /** Follow/unfollow toggle (other-user only): optimistic flip, revert on failure; 429 → revert +
     *  rate-limit message; the follow-`POST` constant 404 → revert + the neutral "user unavailable"
     *  message. `followerCount` is NOT mutated locally (it is a read snapshot of a raw public aggregate). */
    fun onToggleFollow() {
        val current = state.value
        val profile = (current.outcome as? ProfileOutcome.Loaded)?.profile ?: return
        if (profile.isSelf || current.isFollowInFlight) return
        val id = resolvedUserId ?: return
        val currentlyFollowed = current.optimisticFollowed ?: profile.followedByViewer
        val wantFollow = !currentlyFollowed
        state.update { it.copy(optimisticFollowed = wantFollow, isFollowInFlight = true) }
        viewModelScope.launch {
            val outcome = if (wantFollow) flow.follow(id) else flow.unfollow(id)
            state.update { s ->
                when (outcome) {
                    FollowToggleOutcome.Followed -> s.copy(optimisticFollowed = true, isFollowInFlight = false)
                    FollowToggleOutcome.Unfollowed -> s.copy(optimisticFollowed = false, isFollowInFlight = false)
                    is FollowToggleOutcome.RateLimited ->
                        s.copy(
                            optimisticFollowed = currentlyFollowed,
                            isFollowInFlight = false,
                            message = ProfileMessage.FOLLOW_RATE_LIMITED,
                        )
                    FollowToggleOutcome.TargetGone ->
                        s.copy(
                            optimisticFollowed = currentlyFollowed,
                            isFollowInFlight = false,
                            message = ProfileMessage.TARGET_UNAVAILABLE,
                        )
                    // Silent revert — the toggle snapping back IS the feedback (no extra banner).
                    FollowToggleOutcome.NetworkError ->
                        s.copy(optimisticFollowed = currentlyFollowed, isFollowInFlight = false)
                }
            }
        }
    }

    /** Block confirmed (other-user only): on success surface the toast + the navigate-back one-shot
     *  (the just-blocked profile would 404 on any re-read); 429 → rate-limit message, no pop. */
    fun onBlockConfirmed() {
        val id = resolvedUserId ?: return
        viewModelScope.launch {
            state.update { s ->
                when (flow.block(id)) {
                    BlockOutcome.Blocked -> s.copy(message = ProfileMessage.BLOCK_SUCCESS, navigateBack = true)
                    is BlockOutcome.RateLimited -> s.copy(message = ProfileMessage.BLOCK_RATE_LIMITED)
                    BlockOutcome.NetworkError -> s.copy(message = ProfileMessage.ACTION_FAILED)
                }
            }
        }
    }

    /** Report submitted (other-user only): success / duplicate / rate-limit / failure → a message. */
    fun onReportSubmitted(
        category: ReportReasonCategory,
        note: String?,
    ) {
        val id = resolvedUserId ?: return
        viewModelScope.launch {
            state.update { s ->
                when (flow.report(id, category, note)) {
                    ReportOutcome.Submitted -> s.copy(message = ProfileMessage.REPORT_SUCCESS)
                    ReportOutcome.Duplicate -> s.copy(message = ProfileMessage.REPORT_DUPLICATE)
                    is ReportOutcome.RateLimited -> s.copy(message = ProfileMessage.REPORT_RATE_LIMITED)
                    ReportOutcome.NetworkError -> s.copy(message = ProfileMessage.ACTION_FAILED)
                }
            }
        }
    }

    /** "Kirim pesan" (other-user only): create-or-return the 1:1 conversation; `Ready` → the
     *  [ProfileUiState.openChat] one-shot (id + the loaded profile's display identity) the screen turns
     *  into a `ChatThreadRoute` push. Ignored while one is in flight (the endpoint is idempotent,
     *  navigation is not). A `Ready` landing after a successful block (navigate-back pending) is dropped
     *  — the block wins; the backend would 403 every send in that thread anyway. */
    fun onSendMessage() {
        val chat = chatFlow ?: return
        val current = state.value
        val profile = (current.outcome as? ProfileOutcome.Loaded)?.profile ?: return
        if (profile.isSelf || current.isOpeningChat) return
        val id = resolvedUserId ?: return
        state.update { it.copy(isOpeningChat = true) }
        viewModelScope.launch {
            val outcome = chat.createOrReturn(id)
            state.update { s ->
                when (outcome) {
                    is CreateConversationOutcome.Ready ->
                        if (s.navigateBack) {
                            s.copy(isOpeningChat = false)
                        } else {
                            s.copy(
                                isOpeningChat = false,
                                openChat = ProfileChatTarget(outcome.conversationId, profile.username, profile.displayName),
                            )
                        }
                    CreateConversationOutcome.Blocked -> s.copy(isOpeningChat = false, message = ProfileMessage.CHAT_BLOCKED)
                    // Neutral, direction-less — the same copy as the follow-POST constant 404.
                    CreateConversationOutcome.RecipientNotFound ->
                        s.copy(isOpeningChat = false, message = ProfileMessage.TARGET_UNAVAILABLE)
                    // SelfConversation is unreachable (no action on the self read); SessionExpired is also
                    // re-routed by the Auth plugin — both share the share-to-chat picker's Failed bucket.
                    CreateConversationOutcome.SelfConversation,
                    CreateConversationOutcome.Error,
                    CreateConversationOutcome.NetworkError,
                    CreateConversationOutcome.SessionExpired,
                    -> s.copy(isOpeningChat = false, message = ProfileMessage.ACTION_FAILED)
                }
            }
        }
    }

    /** Clears the one-shot [ProfileUiState.openChat] after the screen has navigated. */
    fun onChatOpened() = state.update { it.copy(openChat = null) }

    /** Clears the one-shot [ProfileUiState.message] after the screen has shown it. */
    fun onMessageShown() = state.update { it.copy(message = null) }

    /** Clears the one-shot [ProfileUiState.navigateBack] after the screen has popped. */
    fun onNavigatedBack() = state.update { it.copy(navigateBack = false) }

    private fun VmState.toUiState(): ProfileUiState =
        profileUiState(
            outcome = outcome,
            isInitialLoad = isInitialLoad,
            optimisticFollowed = optimisticFollowed,
            isFollowInFlight = isFollowInFlight,
            message = message,
            navigateBack = navigateBack,
            isOpeningChat = isOpeningChat,
            openChat = openChat,
        )
}
