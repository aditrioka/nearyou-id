package id.nearyou.app.post

/**
 * Orchestrates the `mobile-post-editing` operations: delegates to [PostEditApiClient] (PATCH + history) and
 * [SinglePostApiClient] (the freshness GET) and maps each low-level `*ApiResult` to EXACTLY one member of
 * its sealed outcome, keyed on the HTTP **status** + the parsed `error.code` where the backend distinguishes
 * (`400` / `409`), with no generic "failed" wildcard. A stateless Koin singleton — every method takes the
 * `postId` explicitly (so `single<PostEditFlow> { get<PostEditRepository>() }` is safe).
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` + `errorCode` primitives — never a
 * body, content, coordinate, or `author_id`; the repository never `println`s/logs bodies.
 */
class PostEditRepository(
    private val editApiClient: PostEditApiClient,
    private val singlePostApiClient: SinglePostApiClient,
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) : PostEditFlow {
    override suspend fun editPost(
        postId: String,
        content: String,
    ): PostEditOutcome =
        when (val result = editApiClient.editPost(postId, content)) {
            is EditPostApiResult.Success -> PostEditOutcome.Success(result.content)
            is EditPostApiResult.NetworkError -> PostEditOutcome.Network
            is EditPostApiResult.HttpError -> mapEditHttpError(result)
        }

    override suspend fun loadHistory(postId: String): EditHistoryOutcome =
        when (val result = editApiClient.loadHistory(postId)) {
            is EditHistoryApiResult.Success -> EditHistoryOutcome.Loaded(result.versions)
            // Any non-200 (incl. 404) + transport → the retryable error state (loading/loaded/empty/error).
            is EditHistoryApiResult.HttpError -> EditHistoryOutcome.Network
            is EditHistoryApiResult.NetworkError -> EditHistoryOutcome.Network
        }

    override suspend fun refreshPost(postId: String): PostRefreshOutcome =
        when (val result = singlePostApiClient.fetchPost(postId)) {
            is SinglePostApiResult.Success ->
                PostRefreshOutcome.Loaded(result.post.content, result.post.editedAt, result.post.isAuthor)
            // Graceful degradation: a non-200 OR transport failure both leave post-detail on its nav payload.
            SinglePostApiResult.Unavailable -> PostRefreshOutcome.Unavailable
        }

    /**
     * Maps a non-200 edit response. Each documented (status, code) → ONE outcome; `5xx` + any unenumerated
     * status (incl. a defensive `400` for empty/over-length that the client gate already blocks) → the
     * DEFINED retryable [PostEditOutcome.Network]. `401` never reaches here (the `Auth` plugin consumes it).
     */
    private fun mapEditHttpError(error: EditPostApiResult.HttpError): PostEditOutcome =
        when {
            error.status == 403 -> PostEditOutcome.PremiumRequired
            error.status == 429 -> PostEditOutcome.RateLimited(error.retryAfterSeconds ?: 0L)
            error.status == 409 ->
                if (error.errorCode == "edit_window_expired") {
                    PostEditOutcome.WindowExpired
                } else {
                    // edit_conflict (the temporal collision) — and any other 409 — is the retryable conflict.
                    PostEditOutcome.Conflict
                }
            error.status == 400 ->
                when {
                    error.errorCode == "no_changes" -> PostEditOutcome.NoChanges
                    error.errorCode != null && error.errorCode.startsWith("content_moderated") ->
                        PostEditOutcome.ContentModerated
                    else -> {
                        // Empty / over-length: the client code-point gate already blocks both, so a 400 here
                        // is a defensive edge — a retryable network banner + a logged (coordinate-free)
                        // diagnostic, never a crash or silent no-op.
                        diagnosticLog(error.status, error.errorCode)
                        PostEditOutcome.Network
                    }
                }
            error.status == 404 -> PostEditOutcome.NotFound
            else -> PostEditOutcome.Network
        }
}
