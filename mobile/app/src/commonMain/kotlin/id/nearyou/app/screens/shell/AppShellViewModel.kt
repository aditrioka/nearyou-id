package id.nearyou.app.screens.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.notifications.NotificationsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The authenticated app shell's state holder (docs/11 §2.2), owning the Notifikasi unread-count **badge**
 * (design D6). Resolved via `viewModel { … }` inside [AppShellScreen] — which IS the `HomeRoute` `NavEntry`
 * — so the VM binds to that store and the count survives **configuration changes (rotation)** AND bottom-nav
 * **section switches**, mirroring the `HomeRoute`-scoped feed / notifications ViewModels.
 *
 * This replaces the legacy `remember`-held count + in-composable `LaunchedEffect`/`DisposableEffect` fetch
 * (2026-06-10 audit, finding 05-#9): business/data work must not launch from a composable (docs/11 §2.2),
 * and the `remember`-held count silently **re-fetched on every rotation** (the `LaunchedEffect(Unit)` re-fired
 * against a freshly-reset holder). The fetch now lives in the retained VM, so it fires exactly once per
 * authenticated entry.
 *
 * The count is fetched ONCE in [init] (the one-shot on shell composition) and re-fetched ONCE via
 * [refreshUnreadCount] when the user leaves the Notifikasi section (having likely read some). There is **no**
 * polling timer / repeating delay / push-driven live subscription — live updates are deferred (follow-up
 * issue #197, `mobile-notifications-live-unread-badge`). A failed/absent count → 0 (no badge).
 */
class AppShellViewModel(
    private val flow: NotificationsFlow,
) : ViewModel() {
    private val unreadCount = MutableStateFlow(0L)

    /**
     * The single shell UI state (docs/11 §2.2): the unread count folded into [AppShellUiState] via [stateIn]
     * — computed in the VM, not re-derived in the composable. The bottom-nav Notifikasi badge is shown only
     * when [AppShellUiState.showBadge] (count > 0).
     */
    val uiState: StateFlow<AppShellUiState> =
        unreadCount
            .map { AppShellUiState(unreadCount = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppShellUiState())

    init {
        refreshUnreadCount()
    }

    /**
     * Re-fetches the unread count once (design D6 — one-shot, never a live/poll loop). Called in [init] on
     * shell composition and again when the Notifikasi section is left. A failed/absent count resets it to 0.
     */
    fun refreshUnreadCount() {
        viewModelScope.launch {
            unreadCount.value = flow.unreadCount() ?: 0L
        }
    }
}

/** The shell's single UI state (docs/11 §2.2): the Notifikasi unread-badge count. */
data class AppShellUiState(
    val unreadCount: Long = 0L,
) {
    /** The bottom-nav Notifikasi badge is shown only when there are unread notifications (count > 0). */
    val showBadge: Boolean get() = unreadCount > 0
}
