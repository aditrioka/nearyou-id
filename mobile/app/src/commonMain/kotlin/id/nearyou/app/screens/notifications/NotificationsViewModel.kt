package id.nearyou.app.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.notifications.MarkAllReadResult
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationDto
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.PartnerResolution
import id.nearyou.app.notifications.PostTargetResolution
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.ui.timeline.LoadMoreController
import id.nearyou.app.ui.timeline.LoadMorePage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // mobile-notifications-deep-link-targets: tapping a row resolves a deep-link target IN ADDITION to the
    // optimistic mark-read. The resolved target is a NULLABLE, CONSUMED-ONCE field on the StateFlow (the
    // EditPostUiState one-shot-signal pattern) — the screen invokes the hoisted nav callback then calls
    // onNavConsumed() to clear it, so navigation does NOT re-fire on recomposition. NO Channel/SharedFlow
    // (forbidden by docs/11 § 2.2). actor_user_id / target_id / conversation_id are used ONLY as the
    // resolved target's payload / a fetch path param — never rendered nor logged (the no-PII discipline).
    private val _pendingNavTarget = MutableStateFlow<NotificationNavTarget?>(null)
    val pendingNavTarget: StateFlow<NotificationNavTarget?> = _pendingNavTarget.asStateFlow()

    // Consumed-once non-blocking signal: a post-target whose by-id fetch was Unavailable (404 / any failure).
    // The screen shows a transient "Postingan tidak tersedia" affordance (no navigation) and clears it.
    private val _postUnavailable = MutableStateFlow(false)
    val postUnavailable: StateFlow<Boolean> = _postUnavailable.asStateFlow()

    // The id of the row whose deep-link resolution (a fetch) is in flight, or null — drives a per-row
    // resolving indicator. A new tap supersedes/cancels the prior in-flight resolution (latest-tap-wins).
    private val _resolvingRowId = MutableStateFlow<String?>(null)
    val resolvingRowId: StateFlow<String?> = _resolvingRowId.asStateFlow()

    private var resolveJob: Job? = null

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
     * The row tap entry point: marks the row read (optimistic, unchanged) AND independently resolves its
     * deep-link target (mobile-notifications-deep-link-targets). The two are independent — a tap ALWAYS
     * marks read, and a post-target whose fetch fails still marks read while surfacing the non-blocking
     * "tidak tersedia" affordance.
     */
    fun onRowTap(id: String) {
        markRead(id)
        resolveNavTarget(id)
    }

    /** The screen calls this after consuming [pendingNavTarget] (invoking the hoisted nav callback) so the
     *  one-shot signal does not re-fire on recomposition (the EditPostUiState consumed-marker pattern). */
    fun onNavConsumed() {
        _pendingNavTarget.value = null
    }

    /** The screen calls this after showing the transient post-unavailable affordance, to clear the signal. */
    fun onPostUnavailableConsumed() {
        _postUnavailable.value = false
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

    // Monotonic token guarding the per-row resolving indicator against a superseded job's `finally`
    // clearing a newer job's indicator. The supersede CORRECTNESS (only the latest tap navigates) is
    // guaranteed independently by resolveJob.cancel() — a cancelled coroutine stops at the fetch
    // suspension point and never reaches the `_pendingNavTarget` assignment.
    private var resolveToken = 0

    /**
     * Resolves the tapped row's deep-link destination per the canonical (target_type, target_id) + actor
     * model and sets the consumed-once [pendingNavTarget]. `post` → full-projection fetch → post detail
     * (Unavailable → the non-blocking [postUnavailable] signal, no nav); `followed` (null target + actor) →
     * profile, no fetch; `chat_message` (message target + actor) → fetch the partner identity → chat thread
     * (partner-fetch failure → empty partner fields, the conversation is still opened). `chat_message_redacted`
     * (message target, NO actor), `reply` targets, informational null-target types, and unknown/missing-field
     * rows → no navigation. A new tap supersedes a prior in-flight resolution.
     */
    private fun resolveNavTarget(id: String) {
        resolveJob?.cancel()
        val token = ++resolveToken
        val current = _outcome.value as? NotificationsOutcome.Loaded ?: return
        val notification = current.items.firstOrNull { it.id == id } ?: return
        when (val intent = resolveIntent(notification)) {
            is NavIntent.OpenProfile -> {
                _resolvingRowId.value = null
                _pendingNavTarget.value = NotificationNavTarget.Profile(intent.userId)
            }
            is NavIntent.OpenPost ->
                resolveJob =
                    viewModelScope.launch {
                        _resolvingRowId.value = id
                        try {
                            when (val res = flow.resolvePostTarget(intent.postId)) {
                                is PostTargetResolution.Resolved ->
                                    _pendingNavTarget.value = NotificationNavTarget.Post(res.toPostDetailTarget())
                                PostTargetResolution.Unavailable -> _postUnavailable.value = true
                            }
                        } finally {
                            if (token == resolveToken) _resolvingRowId.value = null
                        }
                    }
            is NavIntent.OpenChat ->
                resolveJob =
                    viewModelScope.launch {
                        _resolvingRowId.value = id
                        try {
                            val (username, displayName) =
                                when (val partner = flow.resolvePartner(intent.actorUserId)) {
                                    is PartnerResolution.Resolved -> partner.username to partner.displayName
                                    // A failed partner lookup does NOT block the (valid) conversation — open
                                    // the thread with blank partner fields (top bar degrades to placeholder).
                                    PartnerResolution.Unavailable -> "" to ""
                                }
                            _pendingNavTarget.value =
                                NotificationNavTarget.ChatThread(intent.conversationId, username, displayName)
                        } finally {
                            if (token == resolveToken) _resolvingRowId.value = null
                        }
                    }
            NavIntent.None -> _resolvingRowId.value = null
        }
    }

    /** Pure mapping of a notification's canonical addressing to a typed nav intent (PII-free; no fetch). */
    private fun resolveIntent(n: NotificationDto): NavIntent =
        when (n.targetType) {
            "post" -> n.targetId?.let { NavIntent.OpenPost(it) } ?: NavIntent.None
            "message" -> {
                val conversationId = resolveConversationId(n.bodyData)
                val actor = n.actorUserId
                // chat_message has actor (= the 1:1 sender = partner); chat_message_redacted has actor=NULL
                // → None. A message row missing conversation_id → None.
                if (conversationId != null && actor != null) {
                    NavIntent.OpenChat(conversationId, actor)
                } else {
                    NavIntent.None
                }
            }
            // null target + actor present = `followed` (the only such type in the V10 catalog) → profile.
            null -> n.actorUserId?.let { NavIntent.OpenProfile(it) } ?: NavIntent.None
            // "reply" (post_auto_hidden dynamic case) + any unknown/future target_type → no destination.
            else -> NavIntent.None
        }

    private fun resolveConversationId(bodyData: JsonElement): String? {
        val obj = bodyData as? JsonObject ?: return null
        val primitive = obj["conversation_id"] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.ifBlank { null }
    }

    private fun PostTargetResolution.Resolved.toPostDetailTarget(): PostDetailTarget =
        PostDetailTarget(
            postId = postId,
            content = content,
            cityName = cityName,
            // The by-id single-post-read projection omits coordinates → no distance for a deep-linked post.
            distanceM = null,
            createdAtIso = createdAtIso,
            likedByViewer = likedByViewer,
            replyCount = replyCount,
            authorUsername = authorUsername,
            authorDisplayName = authorDisplayName,
        )

    private fun NotificationsOutcome.Loaded.withRowRead(id: String): NotificationsOutcome.Loaded =
        copy(items = items.map { if (it.id == id) it.asRead() else it })

    private fun revertRowRead(id: String) {
        val now = _outcome.value as? NotificationsOutcome.Loaded ?: return
        _outcome.value = now.copy(items = now.items.map { if (it.id == id) it.asUnread() else it })
    }
}

/**
 * The consumed-once deep-link target the screen reads from [NotificationsViewModel.pendingNavTarget] and
 * forwards to the matching hoisted nav callback (then calls `onNavConsumed()`). Carries display payload
 * only — [Profile.userId] / [ChatThread.conversationId] are route keys (never rendered), and the chat
 * partner fields are display strings (no UUID).
 */
sealed interface NotificationNavTarget {
    data class Post(val target: PostDetailTarget) : NotificationNavTarget

    data class Profile(val userId: String) : NotificationNavTarget

    data class ChatThread(
        val conversationId: String,
        val partnerUsername: String,
        val partnerDisplayName: String,
    ) : NotificationNavTarget
}

/** Internal, pre-fetch resolution of a tapped row's destination (the pure [resolveIntent] output). */
private sealed interface NavIntent {
    data class OpenPost(val postId: String) : NavIntent

    data class OpenProfile(val userId: String) : NavIntent

    data class OpenChat(val conversationId: String, val actorUserId: String) : NavIntent

    data object None : NavIntent
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
