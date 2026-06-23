package id.nearyou.app.post

import kotlinx.coroutines.awaitCancellation

/**
 * Test-only [CreatePostFlow] for screen tests. Returns a pre-programmed [PostCreationOutcome] and
 * records the submitted content + invocation count so a test can drive a specific banner / success
 * and assert the CTA wired through (mirrors `FakeNearbyTimelineFlow` / `FakeAuthFlow`). With
 * [suspendForever] = true, `submit` never returns — the screen stays in-flight so the Loading state
 * (disabled CTA + loading copy) can be asserted.
 */
class FakeCreatePostFlow(
    private val outcome: PostCreationOutcome = PostCreationOutcome.Success("p1"),
    private val suspendForever: Boolean = false,
) : CreatePostFlow {
    var submitInvocationCount: Int = 0
        private set

    var lastSubmittedContent: String? = null
        private set

    /** image-attached-posts: the imageId passed on the last submit (null ⇒ the text-only path). Lets a
     *  test assert the create body carries the uploaded id ONLY when an image was attached. */
    var lastSubmittedImageId: String? = null
        private set

    override suspend fun submit(
        content: String,
        imageId: String?,
    ): PostCreationOutcome {
        submitInvocationCount++
        lastSubmittedContent = content
        lastSubmittedImageId = imageId
        if (suspendForever) awaitCancellation()
        return outcome
    }
}
