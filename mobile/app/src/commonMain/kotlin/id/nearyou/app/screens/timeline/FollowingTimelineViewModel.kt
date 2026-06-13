package id.nearyou.app.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.ui.timeline.InlineLikeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * `HomeRoute`-scoped ViewModel owning the Following feed's first-page load state. Resolved via
 * `viewModel { … }` inside the Following tab's screen — which (design D1/D2) composes **directly** under
 * the `HomeRoute` `NavEntry`, so the VM binds to the `HomeRoute` ViewModel store and **survives feed
 * swipes/tab switches, bottom-nav section switches, AND the composer round-trip**, cleared only when
 * `HomeRoute` is popped. That is what makes returning to the Following tab reuse the already-loaded feed
 * instead of re-running `loadFirstPage()` — mirroring `NearbyTimelineViewModel` / `GlobalTimelineViewModel`.
 *
 * The first load runs once on construction; [reload] re-fetches (pull-to-refresh + error retry). A load
 * failure maps to the EXISTING retryable [FollowingTimelineOutcome.NetworkError] (no new outcome member).
 *
 * Loading is split into [isInitialLoad] (true until the first outcome arrives — drives the skeleton) and
 * [isRefreshing] (true during a [reload] while a prior outcome is retained — drives only the
 * `PullToRefreshBox` indicator), per the canonical loading/refresh pattern (design D3). Mirrors
 * `GlobalTimelineViewModel`.
 */
class FollowingTimelineViewModel(
    private val flow: FollowingTimelineFlow,
    likeFlow: LikeFlow,
) : ViewModel() {
    private val _outcome = MutableStateFlow<FollowingTimelineOutcome?>(null)
    val outcome: StateFlow<FollowingTimelineOutcome?> = _outcome.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // mobile-inline-post-actions: the Following surface's instance of the SAME shared inline-like
    // controller class Nearby/Global use (no per-feed duplicate of the lifecycle), over the same LikeFlow
    // Koin singleton. Behavior identical: optimistic flip in the retained Loaded outcome; PostGone
    // self-heals via reload(); NetworkError reverts silently (declared v1).
    private val likeController =
        InlineLikeController(
            likeFlow = likeFlow,
            scope = viewModelScope,
            idOf = { it.id },
            setLiked = { post, liked -> post.copy(likedByViewer = liked) },
            readPosts = { (_outcome.value as? FollowingTimelineOutcome.Loaded)?.posts },
            writePosts = { posts ->
                val current = _outcome.value
                if (current is FollowingTimelineOutcome.Loaded) _outcome.value = current.copy(posts = posts)
            },
            onPostGone = ::reload,
        )

    /** Non-null while the Free like-cap dialog should be shown (one-shot state, docs/11 § 2.2). */
    val likeCapRetryAfterSeconds: StateFlow<Long?> = likeController.capRetryAfterSeconds

    init {
        load(initial = true)
    }

    /** Inline like tap: [currentlyLiked] is the tapped card's CURRENT state (both directions valid). */
    fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ) = likeController.toggle(postId, currentlyLiked)

    /** Clears the one-shot cap-dialog state (the dialog's dismiss + v1 Premium-CTA wiring). */
    fun onLikeCapDialogDismissed() = likeController.onCapDialogDismissed()

    /** Pull-to-refresh + error-retry both call this — re-fetches page 1 while keeping content mounted. */
    fun reload() {
        // Reentrancy guard (mirrors Global/Nearby): stacked PTR + retry taps would otherwise race
        // concurrent fetches. One reload at a time; the next gesture re-fires after.
        if (_isRefreshing.value || _isInitialLoad.value) return
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
                // The fetch threw → existing retryable error state (network copy + retry). No new
                // FollowingTimelineOutcome member is introduced.
                _outcome.value = FollowingTimelineOutcome.NetworkError
            } finally {
                _isInitialLoad.value = false
                _isRefreshing.value = false
            }
        }
    }
}
