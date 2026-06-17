package id.nearyou.app.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.notifications.MarkAllReadResult
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationDto
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
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
 * re-fetch (design D8). Both map over the retained `Loaded.items`, so once load-more has appended pages
 * they operate over the GROWN list (mark-all-read flips appended rows too). The unread **badge** count is
 * owned separately by the shell (a one-shot `unread-count` fetch, refreshed on leaving the section), so it
 * is NOT recomputed here.
 *
 * Cursor load-more (infinite scroll) appends pages into the same retained `Loaded` outcome via the shared
 * [LoadMoreController] (`mobile-nearby-timeline-infinite-scroll`, extended to notifications), reusing the
 * first page's `unread=false` filter and gated so it never runs during the initial load or a refresh.
 */
class NotificationsViewModel(
    private val flow: NotificationsFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<NotificationsOutcome?>(null)
    val outcome: StateFlow<NotificationsOutcome?> = _outcome.asStateFlow()

    // Loading is split into isInitialLoad (drives the skeleton, true until the
    // first outcome arrives) and isRefreshing (drives ONLY the PullToRefreshBox
    // indicator while the prior outcome stays mounted) — the canonical
    // mobile-design-system "list loading and refresh" pattern the timeline VMs
    // migrated to in #167. This VM had kept the pre-split single `inFlight`
    // flag (2026-06-10 audit, finding 05-#2): refresh tore Content down to the
    // skeleton AND showed two progress indicators at once.
    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // mobile-nearby-timeline-infinite-scroll (extended to notifications): the notifications instance of the
    // ONE shared load-more lifecycle (ui/timeline/LoadMoreController). Appends pages into the retained
    // Loaded outcome — so the optimistic markRead / markAllRead mutations (which map over the retained
    // items list) operate over the GROWN list, appended rows included. There is NO spatial anchor (unlike
    // Nearby) and NO rate-limit / upsell state. Eligibility-gated so load-more never runs during the
    // initial load or a refresh.
    private val loadMoreController =
        LoadMoreController<NotificationDto>(
            scope = viewModelScope,
            currentCursor = { (_outcome.value as? NotificationsOutcome.Loaded)?.nextCursor },
            canLoadMore = { !_isInitialLoad.value && !_isRefreshing.value },
            fetchPage = { cursor ->
                when (val outcome = flow.loadMore(cursor)) {
                    is NotificationsOutcome.Loaded -> LoadMorePage.Success(outcome.items, outcome.nextCursor)
                    else -> LoadMorePage.Failure
                }
            },
            appendItems = { items, next ->
                val current = _outcome.value
                if (current is NotificationsOutcome.Loaded) {
                    _outcome.value = current.copy(items = current.items + items, nextCursor = next)
                }
            },
        )

    /** True while a load-more page is in flight — drives only the list-end footer spinner. */
    val isLoadingMore: StateFlow<Boolean> = loadMoreController.isLoadingMore

    /** True after a failed load-more — drives the non-destructive retry footer (loaded list retained). */
    val loadMoreError: StateFlow<Boolean> = loadMoreController.loadMoreError

    init {
        load(initial = true)
    }

    /** Scroll-end trigger from the screen — appends the next page (no-op during initial/refresh or at end). */
    fun onLoadMore() = loadMoreController.loadMore()

    /** Retry control on the load-more error footer — re-issues for the still-current cursor. */
    fun onRetryLoadMore() = loadMoreController.retry()

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (2026-06-10 audit, 06 medium): stacked PTR + retry taps
        // raced concurrent fetches — latest-writer-wins on outcome and a flickering
        // isRefreshing. One reload at a time; the next gesture re-fires after.
        if (_isRefreshing.value || _isInitialLoad.value) return
        // Refresh resets paging: load() swaps in a fresh first page (dropping the appended tail) and the
        // footer state is cleared here (mobile-design-system § load-more pattern).
        loadMoreController.reset()
        load(initial = false)
    }

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            if (!initial) _isRefreshing.value = true
            try {
                _outcome.value = flow.loadFirstPage()
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cancellation
            } catch (_: Throwable) {
                // The fetch threw → existing retryable error state. No new outcome member.
                _outcome.value = NotificationsOutcome.NetworkError
            } finally {
                _isInitialLoad.value = false
                _isRefreshing.value = false
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
        // Capture the IDs we actually flip (the currently-unread rows) so a failure reverts ONLY those,
        // mapping over the CURRENT list — an interleaved reload's newer rows survive (no whole-list clobber
        // / no resurrecting of since-removed rows), mirroring the per-id discipline of markRead's revert.
        val flippedIds = current.items.filter { it.readAt == null }.map { it.id }.toSet()
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
                    _outcome.value = now.copy(items = now.items.map { if (it.id in flippedIds) it.asUnread() else it })
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

/**
 * Reverts an optimistic read flip back to unread. **Only valid on rows we optimistically flipped from a
 * null `read_at`** — `markRead` early-returns on already-read rows, and `markAllRead` reverts only the IDs
 * it flipped, so this is never applied to a genuinely server-read row (which it would silently un-read).
 */
private fun NotificationDto.asUnread(): NotificationDto = copy(readAt = null)
