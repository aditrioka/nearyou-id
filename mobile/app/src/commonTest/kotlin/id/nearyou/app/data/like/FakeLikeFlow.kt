package id.nearyou.app.data.like

import id.nearyou.app.post.LikeOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [LikeFlow] for the inline-like controller / ViewModel / screen tests. Returns a
 * pre-programmed [outcome], counts invocations, and records the `(postId, currentlyLiked)` args so a
 * test can pin the toggle DIRECTION (the unlike scenario — the direction must never be hardcoded).
 * With [gate] set, `toggleLike` suspends until the deferred completes — lets `runTest` tests observe
 * the in-flight state and release it deterministically. With [suspendForever] = true it never returns
 * (the `awaitCancellation` idiom the timeline fakes use for non-`runTest` ViewModel tests).
 */
class FakeLikeFlow(
    var outcome: LikeOutcome = LikeOutcome.Liked,
) : LikeFlow {
    var invocationCount: Int = 0
        private set

    /** Recorded `(postId, currentlyLiked)` per call — pins the toggle direction. */
    val invocations = mutableListOf<Pair<String, Boolean>>()

    /** When set, `toggleLike` suspends until completed (release with `gate?.complete(Unit)`). */
    var gate: CompletableDeferred<Unit>? = null

    /** When true, `toggleLike` never returns (for non-`runTest` in-flight assertions). */
    var suspendForever: Boolean = false

    override suspend fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ): LikeOutcome {
        invocationCount++
        invocations += postId to currentlyLiked
        if (suspendForever) awaitCancellation()
        gate?.await()
        return outcome
    }
}
