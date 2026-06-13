package id.nearyou.app.post

import id.nearyou.app.data.like.LikeFlow

/**
 * The post-detail orchestration contract consumed by `PostDetailScreen`. The production binding is
 * [PostDetailRepository] (a stateless Koin singleton); commonTest substitutes a `FakePostDetailFlow` so
 * the screen tests can drive specific outcomes without a backend — mirroring `mobile-nearby-timeline`'s
 * `NearbyTimelineFlow` seam and `mobile-post-creation`'s `CreatePostFlow` seam (design D6).
 *
 * As of `mobile-inline-post-actions` the like half of the seam lives in the [LikeFlow] super-interface
 * (`data/like/LikeFlow.kt`) — `toggleLike` is inherited, not re-declared — so the feed cards' inline
 * like shares the SAME `PostDetailRepository` singleton (`single<LikeFlow> { get<PostDetailRepository>() }`)
 * without depending on the detail-screen surface.
 *
 * Every method takes the [postId] explicitly (the repository is a singleton — it cannot hold a
 * per-screen post id), and maps each HTTP status + transport-failure to EXACTLY one member of its
 * per-operation sealed outcome, with no generic "failed" wildcard. `401` is delegated to the shipped
 * `Auth` plugin (terminal 401 → `SessionInvalidator` → `SignInScreen`) and is NEVER mapped here;
 * `CancellationException` is rethrown by the ApiClient layer (never mapped to `NetworkError`).
 */
interface PostDetailFlow : LikeFlow {
    /** `GET /api/v1/posts/{postId}/replies` (first page). `next_cursor` is retained on [RepliesOutcome.Loaded]
     *  but cursor load-more is NOT wired in this change (deferred). */
    suspend fun loadReplies(postId: String): RepliesOutcome

    /** `POST /api/v1/posts/{postId}/replies` with `{ "content": <content> }`. On [ReplyPostOutcome.Success]
     *  the caller appends the returned reply locally + bumps the count — the repo does NOT re-fetch. */
    suspend fun postReply(
        postId: String,
        content: String,
    ): ReplyPostOutcome

    /** `GET /api/v1/posts/{postId}/likes/count`. A fetch failure degrades to [LikeCountOutcome.Unavailable]
     *  (the count is hidden; the toggle stays functional). */
    suspend fun likeCount(postId: String): LikeCountOutcome
}

/**
 * Every like toggle maps to EXACTLY one member, keyed on the HTTP **status** (the like endpoints carry
 * no distinguishing `error.code`). `204` → [Liked] / [Unliked] (per the requested direction); `429` →
 * [RateLimited]; `404` → [PostGone]; `5xx`/IO/any-other → the DEFINED retryable [NetworkError] (NOT a
 * generic "failed" member). `401` is delegated to the `Auth` plugin. No member carries PII.
 */
sealed interface LikeOutcome {
    data object Liked : LikeOutcome

    data object Unliked : LikeOutcome

    /** `429` — carries the `Retry-After` seconds (the screen renders the cap upsell + a coarse countdown). */
    data class RateLimited(val retryAfterSeconds: Long) : LikeOutcome

    /** `404 post_not_found` — the post is gone / invisible. */
    data object PostGone : LikeOutcome

    /** `5xx`, transport/IO, or any unenumerated status — retryable. */
    data object NetworkError : LikeOutcome
}

/**
 * Every reply POST maps to EXACTLY one member. `201` → [Success] (the returned [ReplyDto]); `429` →
 * [RateLimited]; `404` → [PostGone]; `400` → the single [InvalidContent] (the SHIPPED backend emits ONE
 * `invalid_request` code for BOTH empty and >280 content — the empty-vs-too-long distinction is the
 * client-side pre-submit code-point gate, NOT a server split); `5xx`/IO/any-other → [NetworkError].
 */
sealed interface ReplyPostOutcome {
    /** `201` — carries the parsed [ReplyDto] for local append (the caller bumps the displayed count). */
    data class Success(val reply: ReplyDto) : ReplyPostOutcome

    /** `429` — carries the `Retry-After` seconds; the screen renders the reply-cap upsell. */
    data class RateLimited(val retryAfterSeconds: Long) : ReplyPostOutcome

    /** `404 post_not_found`. */
    data object PostGone : ReplyPostOutcome

    /** `400 invalid_request` — the SINGLE mapping for empty + over-limit (defensive given the client
     *  guard already blocks both); a retryable banner with a logged diagnostic, NOT a crash, NOT a
     *  silent no-op. MUST NOT be split into distinct empty/too-long server outcomes. */
    data object InvalidContent : ReplyPostOutcome

    /** `5xx`, transport/IO, or any unenumerated status — retryable. */
    data object NetworkError : ReplyPostOutcome
}

/**
 * The replies-list fetch maps to EXACTLY one member: `200` → [Loaded] (carrying the parsed replies + the
 * retained-not-consumed [Loaded.nextCursor]); any non-200 (incl. `404`) + transport/IO → the retryable
 * [NetworkError]. (`404` is folded into [NetworkError] rather than a distinct member — the list surface's
 * spec'd states are only loading / loaded / empty / error.)
 */
sealed interface RepliesOutcome {
    data class Loaded(
        val replies: List<ReplyDto>,
        val nextCursor: String?,
    ) : RepliesOutcome

    data object NetworkError : RepliesOutcome
}

/**
 * The like-count fetch maps to [Available] (`200` with the parsed count) or [Unavailable] (any non-200
 * + transport/IO). [Unavailable] is the graceful-degradation signal: the screen hides the count node but
 * keeps the toggle functional (design D5 / spec § "count shown when available; toggle always present").
 */
sealed interface LikeCountOutcome {
    data class Available(val count: Long) : LikeCountOutcome

    data object Unavailable : LikeCountOutcome
}
