package id.nearyou.app.post

/**
 * The post-editing orchestration contract (the `mobile-post-editing` capability). The production binding
 * is [PostEditRepository] (a stateless Koin singleton); commonTest substitutes a `FakePostEditFlow` so the
 * `EditPostViewModel` + screen tests drive specific outcomes without a backend — mirroring the
 * [PostDetailFlow] seam. Every method takes the `postId` explicitly (the repository is a singleton).
 *
 * Each method maps every HTTP status + transport failure to EXACTLY one member of its sealed outcome, with
 * no generic "failed" wildcard; `401` is delegated to the shipped `Auth` plugin (never mapped here) and
 * `CancellationException` is rethrown by the ApiClient layer.
 */
interface PostEditFlow {
    /** `PATCH /api/v1/posts/{postId}` with `{ "content": <content> }` (the Premium content edit). */
    suspend fun editPost(
        postId: String,
        content: String,
    ): PostEditOutcome

    /** `GET /api/v1/posts/{postId}/edits` — the chronological "Versi ke-N" history for the modal. */
    suspend fun loadHistory(postId: String): EditHistoryOutcome

    /** `GET /api/v1/posts/{postId}` (`single-post-read`) — freshens post-detail's content + reads the
     *  `editedAt` edited-signal. A failure degrades to [PostRefreshOutcome.Unavailable] (the surface keeps
     *  its nav-payload content; the "Diedit" label stays hidden) — it NEVER errors the post-detail screen. */
    suspend fun refreshPost(postId: String): PostRefreshOutcome
}

/**
 * Every edit PATCH maps to EXACTLY one member, keyed on the HTTP **status** + the parsed `error.code`
 * where the backend distinguishes (it does, for `400` / `409`). `200` → [Success]; `403 premium_required`
 * → [PremiumRequired]; `409 edit_window_expired` → [WindowExpired]; `409 edit_conflict` (the retryable
 * temporal collision) → [Conflict]; `400 no_changes` → [NoChanges]; `400 content_moderated_*` →
 * [ContentModerated]; `429` → [RateLimited]; `404` → [NotFound]; `5xx`/IO/any-other (incl. a defensive
 * `400` for empty/over-length the client gate already blocks) → the retryable [Network]. No member carries PII.
 */
sealed interface PostEditOutcome {
    /** `200` — the updated content (the caller returns to detail showing it + the "Diedit" label). */
    data class Success(val content: String) : PostEditOutcome

    /** `403 premium_required` — surface the "Aktifkan Premium" upsell (reactive gate, design D2). */
    data object PremiumRequired : PostEditOutcome

    /** `409 edit_window_expired` — the 30-minute window has passed (safe to reveal to the owner). */
    data object WindowExpired : PostEditOutcome

    /** `400 no_changes` — the normalized content equals the current content (no edit recorded). */
    data object NoChanges : PostEditOutcome

    /** `400 content_moderated_*` — the edit was rejected by moderation. */
    data object ContentModerated : PostEditOutcome

    /** `409 edit_conflict` — the retryable temporal collision ("Coba lagi sebentar."). */
    data object Conflict : PostEditOutcome

    /** `429` — carries the `Retry-After` seconds; the screen shows a rate-limit message (no silent retry). */
    data class RateLimited(val retryAfterSeconds: Long) : PostEditOutcome

    /** `404 post_not_found` — the post is gone / invisible. */
    data object NotFound : PostEditOutcome

    /** `5xx`, transport/IO, or any unenumerated status — retryable. */
    data object Network : PostEditOutcome
}

/**
 * The edit-history fetch maps to EXACTLY one member: `200` → [Loaded] (the parsed versions, already in the
 * backend's chronological order with their "Versi ke-N" labels); any non-200 (incl. `404`) + transport/IO
 * → the retryable [Network] (the modal's spec'd states are loading / loaded / empty / error).
 */
sealed interface EditHistoryOutcome {
    data class Loaded(val versions: List<EditVersionDto>) : EditHistoryOutcome

    data object Network : EditHistoryOutcome
}

/**
 * The post-freshness fetch maps to [Loaded] (`200` with the current content + nullable `editedAt`) or
 * [Unavailable] (any non-200 + transport/IO). [Unavailable] is the graceful-degradation signal: post-detail
 * keeps its nav-payload content and hides the "Diedit" label.
 */
sealed interface PostRefreshOutcome {
    data class Loaded(
        val content: String,
        val editedAt: String?,
        val isAuthor: Boolean,
    ) : PostRefreshOutcome

    data object Unavailable : PostRefreshOutcome
}
