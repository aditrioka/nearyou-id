package id.nearyou.app.post

/**
 * Orchestrates the post-detail operations (design D6): delegates to [likeApiClient] / [replyApiClient]
 * and maps each low-level `*ApiResult` to EXACTLY one member of its sealed outcome, keyed on the HTTP
 * **status** (and the parsed `error.code` only where the backend distinguishes by code — it does not,
 * for these endpoints), with no generic "failed" wildcard. A stateless Koin singleton: every method
 * takes the [postId] explicitly (so `single<PostDetailFlow> { get<PostDetailRepository>() }` is safe).
 *
 * Append-on-201 is the CALLER's concern — [postReply] returns [ReplyPostOutcome.Success] with the new
 * [ReplyDto]; the repository does NOT re-fetch the list (design D9).
 *
 * PII discipline: the [diagnosticLog] sink is **coordinate-free by construction** — its signature carries
 * only the HTTP `status` + `errorCode` primitives, never a body, coordinate, or `author_id`. These
 * endpoints carry no coordinate at all; the repository never `println`s/logs bodies (spec § "No author
 * identifier or coordinate is rendered or logged").
 */
class PostDetailRepository(
    private val likeApiClient: LikeApiClient,
    private val replyApiClient: ReplyApiClient,
    // Diagnostic sink for non-user-facing error detail (status + error code only). Wired to Sentry /
    // OTel when that lands; no-op for now. MUST NOT carry tokens, bodies, or coordinates.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) : PostDetailFlow {
    override suspend fun loadReplies(postId: String): RepliesOutcome =
        when (val result = replyApiClient.listReplies(postId)) {
            is ReplyListApiResult.Success ->
                RepliesOutcome.Loaded(replies = result.body.replies, nextCursor = result.body.nextCursor)
            is ReplyListApiResult.NetworkError -> RepliesOutcome.NetworkError
            // Any non-200 (incl. 404 post_not_found) → the retryable error state; the list surface's
            // spec'd states are loading / loaded / empty / error (no distinct PostGone member here).
            is ReplyListApiResult.HttpError -> RepliesOutcome.NetworkError
        }

    override suspend fun loadMoreReplies(
        postId: String,
        cursor: String,
    ): RepliesOutcome =
        when (val result = replyApiClient.listReplies(postId, cursor)) {
            is ReplyListApiResult.Success ->
                RepliesOutcome.Loaded(replies = result.body.replies, nextCursor = result.body.nextCursor)
            is ReplyListApiResult.NetworkError -> RepliesOutcome.NetworkError
            is ReplyListApiResult.HttpError -> RepliesOutcome.NetworkError
        }

    override suspend fun toggleLike(
        postId: String,
        currentlyLiked: Boolean,
    ): LikeOutcome {
        val result = if (currentlyLiked) likeApiClient.unlike(postId) else likeApiClient.like(postId)
        return when (result) {
            // 204 → the direction the caller requested (DELETE→Unliked, POST→Liked). DELETE never 404s.
            LikeApiResult.NoContent -> if (currentlyLiked) LikeOutcome.Unliked else LikeOutcome.Liked
            is LikeApiResult.NetworkError -> LikeOutcome.NetworkError
            is LikeApiResult.HttpError -> mapLikeHttpError(result)
        }
    }

    override suspend fun postReply(
        postId: String,
        content: String,
    ): ReplyPostOutcome =
        when (val result = replyApiClient.postReply(postId, content)) {
            is ReplyPostApiResult.Created -> ReplyPostOutcome.Success(result.reply)
            is ReplyPostApiResult.NetworkError -> ReplyPostOutcome.NetworkError
            is ReplyPostApiResult.HttpError -> mapReplyHttpError(result)
        }

    override suspend fun likeCount(postId: String): LikeCountOutcome =
        when (val result = likeApiClient.count(postId)) {
            is LikeCountApiResult.Success -> LikeCountOutcome.Available(result.count)
            // Graceful degradation: a non-200 OR a transport failure both hide the count (toggle stays usable).
            is LikeCountApiResult.HttpError -> LikeCountOutcome.Unavailable
            is LikeCountApiResult.NetworkError -> LikeCountOutcome.Unavailable
        }

    /** Maps a non-204 like response. 429 / 404 each → one member; 5xx + any other unenumerated status →
     *  the DEFINED retryable [LikeOutcome.NetworkError] (NOT a generic "failed" member). 401 never reaches
     *  here — the shipped `Auth` plugin consumes it upstream. */
    private fun mapLikeHttpError(error: LikeApiResult.HttpError): LikeOutcome =
        when {
            error.status == 429 -> LikeOutcome.RateLimited(error.retryAfterSeconds ?: 0L)
            error.status == 404 -> LikeOutcome.PostGone
            error.status in 500..599 -> LikeOutcome.NetworkError
            else -> LikeOutcome.NetworkError
        }

    /** Maps a non-201 reply-POST response. 429 / 404 / 400 each → one member (400 → the single
     *  [ReplyPostOutcome.InvalidContent], with a logged diagnostic — NOT split into empty/too-long); 5xx +
     *  any other unenumerated status → the DEFINED retryable [ReplyPostOutcome.NetworkError]. 401 never
     *  reaches here. */
    private fun mapReplyHttpError(error: ReplyPostApiResult.HttpError): ReplyPostOutcome =
        when {
            error.status == 429 -> ReplyPostOutcome.RateLimited(error.retryAfterSeconds ?: 0L)
            error.status == 404 -> ReplyPostOutcome.PostGone
            error.status == 400 -> {
                // The backend emits the SAME invalid_request for empty + over-limit; the client gate
                // already blocks both, so a 400 here is a defensive edge — retryable banner + a logged
                // (coordinate-free) diagnostic, never a crash or silent no-op.
                diagnosticLog(error.status, error.errorCode)
                ReplyPostOutcome.InvalidContent
            }
            error.status in 500..599 -> ReplyPostOutcome.NetworkError
            else -> ReplyPostOutcome.NetworkError
        }
}
