package id.nearyou.app.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.notifications.MarkAllReadResult
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationDto
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shell-NavEntry-scoped ViewModel owning the notifications feed's load state + the optimistic read
 * mutations. Resolved via `viewModel { … }` inside `NotificationsScreen` — which composes under the shell
 * (the `HomeRoute` `NavEntry`), so the VM binds to that store and **survives bottom-nav section switches**
 * without re-fetch (design D7), mirroring the `HomeRoute`-scoped feed ViewModels. Constructed on the first
 * composition of the Notifikasi section; the first page loads once in [init]; [reload] re-fetches
 * (pull-to-refresh + error retry).
 *
 * Mark-read / mark-all-read are **optimistic** local mutations of the `Loaded` items: the row(s) flip to
 * read immediately, then `204`/`404` keeps the flip while any other transport failure reverts it — no full
 * re-fetch (design D8). The unread **badge** count is owned separately by the shell (a one-shot
 * `unread-count` fetch, refreshed on leaving the section), so it is NOT recomputed here.
 */
class NotificationsViewModel(
    private val flow: NotificationsFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<NotificationsOutcome?>(null)
    val outcome: StateFlow<NotificationsOutcome?> = _outcome.asStateFlow()

    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    init {
        load()
    }

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1. */
    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _inFlight.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // The fetch threw → existing retryable error state. No new outcome member.
                _outcome.value = NotificationsOutcome.NetworkError
            } finally {
                _inFlight.value = false
            }
        }
    }

    /**
     * Optimistically flips the row [id] to read, then issues `PATCH /{id}/read`. `204`/`404` keep the
     * flip (both look read); any other transport failure reverts it. Already-read rows are a no-op.
     */
    fun markRead(id: String) {
        val current = _outcome.value as? NotificationsOutcome.Loaded ?: return
        val target = current.items.firstOrNull { it.id == id } ?: return
        if (target.readAt != null) return // already read — nothing to do
        _outcome.value = current.withRowRead(id)
        viewModelScope.launch {
            val result =
                try {
                    flow.markRead(id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MarkReadResult.Failed
                }
            when (result) {
                MarkReadResult.Acknowledged, MarkReadResult.NotFound -> Unit // keep the flip
                MarkReadResult.Failed -> revertRowRead(id)
            }
        }
    }

    /**
     * Optimistically flips ALL loaded rows to read, then issues `PATCH /read-all`. On success the flip
     * stays; on any failure the pre-mutation snapshot is restored.
     */
    fun markAllRead() {
        val current = _outcome.value as? NotificationsOutcome.Loaded ?: return
        val snapshot = current.items
        _outcome.value = current.copy(items = current.items.map { it.asRead() })
        viewModelScope.launch {
            val result =
                try {
                    flow.markAllRead()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MarkAllReadResult.Failed
                }
            when (result) {
                is MarkAllReadResult.Success -> Unit // keep the flip
                MarkAllReadResult.Failed -> {
                    val now = _outcome.value as? NotificationsOutcome.Loaded ?: return@launch
                    _outcome.value = now.copy(items = snapshot)
                }
            }
        }
    }

    private fun NotificationsOutcome.Loaded.withRowRead(id: String): NotificationsOutcome.Loaded =
        copy(items = items.map { if (it.id == id) it.asRead() else it })

    private fun revertRowRead(id: String) {
        val now = _outcome.value as? NotificationsOutcome.Loaded ?: return
        _outcome.value = now.copy(items = now.items.map { if (it.id == id) it.asUnread() else it })
    }
}

/** A non-null `read_at` sentinel — the projection only checks `read_at != null`, so the value is opaque
 *  (never rendered). Used for the optimistic read flip before the server timestamp is known. */
private const val OPTIMISTIC_READ_AT = "optimistic"

private fun NotificationDto.asRead(): NotificationDto = if (readAt != null) this else copy(readAt = OPTIMISTIC_READ_AT)

private fun NotificationDto.asUnread(): NotificationDto = copy(readAt = null)
