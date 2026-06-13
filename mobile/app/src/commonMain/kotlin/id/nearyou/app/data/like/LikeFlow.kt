package id.nearyou.app.data.like

import id.nearyou.app.post.LikeOutcome

/**
 * The cross-surface like seam (`mobile-inline-post-actions`, design D1) — the ONE like toggle the
 * post-detail screen AND the feed cards' inline like share. Extracted from `PostDetailFlow` (which now
 * extends this interface) so the timeline ViewModels can depend on exactly the like surface without
 * dragging the detail screen's reply/list methods along. The production binding is the SAME
 * `PostDetailRepository` Koin singleton behind `PostDetailFlow` (`single<LikeFlow> { get<PostDetailRepository>() }`)
 * — there is deliberately NO second like ApiClient/repository and NO duplicate status→[LikeOutcome]
 * mapping. [LikeOutcome] itself stays in `id.nearyou.app.post` (no mechanical file moves).
 */
interface LikeFlow {
    /** `POST` (when [currentlyLiked] = false) or `DELETE` (when true) `/api/v1/posts/{postId}/like`.
     *  The caller flips optimistically and reverts on a non-`Liked`/`Unliked` outcome. Both verbs are
     *  idempotent 204s on the backend (a re-like even releases its rate-limit slot), so a stale
     *  [currentlyLiked] snapshot is safe by contract. */
    suspend fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ): LikeOutcome
}
