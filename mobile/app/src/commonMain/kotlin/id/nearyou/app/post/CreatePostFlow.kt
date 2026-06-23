package id.nearyou.app.post

/**
 * The post-creation orchestration contract consumed by `PostCreationScreen`. The production binding
 * is [CreatePostRepository]; commonTest substitutes a `FakeCreatePostFlow` so the screen test can
 * drive a specific outcome without a backend or a device — mirroring `mobile-nearby-timeline`'s
 * `NearbyTimelineFlow` seam and `mobile-auth-signin`'s `AuthFlow` seam.
 */
interface CreatePostFlow {
    /**
     * Permission-gates and acquires the device coordinate, issues `POST /api/v1/posts`, and maps the
     * result to exactly one [PostCreationOutcome] (design D1/D3). The client content length pre-check
     * lives in the pure UI projection (`postCreationUiState`); the server remains authoritative.
     *
     * [imageId] (image-attached-posts) is the OPTIONAL id of an already-uploaded image — supplied by the
     * composer ONLY after a successful two-step upload (`ImageUploadOutcome.Success`). It defaults to
     * `null` (the text-only path), in which case the create body omits `image_id` entirely (byte-identical
     * to the pre-change body). A failed/missing upload never reaches here with an id, so a post can never
     * reference a dangling image.
     */
    suspend fun submit(
        content: String,
        imageId: String? = null,
    ): PostCreationOutcome
}
