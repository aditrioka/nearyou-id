package id.nearyou.app.screens.post

import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyDto
import id.nearyou.app.post.ReplyPostOutcome
import kotlin.math.ceil

/** The reply length limit — 280 code points (same cap as posts; `post-replies` content guard). The
 *  client gate counts RAW Unicode code points (no NFKC) for the live counter + submit-enable; the server
 *  NFKC-normalizes + length-guards authoritatively (design D9), so the client gate is only a UX nicety. */
const val MAX_REPLY_CONTENT_CODE_POINTS: Int = 280

/**
 * The display-only projection of a reply — the PII-stripped model the reply cards render. It carries ONLY
 * what a card shows: [content] + the [createdAt] treatment. There is deliberately NO `authorId` (a UUID,
 * PII — never rendered, consistent with the author-free post cards), NO surfaced `isAutoHidden` (the
 * viewer's own auto-hidden reply renders identically to a live one in v1 — design D7 / spec), and NO
 * `deletedAt` (effectively dead on this list path). [id] is the reply's own UUID (not author PII), kept as
 * a stable `LazyColumn` key.
 */
data class ReplyUi(
    val id: String,
    val content: String,
    val createdAt: String,
)

/** Strips a [ReplyDto] to its PII-free [ReplyUi] — drops `authorId`, `isAutoHidden`, `deletedAt`,
 *  `updatedAt`, `postId` so the rendered state STRUCTURALLY cannot leak author identity. */
fun ReplyDto.toUi(): ReplyUi = ReplyUi(id = id, content = content, createdAt = createdAt)

/**
 * Pure, Compose-free projection of the replies-list sub-surface, mirroring [NearbyTimelineUiState][id.nearyou.app.screens.timeline.NearbyTimelineUiState].
 * Deterministic over the [RepliesOutcome] + in-flight flag (no wall-clock / platform dependency); the
 * [Content] replies are [ReplyUi] (author id already dropped). Exhaustive — no generic fallthrough.
 */
sealed interface RepliesUiState {
    /** Fetch in-flight (incl. the pre-first-load window) → loading copy (`timeline_loading`). */
    data object Loading : RepliesUiState

    /** `Loaded`, non-empty → the reply-card list. */
    data class Content(val replies: List<ReplyUi>) : RepliesUiState

    /** `Loaded`, empty → the empty-state copy (`post_detail_replies_empty`). */
    data object Empty : RepliesUiState

    /** `NetworkError` → network copy + a retry control. */
    data object Error : RepliesUiState
}

/**
 * Maps the current replies [outcome] (null = not yet loaded) + [inFlight] to the list sub-state.
 * In-flight (or not-yet-loaded) ⇒ [Loading]; `Loaded` non-empty ⇒ [Content]; `Loaded` empty ⇒ [Empty];
 * `NetworkError` ⇒ [Error]. Exhaustive over [RepliesOutcome] (no wildcard).
 */
fun repliesUiState(
    outcome: RepliesOutcome?,
    inFlight: Boolean,
): RepliesUiState {
    if (inFlight) return RepliesUiState.Loading
    return when (outcome) {
        null -> RepliesUiState.Loading
        is RepliesOutcome.Loaded ->
            if (outcome.replies.isEmpty()) {
                RepliesUiState.Empty
            } else {
                RepliesUiState.Content(outcome.replies.map { it.toUi() })
            }
        RepliesOutcome.NetworkError -> RepliesUiState.Error
    }
}

/**
 * Pure projection of the reply composer's editing state, mirroring [postCreationUiState]'s gate:
 * - [charCount] is the number of **Unicode code points** in `content` (a 280-emoji string counts 280,
 *   NOT 560 — UTF-16 unit counting would wrongly reject it; reuses [String.codePointLength]).
 * - [overLimit] iff [charCount] > [MAX_REPLY_CONTENT_CODE_POINTS].
 * - submit ([submitEnabled]) is enabled iff `content` has ≥1 non-blank code point AND ≤280 code points AND
 *   not in-flight — so the client NEVER submits empty or over-limit content (empty-vs-too-long is a pure
 *   client concern; the server emits one `invalid_request` for both). No PII, no wall-clock.
 */
data class ReplyComposerUiState(
    val charCount: Int,
    val overLimit: Boolean,
    val submitEnabled: Boolean,
)

fun replyComposerUiState(
    content: String,
    inFlight: Boolean,
): ReplyComposerUiState {
    val charCount = content.codePointLength()
    val overLimit = charCount > MAX_REPLY_CONTENT_CODE_POINTS
    val withinLength = content.isNotBlank() && charCount <= MAX_REPLY_CONTENT_CODE_POINTS
    return ReplyComposerUiState(
        charCount = charCount,
        overLimit = overLimit,
        submitEnabled = withinLength && !inFlight,
    )
}

/**
 * Which message banner (if any) shows on the detail surface. Deliberately a small sealed set of static
 * message keys — it can NEVER carry the coordinate, the `author_id`, or any PII (mirrors
 * `PostCreationBanner`). The rate-limit members carry only the coarse non-PII [resetHours] countdown
 * (filled into the cap-upsell `%1$s`). The Compose layer maps each member to a `stringResource`.
 */
sealed interface PostDetailBanner {
    /** Like 429 → `post_detail_likes_cap_upsell` formatted with the reset countdown. */
    data class LikeCap(val resetHours: Int) : PostDetailBanner

    /** Reply 429 → `post_detail_reply_cap_upsell` formatted with the reset countdown. */
    data class ReplyCap(val resetHours: Int) : PostDetailBanner

    /** A `404 post_not_found` (like or reply) → the terminal `post_detail_post_gone` copy with NO retry
     *  control (a retry would always re-fail — the post is gone). */
    data object PostGone : PostDetailBanner

    /** Reply `InvalidContent` (defensive) / `NetworkError`, and a like `NetworkError` → the generic
     *  retryable network copy (`signin_error_network` + a retry control). */
    data object Network : PostDetailBanner
}

/** Maps the last like [outcome] to its banner (null when none / a happy toggle). Exhaustive — no wildcard. */
fun likeBanner(outcome: LikeOutcome?): PostDetailBanner? =
    when (outcome) {
        is LikeOutcome.RateLimited -> PostDetailBanner.LikeCap(resetHours(outcome.retryAfterSeconds))
        LikeOutcome.PostGone -> PostDetailBanner.PostGone
        LikeOutcome.NetworkError -> PostDetailBanner.Network
        LikeOutcome.Liked, LikeOutcome.Unliked, null -> null
    }

/** Maps the last reply-post [outcome] to its banner (null when none / a success). Exhaustive — no wildcard. */
fun replyBanner(outcome: ReplyPostOutcome?): PostDetailBanner? =
    when (outcome) {
        is ReplyPostOutcome.RateLimited -> PostDetailBanner.ReplyCap(resetHours(outcome.retryAfterSeconds))
        ReplyPostOutcome.PostGone -> PostDetailBanner.PostGone
        ReplyPostOutcome.InvalidContent, ReplyPostOutcome.NetworkError -> PostDetailBanner.Network
        is ReplyPostOutcome.Success, null -> null
    }

/**
 * Coarse reset countdown for the cap-upsell `%1$s` (`post_detail_reset_hours` = "%1$d jam"): whole hours,
 * rounded UP, with a floor of 1 (a `Retry-After` of 0/absent still reads "1 jam", never "0 jam"). A finer
 * jam+menit countdown is the deferred `mobile-timeline-relative-timestamp` duration-formatter concern.
 */
fun resetHours(retryAfterSeconds: Long): Int = maxOf(1, ceil(retryAfterSeconds.toDouble() / SECONDS_PER_HOUR).toInt())

private const val SECONDS_PER_HOUR: Double = 3600.0
