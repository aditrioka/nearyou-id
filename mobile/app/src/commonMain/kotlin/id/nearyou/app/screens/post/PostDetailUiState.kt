package id.nearyou.app.screens.post

import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyDto
import id.nearyou.app.post.ReplyPostOutcome

/** The reply length limit — 280 code points (same cap as posts; `post-replies` content guard). The
 *  client gate counts RAW Unicode code points (no NFKC) for the live counter + submit-enable; the server
 *  NFKC-normalizes + length-guards authoritatively (design D9), so the client gate is only a UX nicety. */
const val MAX_REPLY_CONTENT_CODE_POINTS: Int = 280

/**
 * The display projection of a reply — the model the reply rows render. A row shows [content] + the
 * [createdAt] treatment + (mobile-block-from-content D7, mockup frame 7) the author **display identity**
 * from [authorUsername]/[authorDisplayName] (null on an older-backend body → the avatar, the name and the
 * block item are omitted gracefully). [authorId] (a UUID) is carried SOLELY for the client-side
 * authorship gates and as the block-request path param — it MUST NEVER be rendered or logged (the
 * "Pure PostDetailUiState projection (PII-free)" carve-out). [isOwn] is the fail-closed authorship bit
 * (`selfUserId != null && authorId == selfUserId`): it hides the block item and shows the own-reply
 * delete item (post-detail-vm-reply-delete-restyle). NO surfaced `isAutoHidden` (the viewer's own
 * auto-hidden reply renders identically to a live one in v1) and NO `deletedAt` (effectively dead on this
 * list path). [id] is the reply's own UUID (not author PII), kept as a stable `LazyColumn` key.
 */
data class ReplyUi(
    val id: String,
    val content: String,
    val createdAt: String,
    val authorId: String,
    val authorUsername: String? = null,
    val authorDisplayName: String? = null,
    val isOwn: Boolean = false,
)

/** Projects a [ReplyDto] to its [ReplyUi] — drops `isAutoHidden`, `deletedAt`, `updatedAt`, `postId`;
 *  keeps the display identity (renderable) + `authorId` (gate/path-only, never rendered); stamps [ReplyUi.isOwn]
 *  against [selfUserId] (null — unresolved / malformed token — is never own: fail closed). */
fun ReplyDto.toUi(selfUserId: String? = null): ReplyUi =
    ReplyUi(
        id = id,
        content = content,
        createdAt = createdAt,
        authorId = authorId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        isOwn = selfUserId != null && authorId == selfUserId,
    )

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
 * The ONE screen-level state `PostDetailViewModel.uiState` projects (docs/11 § 2.2;
 * post-detail-vm-reply-delete-restyle) — a data class because its parts vary independently. Carries:
 * the displayed post [content] + the freshness-read [editedAtIso] / [isAuthor] / [authorUserId]; the like
 * state ([liked], [likeCount] — null = unavailable, [likeInFlight], [likeOutcome] driving the banner / cap
 * dialog); [replyCount] + the [replies] list sub-state + the load-more footer flags; the composer's
 * [replyInFlight] / [replyOutcome]; the report / block / delete dialog targets; and the one-shots, each
 * cleared via its `onXxxShown()` / dismiss callback ([replyPosted] clears the draft, [reportMessage],
 * [blockMessage], [blockPopBack], [deleteFailed]).
 *
 * [authorUserId] (the post author's UUID) is carried SOLELY as the post-header block target and the
 * profile-navigation argument — it MUST NEVER be rendered or logged. The session self id is NOT carried:
 * it only stamps [ReplyUi.isOwn], and [selfResolved] says whether it has resolved yet (the reply block item
 * stays absent until it has — fail closed). No coordinate, no token material.
 */
data class PostDetailUiState(
    val content: String,
    val editedAtIso: String? = null,
    val isAuthor: Boolean = false,
    val authorUserId: String? = null,
    val liked: Boolean = false,
    val likeCount: Long? = null,
    val likeInFlight: Boolean = false,
    val likeOutcome: LikeOutcome? = null,
    val replyCount: Int = 0,
    val replies: RepliesUiState = RepliesUiState.Loading,
    val selfResolved: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val replyInFlight: Boolean = false,
    val replyOutcome: ReplyPostOutcome? = null,
    val replyPosted: Boolean = false,
    val reportTarget: ReportTarget? = null,
    val reportMessage: PostDetailReportMessage? = null,
    val blockTarget: BlockTarget? = null,
    val blockMessage: PostDetailBlockMessage? = null,
    val blockPopBack: Boolean = false,
    val deleteTarget: String? = null,
    val deleteFailed: Boolean = false,
)

/**
 * Maps the current replies [outcome] (null = not yet loaded) + [inFlight] to the list sub-state, stamping
 * each reply's [ReplyUi.isOwn] against [selfUserId].
 * In-flight (or not-yet-loaded) ⇒ [Loading]; `Loaded` non-empty ⇒ [Content]; `Loaded` empty ⇒ [Empty];
 * `NetworkError` ⇒ [Error]. Exhaustive over [RepliesOutcome] (no wildcard).
 */
fun repliesUiState(
    outcome: RepliesOutcome?,
    inFlight: Boolean,
    selfUserId: String? = null,
): RepliesUiState {
    if (inFlight) return RepliesUiState.Loading
    return when (outcome) {
        null -> RepliesUiState.Loading
        is RepliesOutcome.Loaded ->
            if (outcome.replies.isEmpty()) {
                RepliesUiState.Empty
            } else {
                RepliesUiState.Content(outcome.replies.map { it.toUi(selfUserId) })
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
 * `PostCreationBanner`). The Compose layer maps each member to a `stringResource`. A like / reply `429`
 * is NOT a banner: the daily cap is surfaced by the shared frame-18 `DailyCapUpsellDialog` with its live
 * countdown + paywall CTA (cap-upsell-parity) — one surface per cap hit.
 */
sealed interface PostDetailBanner {
    /** A `404 post_not_found` (like or reply) → the terminal `post_detail_post_gone` copy with NO retry
     *  control (a retry would always re-fail — the post is gone). */
    data object PostGone : PostDetailBanner

    /** Reply `InvalidContent` (defensive) / `NetworkError`, and a like `NetworkError` → the generic
     *  retryable network copy (`signin_error_network` + a retry control). */
    data object Network : PostDetailBanner
}

/** Maps the last like [outcome] to its banner (null when none / a happy toggle / the cap — the cap is the
 *  dialog, not a banner). Exhaustive — no wildcard. */
fun likeBanner(outcome: LikeOutcome?): PostDetailBanner? =
    when (outcome) {
        LikeOutcome.PostGone -> PostDetailBanner.PostGone
        LikeOutcome.NetworkError -> PostDetailBanner.Network
        is LikeOutcome.RateLimited, LikeOutcome.Liked, LikeOutcome.Unliked, null -> null
    }

/** Maps the last reply-post [outcome] to its banner (null when none / a success / the cap — the cap is the
 *  dialog, not a banner). Exhaustive — no wildcard. */
fun replyBanner(outcome: ReplyPostOutcome?): PostDetailBanner? =
    when (outcome) {
        ReplyPostOutcome.PostGone -> PostDetailBanner.PostGone
        ReplyPostOutcome.InvalidContent, ReplyPostOutcome.NetworkError -> PostDetailBanner.Network
        is ReplyPostOutcome.RateLimited, is ReplyPostOutcome.Success, null -> null
    }

/**
 * Which content the post-detail report dialog is currently targeting (mobile-content-report). A nullable
 * `ReportTarget?` on the VM drives the dialog: null = no dialog; [Post] = the post-header report (the post
 * id is held by the VM); [Reply] = a per-reply report carrying ONLY the reply [Reply.replyId] (the report
 * `target_id`). NO author identity is ever modelled here — the reply target is the reply id alone (the
 * `mobile-post-detail` PII contract; `author_id` is intentionally dropped and never sent/rendered).
 */
sealed interface ReportTarget {
    data object Post : ReportTarget

    data class Reply(val replyId: String) : ReportTarget
}

/**
 * Which target the post-detail block confirmation dialog is aimed at (mobile-block-from-content). A
 * nullable `BlockTarget?` on the VM drives the shared `BlockConfirmDialog`: null = no dialog. Carries
 * the target's public [username] (display identity — interpolated into the canonical dialog copy) and
 * the [targetUserId] UUID, which is NEVER rendered or logged — it exists solely as the
 * `POST /api/v1/blocks/{userId}` path param (the MODIFIED PII-projection carve-out). [Reply.replyId]
 * additionally identifies the row to remove locally on a confirmed block.
 */
sealed interface BlockTarget {
    val username: String
    val targetUserId: String

    data class Post(
        override val targetUserId: String,
        override val username: String,
    ) : BlockTarget

    data class Reply(
        val replyId: String,
        override val targetUserId: String,
        override val username: String,
    ) : BlockTarget
}

/**
 * The user-facing one-shot block-result message keys for the post-detail surface (each maps to a
 * `:shared:resources` string at the screen), mirroring the profile treatment: [SUCCESS] → the
 * "Pengguna telah diblokir" toast; [RATE_LIMITED] → the block rate-limit copy; [FAILED] → the generic
 * action-failed copy. Held as a nullable VM field cleared via `onBlockMessageShown()` (docs/11 § 2.2).
 */
enum class PostDetailBlockMessage {
    SUCCESS,
    RATE_LIMITED,
    FAILED,
}

/**
 * Maps a [BlockOutcome] to its one-shot post-detail message — identical to the profile mapping
 * (`Blocked` → success, `RateLimited` → rate-limit, `NetworkError` → action-failed). Pure + exhaustive
 * (no wildcard) so the mapping is unit-testable without a backend.
 */
fun postDetailBlockMessage(outcome: BlockOutcome): PostDetailBlockMessage =
    when (outcome) {
        BlockOutcome.Blocked -> PostDetailBlockMessage.SUCCESS
        is BlockOutcome.RateLimited -> PostDetailBlockMessage.RATE_LIMITED
        BlockOutcome.NetworkError -> PostDetailBlockMessage.FAILED
    }

/**
 * The user-facing one-shot report-result message keys for the post-detail surface (each maps to a
 * `:shared:resources` string at the screen). Held as a nullable VM field cleared via
 * `onReportMessageShown()` — NOT a `Channel`/`SharedFlow` event bus (docs/11 § 2.2). UNLIKE the profile
 * surface, [Submitted] AND [Duplicate] both map to [SUCCESS] (anti-enumeration — `docs/03`:234: a reporter
 * learns nothing about a prior report), so there is deliberately NO distinct "already reported" key here.
 */
enum class PostDetailReportMessage {
    /** Report 204 (Submitted) OR 409 duplicate_report (Duplicate) → the SAME "report submitted" success copy. */
    SUCCESS,

    /** Report 429 → the report rate-limit copy. */
    RATE_LIMITED,

    /** A report network/transport failure → a generic retryable "try again" copy. */
    FAILED,
}

/**
 * Maps a [ReportOutcome] to its one-shot post-detail message (the anti-enumeration mapping, design D3):
 * [ReportOutcome.Submitted] AND [ReportOutcome.Duplicate] → [PostDetailReportMessage.SUCCESS] (identical —
 * no "already reported" wording); [ReportOutcome.RateLimited] → [PostDetailReportMessage.RATE_LIMITED];
 * [ReportOutcome.NetworkError] → [PostDetailReportMessage.FAILED]. Pure + exhaustive (no wildcard) so the
 * mapping is unit-testable without a backend.
 */
fun postDetailReportMessage(outcome: ReportOutcome): PostDetailReportMessage =
    when (outcome) {
        ReportOutcome.Submitted, ReportOutcome.Duplicate -> PostDetailReportMessage.SUCCESS
        is ReportOutcome.RateLimited -> PostDetailReportMessage.RATE_LIMITED
        ReportOutcome.NetworkError -> PostDetailReportMessage.FAILED
    }
