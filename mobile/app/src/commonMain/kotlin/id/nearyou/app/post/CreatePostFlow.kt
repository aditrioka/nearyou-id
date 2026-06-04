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
     */
    suspend fun submit(content: String): PostCreationOutcome
}
