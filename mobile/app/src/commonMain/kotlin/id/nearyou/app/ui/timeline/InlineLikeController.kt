package id.nearyou.app.ui.timeline

import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.post.LikeOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ONE shared inline-like lifecycle for the feed cards (`mobile-inline-post-actions`, design D2) —
 * `NearbyTimelineViewModel` and `GlobalTimelineViewModel` each hold their own instance of this class
 * (per-surface), generic over their feed's post type [P], so the optimistic/revert/in-flight/cap logic
 * exists exactly once. Compose-free and commonTest-unit-testable.
 *
 * Lifecycle per [toggle]:
 *  1. **Per-post in-flight guard** (per surface): a re-tap on a post whose toggle is still in flight is
 *     ignored — no concurrent duplicate `POST`/`DELETE` from this surface. The guard is deliberately NOT
 *     cross-surface: the same post on the other feed may toggle concurrently, which is harmless by the
 *     backend contract (idempotent 204s; a re-like releases its rate-limit slot).
 *  2. **Optimistic flip** of the tapped post's liked state inside the host's retained `Loaded` outcome
 *     (via [readPosts]/[writePosts] — the list stays mounted, only the tapped post changes). The
 *     direction comes from the post's CURRENT state ([toggle]'s `currentlyLiked`) — both directions are
 *     first-class (`false` → like/POST, `true` → unlike/DELETE).
 *  3. **Outcome application against the CURRENT outcome** (an interleaved refresh may have swapped the
 *     list mid-flight; if the post is gone from the fresh list the write is a no-op — no crash, no
 *     stale-state resurrection):
 *     - `Liked`/`Unliked` → re-assert the optimistic state (a mid-flight refresh may have overwritten it
 *       with pre-toggle server data).
 *     - `RateLimited` → revert + raise the one-shot cap state ([capRetryAfterSeconds]).
 *     - `PostGone` → revert + [onPostGone] (the host's `reload()` — the refreshed page drops the post).
 *     - `NetworkError` → revert silently (the spec-recorded v1 posture; no transient-error substrate).
 *
 * The cap signal is **one-shot state, not an event stream** (docs/11 § 2.2): a nullable value cleared by
 * [onCapDialogDismissed] — the host renders the cap dialog while it is non-null. `CancellationException`
 * from the seam propagates (never mapped); the in-flight slot is released in `finally`.
 */
class InlineLikeController<P>(
    private val likeFlow: LikeFlow,
    private val scope: CoroutineScope,
    private val idOf: (P) -> String,
    private val setLiked: (P, Boolean) -> P,
    private val readPosts: () -> List<P>?,
    private val writePosts: (List<P>) -> Unit,
    private val onPostGone: () -> Unit,
) {
    private val inFlight = mutableSetOf<String>()

    private val _capRetryAfterSeconds = MutableStateFlow<Long?>(null)

    /** Non-null while a rate-limited like's cap dialog should be shown; carries the 429 `Retry-After`. */
    val capRetryAfterSeconds: StateFlow<Long?> = _capRetryAfterSeconds.asStateFlow()

    /** Flips [postId] optimistically and runs the toggle through the shared [LikeFlow] seam. */
    fun toggle(
        postId: String,
        currentlyLiked: Boolean,
    ) {
        // Claimed synchronously (the PostDetailScreen 05-#10 double-tap precedent): both taps of a
        // same-frame double tap would otherwise pass the guard and double-POST.
        if (!inFlight.add(postId)) return
        val target = !currentlyLiked
        applyLiked(postId, target)
        scope.launch {
            try {
                when (val outcome = likeFlow.toggleLike(postId, currentlyLiked)) {
                    LikeOutcome.Liked, LikeOutcome.Unliked -> applyLiked(postId, target)
                    is LikeOutcome.RateLimited -> {
                        applyLiked(postId, currentlyLiked)
                        _capRetryAfterSeconds.value = outcome.retryAfterSeconds
                    }
                    LikeOutcome.PostGone -> {
                        applyLiked(postId, currentlyLiked)
                        onPostGone()
                    }
                    LikeOutcome.NetworkError -> applyLiked(postId, currentlyLiked)
                }
            } finally {
                inFlight.remove(postId)
            }
        }
    }

    /** Clears the one-shot cap state (the dialog's dismiss/CTA callbacks — docs/11 § 2.2). */
    fun onCapDialogDismissed() {
        _capRetryAfterSeconds.value = null
    }

    /** Applies [liked] to [postId] against the host's CURRENT posts; a no-op when the list is absent
     *  (no `Loaded` outcome) or the post has left it (an interleaved refresh dropped it). */
    private fun applyLiked(
        postId: String,
        liked: Boolean,
    ) {
        val posts = readPosts() ?: return
        if (posts.none { idOf(it) == postId }) return
        writePosts(posts.map { if (idOf(it) == postId) setLiked(it, liked) else it })
    }
}
